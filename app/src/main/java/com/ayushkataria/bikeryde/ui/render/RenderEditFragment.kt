package com.ayushkataria.bikeryde.ui.render

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.media.MergedStop
import com.ayushkataria.bikeryde.media.RenderImageStorage
import com.ayushkataria.bikeryde.media.RenderRepository
import com.ayushkataria.bikeryde.media.RenderType
import com.ayushkataria.bikeryde.media.RideRenderDataAssembler
import com.ayushkataria.bikeryde.media.StaticImageRenderer
import com.ayushkataria.bikeryde.media.VideoDurationRecommender
import com.ayushkataria.bikeryde.media.mergedStopsForRide
import com.ayushkataria.bikeryde.media.VideoRenderWorker
import com.ayushkataria.bikeryde.ride.RideEventAction
import com.ayushkataria.bikeryde.ride.RideRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The design doc's "tapping [Create Image/Animation] opens a customize screen" step: lets the
 * rider rename stops (e.g. a recorded "Konappana Agrahara" becomes "Home") and attach background
 * photos — one cover photo for a static image, or one per stop for a video, where they fade into
 * each other as the route animates past each stop — before the actual render kicks off.
 */
class RenderEditFragment : Fragment(R.layout.fragment_render_edit) {

    private lateinit var repository: RideRepository
    private lateinit var renderType: RenderType
    private var rideId: Long = -1L

    private lateinit var coverImageCard: View
    private lateinit var coverImagePreview: ImageView
    private lateinit var coverImageButton: MaterialButton
    private lateinit var coverImageRemoveButton: View
    private lateinit var videoBackgroundHint: View
    private lateinit var videoLengthSection: View
    private lateinit var videoLengthValueText: TextView
    private lateinit var videoLengthSlider: Slider
    private lateinit var stopsContainer: ViewGroup
    private lateinit var generateButton: MaterialButton

    private var stops: List<MergedStop> = emptyList()
    private var coverImagePath: String? = null
    private val stopBackgroundPaths = mutableMapOf<Int, String>()
    private val stopThumbnails = mutableMapOf<Int, ImageView>()
    private val stopNameInputs = mutableMapOf<Int, TextInputEditText>()
    private val excludedStopIndices = mutableSetOf<Int>()

    private var recommendedDurationS = VideoDurationRecommender.MIN_SECONDS
    private var durationMultiplier = VideoDurationRecommender.DEFAULT_MULTIPLIER

    /** null = no pick pending; -1 = picking the cover photo; >=0 = picking that stop's index. */
    private var pendingPickTarget: Int? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pendingPickTarget
        pendingPickTarget = null
        if (uri != null && target != null) onImagePicked(target, uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rideId = requireArguments().getLong(ARG_RIDE_ID)
        renderType = RenderType.valueOf(requireArguments().getString(ARG_RENDER_TYPE)!!)
        repository = RideRepository(requireContext())

        view.findViewById<TextView>(R.id.topBarTitle).setText(
            if (renderType == RenderType.VIDEO) R.string.render_edit_title_video else R.string.render_edit_title_image
        )
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        coverImageCard = view.findViewById(R.id.coverImageCard)
        coverImagePreview = view.findViewById(R.id.coverImagePreview)
        coverImageButton = view.findViewById(R.id.coverImageButton)
        coverImageRemoveButton = view.findViewById(R.id.coverImageRemoveButton)
        videoBackgroundHint = view.findViewById(R.id.videoBackgroundHint)
        videoLengthSection = view.findViewById(R.id.videoLengthSection)
        videoLengthValueText = view.findViewById(R.id.videoLengthValueText)
        videoLengthSlider = view.findViewById(R.id.videoLengthSlider)
        stopsContainer = view.findViewById(R.id.stopsContainer)
        generateButton = view.findViewById(R.id.generateButton)

        val isVideo = renderType == RenderType.VIDEO
        coverImageCard.visibility = if (isVideo) View.GONE else View.VISIBLE
        videoBackgroundHint.visibility = if (isVideo) View.VISIBLE else View.GONE
        videoLengthSection.visibility = if (isVideo) View.VISIBLE else View.GONE
        generateButton.setText(R.string.render_edit_generate)

        coverImageButton.setOnClickListener { launchPicker(COVER_TARGET) }
        coverImageRemoveButton.setOnClickListener { clearCoverImage() }
        generateButton.setOnClickListener { onGenerateClicked() }

        if (isVideo) {
            videoLengthSlider.value = durationMultiplier
            videoLengthSlider.addOnChangeListener { _, value, _ ->
                durationMultiplier = value
                updateVideoLengthPreview()
            }
        }

        loadStops()
    }

