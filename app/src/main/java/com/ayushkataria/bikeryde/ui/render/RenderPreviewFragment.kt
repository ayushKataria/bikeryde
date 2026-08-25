package com.ayushkataria.bikeryde.ui.render

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.media.RenderRepository
import com.ayushkataria.bikeryde.media.RenderStatus
import com.ayushkataria.bikeryde.media.RenderType
import com.ayushkataria.bikeryde.media.VideoRenderWorker
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Preview/export screen for a completed (or in-progress) render — the design doc's "tapping opens
 * preview/export screen" (§5.3). Works for both [RenderType.IMAGE] and [RenderType.VIDEO]; a
 * static image is just an already-finished render, a video may still be running when this screen
 * opens (e.g. from the "rendering ready" notification), in which case it follows the WorkManager
 * job's live progress until the result is available.
 */
class RenderPreviewFragment : Fragment(R.layout.fragment_render_preview) {

    private lateinit var previewImage: ImageView
    private lateinit var previewVideo: VideoView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var shareButton: MaterialButton

    private lateinit var renderRepository: RenderRepository
    private var resultUri: Uri? = null
    private lateinit var renderType: RenderType

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rideId = requireArguments().getLong(ARG_RIDE_ID)
        renderType = RenderType.valueOf(requireArguments().getString(ARG_RENDER_TYPE)!!)
        val initialUri = requireArguments().getString(ARG_RESULT_URI)

        view.findViewById<TextView>(R.id.topBarTitle).setText(
            if (renderType == RenderType.VIDEO) R.string.post_ride_create_animation else R.string.post_ride_create_image
        )
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        previewImage = view.findViewById(R.id.previewImage)
        previewVideo = view.findViewById(R.id.previewVideo)
        loadingSpinner = view.findViewById(R.id.loadingSpinner)
        statusText = view.findViewById(R.id.statusText)
        shareButton = view.findViewById(R.id.shareButton)
        shareButton.setOnClickListener { shareResult() }

        renderRepository = RenderRepository(requireContext())

        if (initialUri != null) {
            showResult(Uri.parse(initialUri))
        } else {
            loadExistingOrFollowWork(rideId)
        }
    }

    private fun loadExistingOrFollowWork(rideId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val existing = renderRepository.getLatest(rideId, renderType)
            val filePath = existing?.filePath
            if (existing?.status == RenderStatus.DONE && filePath != null) {
                showResult(Uri.parse(filePath))
                return@launch
            }
            if (renderType == RenderType.VIDEO) {
                followVideoWork(rideId)
            } else {
                statusText.visibility = View.VISIBLE
                statusText.setText(R.string.render_not_available)
                loadingSpinner.visibility = View.GONE
            }
        }
    }

    private fun followVideoWork(rideId: Long) {
        statusText.visibility = View.VISIBLE
        val liveData = WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData(VideoRenderWorker.uniqueWorkName(rideId))
        liveData.observe(viewLifecycleOwner) { infos ->
            val info = infos.firstOrNull() ?: return@observe
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val uriString = info.outputData.getString(VideoRenderWorker.KEY_RESULT_URI)
                    if (uriString != null) showResult(Uri.parse(uriString))
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    loadingSpinner.visibility = View.GONE
                    statusText.setText(R.string.render_failed)
                }
                else -> {
                    val percent = info.progress.getInt(VideoRenderWorker.KEY_PROGRESS, 0)
                    statusText.text = getString(R.string.render_progress_format, percent)
                }
            }
        }
    }

    private fun showResult(uri: Uri) {
        resultUri = uri
        loadingSpinner.visibility = View.GONE
        statusText.visibility = View.GONE
        shareButton.isEnabled = true
        if (renderType == RenderType.VIDEO) {
            previewVideo.visibility = View.VISIBLE
            previewVideo.setVideoURI(uri)
            previewVideo.setMediaController(MediaController(requireContext()).apply { setAnchorView(previewVideo) })
            previewVideo.setOnPreparedListener { it.isLooping = true }
            previewVideo.start()
        } else {
            previewImage.visibility = View.VISIBLE
            previewImage.setImageURI(uri)
        }
    }

    private fun shareResult() {
        val uri = resultUri ?: return
        val mimeType = if (renderType == RenderType.VIDEO) "video/mp4" else "image/png"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.render_share)))
    }

    override fun onDestroyView() {
        if (::previewVideo.isInitialized) previewVideo.stopPlayback()
        super.onDestroyView()
    }

    companion object {
        const val ARG_RIDE_ID = "rideId"
        const val ARG_RENDER_TYPE = "renderType"
        const val ARG_RESULT_URI = "resultUri"

        fun args(rideId: Long, renderType: RenderType, resultUri: String? = null): Bundle = bundleOf(
            ARG_RIDE_ID to rideId,
            ARG_RENDER_TYPE to renderType.name,
            ARG_RESULT_URI to resultUri
        )
    }
}
