package com.ayushkataria.bikeryde.media

import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ayushkataria.bikeryde.ride.RideRepository
import kotlinx.coroutines.delay
import java.io.File

/**
 * Renders a ride's animated route video in the background via [MediaCodec] + a Surface input
 * (drawing each frame with [RouteFrameDrawer] straight onto the encoder's Surface, no OpenGL
 * needed), muxes it to MP4, and moves it into app-private storage ([RenderFileStorage]) — a
 * preview only, not saved anywhere visible until the user taps Save. Reports progress via
 * [setProgress] and a persistent notification, per design doc §5.3/§6.
 *
 * Operates on [RideRenderData] — day count is opaque to this worker, so once multi-day tracking
 * exists, rendering a multi-day trip's video is the same code path as a single day's.
 */
class VideoRenderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val rideId = inputData.getLong(KEY_RIDE_ID, -1L)
        if (rideId == -1L) return Result.failure()

        RenderNotifications.ensureChannel(applicationContext)
        setForeground(createForegroundInfo(0))

        val stopNames = inputData.getStringArray(KEY_STOP_NAMES)
        val stopBackgrounds = inputData.getStringArray(KEY_STOP_BACKGROUNDS)
        val stopNameOverrides = stopNames?.mapIndexedNotNull { i, name ->
            if (name.isNullOrBlank()) null else i to name
        }?.toMap() ?: emptyMap()
        val stopBackgroundPaths = stopBackgrounds?.mapIndexedNotNull { i, path ->
            if (path.isNullOrBlank()) null else i to path
        }?.toMap() ?: emptyMap()

        val rideRepository = RideRepository(applicationContext)
        val renderRepository = RenderRepository(applicationContext)
        val data = RideRenderDataAssembler(rideRepository).assemble(rideId, stopNameOverrides, stopBackgroundPaths)
        if (data == null || data.allPoints.size < 2) return Result.failure()

        val recommendedDurationS = VideoDurationRecommender.recommend(data.allStops.size)
        val durationS = inputData.getInt(KEY_DURATION_SECONDS, recommendedDurationS)

        // This render replaces any previous preview for the same ride/type — delete it now
        // rather than leaving orphaned preview files behind in app storage.
        renderRepository.getLatest(rideId, RenderType.VIDEO)?.filePath?.let { path ->
            runCatching { File(path).delete() }
        }

        val renderId = renderRepository.insertQueued(rideId, RenderType.VIDEO, workId = id.toString())
        renderRepository.markProcessing(renderId)

        val backgroundPaths = listOfNotNull(data.coverImagePath) + data.allStops.mapNotNull { it.backgroundImagePath }
        BackgroundImageCache.preload(backgroundPaths)

        return try {
            val (outputFile, fps) = renderToFile(data, durationS) { percent ->
                setProgress(workDataOf(KEY_PROGRESS to percent))
                setForeground(createForegroundInfo(percent))
            }
            val output = saveToAppStorage(outputFile)
            renderRepository.markDone(renderId, output.filePath, "${WIDTH}x$HEIGHT", fps)
            RenderNotifications.showReady(applicationContext, rideId)
            Result.success(workDataOf(KEY_RESULT_URI to output.uri.toString()))
        } catch (e: Exception) {
            renderRepository.markFailed(renderId)
            Result.failure()
        } finally {
            BackgroundImageCache.clear()
        }
    }

    private suspend fun createForegroundInfo(percent: Int): ForegroundInfo {
        val notification = RenderNotifications.progressNotification(applicationContext, percent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                RenderNotifications.PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(RenderNotifications.PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Picks the highest frame rate the device's H.264 encoder actually supports at [WIDTH]x[HEIGHT]
     * — 60fps target, falling back to 30fps — per the design doc's capability-detection open question,
     * rather than assuming hardware support and failing mid-render.
     */
    private fun chooseFrameRate(): Int {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
            val encoderName = codecList.findEncoderForFormat(format) ?: return FALLBACK_FPS
            val codecInfo = codecList.codecInfos.first { it.name == encoderName }
            val videoCaps = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            if (videoCaps?.areSizeAndRateSupported(WIDTH, HEIGHT, TARGET_FPS.toDouble()) == true) TARGET_FPS else FALLBACK_FPS
        } catch (e: Exception) {
            FALLBACK_FPS
        }
    }

    private suspend fun renderToFile(data: RideRenderData, durationS: Int, onProgress: suspend (Int) -> Unit): Pair<File, Int> {
        val fps = chooseFrameRate()
        val frameCount = fps * durationS

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val outputFile = File(applicationContext.cacheDir, "ride_render_${System.currentTimeMillis()}.mp4")
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0 && muxerStarted && encodedData != null) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            // Pace frame submission to real time so the encoder's automatic buffer timestamps
            // (there's no public API to set them explicitly on a lockCanvas-fed input surface)
            // produce a video whose duration actually matches frameCount / fps.
            val startNanos = System.nanoTime()
            for (frame in 0 until frameCount) {
                val progress = frame / (frameCount - 1).toFloat()
                val canvas = inputSurface.lockCanvas(null)
                try {
                    RouteFrameDrawer.draw(canvas, WIDTH, HEIGHT, data, progress)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                drainEncoder(false)
                onProgress(((frame + 1) * 100 / frameCount))

                val targetElapsedNanos = frame.toLong() * 1_000_000_000L / fps
                val remainingNanos = targetElapsedNanos - (System.nanoTime() - startNanos)
                if (remainingNanos > 0) delay(remainingNanos / 1_000_000)
            }
            drainEncoder(true)
        } finally {
            encoder.stop()
            encoder.release()
            inputSurface.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }

        return outputFile to fps
    }

    /** Moves the temp working file (written to [Context.getCacheDir] during encoding) into
     * app-private, persistent storage — the actual preview location. */
    private fun saveToAppStorage(file: File): RenderOutput {
        val dest = RenderFileStorage.newVideoFile(applicationContext)
        if (!file.renameTo(dest)) {
            file.copyTo(dest, overwrite = true)
            file.delete()
        }
        return RenderOutput(RenderFileStorage.uriFor(applicationContext, dest), dest.absolutePath)
    }

    companion object {
        const val KEY_RIDE_ID = "rideId"
        const val KEY_PROGRESS = "progress"
        const val KEY_RESULT_URI = "resultUri"
        /** String array aligned by index to [RideRepository.getEvents]'s order — one custom stop name per entry, empty for none. */
        const val KEY_STOP_NAMES = "stopNames"
        /** String array aligned the same way — one background photo path per stop, empty for none. */
        const val KEY_STOP_BACKGROUNDS = "stopBackgrounds"
        /** Animation length in seconds — defaults to [VideoDurationRecommender.recommend] if omitted. */
        const val KEY_DURATION_SECONDS = "durationSeconds"

        const val WIDTH = 1080
        const val HEIGHT = 1920
        private const val TARGET_FPS = 60
        private const val FALLBACK_FPS = 30
        private const val BIT_RATE = 8_000_000

        fun uniqueWorkName(rideId: Long) = "video_render_$rideId"
    }
}