    private fun loadStops() {
        viewLifecycleOwner.lifecycleScope.launch {
            stops = mergedStopsForRide(repository, rideId)
            if (renderType == RenderType.VIDEO) {
                recommendedDurationS = VideoDurationRecommender.recommend(stops.size)
                updateVideoLengthPreview()
            }
            stopsContainer.removeAllViews()
            if (stops.isEmpty()) {
                val empty = TextView(requireContext()).apply {
                    text = getString(R.string.render_edit_stops_empty)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.md_on_surface_variant))
                }
                stopsContainer.addView(empty)
                return@launch
            }
            stops.forEachIndexed { index, stop -> stopsContainer.addView(buildStopRow(index, stop)) }
        }
    }

    private fun buildStopRow(index: Int, stop: MergedStop): View {
        val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_stop_edit_row, stopsContainer, false)
        val thumbnail = row.findViewById<ImageView>(R.id.rowThumbnail)
        val meta = row.findViewById<TextView>(R.id.rowMeta)
        val nameInput = row.findViewById<TextInputEditText>(R.id.rowNameInput)
        val includeCheckbox = row.findViewById<MaterialCheckBox>(R.id.rowIncludeCheckbox)

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        meta.text = getString(
            R.string.stop_legend_row_format,
            stop.actions.joinToString(", ") { stopActionLabel(it) },
            stop.placeName ?: getString(R.string.unknown_location),
            timeFormat.format(stop.timestamp)
        )
        nameInput.setText(stop.placeName ?: "")

        if (renderType == RenderType.VIDEO) {
            thumbnail.visibility = View.VISIBLE
            thumbnail.setOnClickListener { launchPicker(index) }
            stopThumbnails[index] = thumbnail
        } else {
            thumbnail.visibility = View.GONE
        }
        stopNameInputs[index] = nameInput

        includeCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) excludedStopIndices.remove(index) else excludedStopIndices.add(index)
            setStopRowIncluded(row, thumbnail, nameInput, isChecked)
        }

        (row.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = if (index == 0) 0 else dpToPx(20)
        return row
    }

    /** Dims a stop row's other controls when it's unchecked — excluded stops still show their
     * recorded info for context, but can't be renamed or given a photo since they won't render. */
    private fun setStopRowIncluded(row: View, thumbnail: ImageView, nameInput: TextInputEditText, included: Boolean) {
        row.alpha = if (included) 1f else 0.5f
        thumbnail.isEnabled = included
        nameInput.isEnabled = included
    }

    private fun updateVideoLengthPreview() {
        val finalSeconds = VideoDurationRecommender.apply(recommendedDurationS, durationMultiplier)
        val isDefault = kotlin.math.abs(durationMultiplier - VideoDurationRecommender.DEFAULT_MULTIPLIER) < 0.01f
        videoLengthValueText.text = if (isDefault) {
            getString(R.string.render_edit_length_value_recommended_format, finalSeconds)
        } else {
            getString(
                R.string.render_edit_length_value_multiplier_format,
                finalSeconds,
                String.format(Locale.US, "%.1fx", durationMultiplier)
            )
        }
    }

    private fun launchPicker(target: Int) {
        pendingPickTarget = target
        pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun onImagePicked(target: Int, uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val path = runCatching { RenderImageStorage.copyToAppStorage(requireContext(), uri) }.getOrNull()
            if (path == null) {
                Toast.makeText(requireContext(), R.string.render_image_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Decode the thumbnail from the copy we just made, not the picker's uri — a photo
            // picker's content:// grant can fail to re-open by the time this runs, which
            // setImageURI(uri) swallows silently (leaving the slot blank instead of erroring).
            val thumbnail = RenderImageStorage.decodeSampledBitmap(path, THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX)
            if (target == COVER_TARGET) {
                coverImagePath = path
                coverImagePreview.setImageBitmap(thumbnail)
                coverImagePreview.visibility = View.VISIBLE
                coverImageButton.setText(R.string.render_edit_change_photo)
                coverImageRemoveButton.visibility = View.VISIBLE
            } else {
                stopBackgroundPaths[target] = path
                stopThumbnails[target]?.apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setPadding(0, 0, 0, 0)
                    setImageBitmap(thumbnail)
                }
            }
        }
    }

    private fun clearCoverImage() {
        coverImagePath = null
        coverImagePreview.setImageBitmap(null)
        coverImagePreview.visibility = View.GONE
        coverImageButton.setText(R.string.render_edit_add_photo)
        coverImageRemoveButton.visibility = View.GONE
    }

    private fun onGenerateClicked() {
        val nameOverrides = stopNameInputs.mapValues { (_, field) -> field.text?.toString().orEmpty() }
        generateButton.isEnabled = false

        when (renderType) {
            RenderType.IMAGE -> generateImage(nameOverrides)
            RenderType.VIDEO -> generateVideo(nameOverrides)
        }
    }

    private fun generateImage(nameOverrides: Map<Int, String>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val renderRepository = RenderRepository(requireContext())
            // This render replaces any previous preview for this ride — delete it now rather
            // than leaving an orphaned preview file behind in app storage.
            renderRepository.getLatest(rideId, RenderType.IMAGE)?.filePath?.let { path ->
                runCatching { File(path).delete() }
            }
            val renderId = renderRepository.insertQueued(rideId, RenderType.IMAGE)
            renderRepository.markProcessing(renderId)

            val data = RideRenderDataAssembler(repository).assemble(
                rideId = rideId,
                stopNameOverrides = nameOverrides,
                excludedStopIndices = excludedStopIndices,
                coverImagePath = coverImagePath
            )
            val output = data?.let { runCatching { StaticImageRenderer(requireContext()).render(it) }.getOrNull() }
            generateButton.isEnabled = true
            if (output == null) {
                renderRepository.markFailed(renderId)
                Toast.makeText(requireContext(), R.string.render_image_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            renderRepository.markDone(renderId, output.filePath, StaticImageRenderer.RESOLUTION_LABEL, fps = 0)
            navigateToPreview(RenderPreviewFragment.args(rideId, RenderType.IMAGE, output.uri.toString()))
        }
    }

    private fun generateVideo(nameOverrides: Map<Int, String>) {
        val stopCount = stops.size
        val namesArray: Array<String?> = Array(stopCount) { i -> nameOverrides[i].orEmpty() }
        val backgroundsArray: Array<String?> = Array(stopCount) { i -> stopBackgroundPaths[i].orEmpty() }

        val request = OneTimeWorkRequestBuilder<VideoRenderWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(VideoRenderWorker.KEY_RIDE_ID, rideId)
                    .putStringArray(VideoRenderWorker.KEY_STOP_NAMES, namesArray)
                    .putStringArray(VideoRenderWorker.KEY_STOP_BACKGROUNDS, backgroundsArray)
                    .putIntArray(VideoRenderWorker.KEY_EXCLUDED_STOPS, excludedStopIndices.toIntArray())
                    .putInt(
                        VideoRenderWorker.KEY_DURATION_SECONDS,
                        VideoDurationRecommender.apply(recommendedDurationS, durationMultiplier)
                    )
                    .build()
            )
            .build()

        // REPLACE (not KEEP): this is an explicit user-triggered (re)generate, so it should
        // always actually run — KEEP would silently no-op if a same-named request were still
        // pending, leaving the WorkRequest id below pointing at nothing WorkManager ever runs.
        WorkManager.getInstance(requireContext()).enqueueUniqueWork(
            VideoRenderWorker.uniqueWorkName(rideId),
            ExistingWorkPolicy.REPLACE,
            request
        )
        Toast.makeText(requireContext(), R.string.render_video_queued, Toast.LENGTH_SHORT).show()
        navigateToPreview(RenderPreviewFragment.args(rideId, RenderType.VIDEO, workId = request.id.toString()))
    }

    /** Pops this customize screen off the back stack on the way to the preview — while a render is
     * in flight (video) or just finished (image), there's nothing left here to re-submit, so back
     * from the preview should return to whatever screen opened the render flow, not this form. */
    private fun navigateToPreview(args: Bundle) {
        findNavController().navigate(
            R.id.action_renderEdit_to_renderPreview,
            args,
            NavOptions.Builder().setPopUpTo(R.id.renderEditFragment, true).build()
        )
    }

    private fun stopActionLabel(action: RideEventAction): String = when (action) {
        RideEventAction.START -> getString(R.string.stop_action_start)
        RideEventAction.PAUSE -> getString(R.string.stop_action_pause)
        RideEventAction.RESUME -> getString(R.string.stop_action_resume)
        RideEventAction.END -> getString(R.string.stop_action_end)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val COVER_TARGET = -1
        private const val THUMBNAIL_SIZE_PX = 400
        const val ARG_RIDE_ID = "rideId"
        const val ARG_RENDER_TYPE = "renderType"

        fun args(rideId: Long, renderType: RenderType): Bundle = bundleOf(
            ARG_RIDE_ID to rideId,
            ARG_RENDER_TYPE to renderType.name
        )
    }
}
