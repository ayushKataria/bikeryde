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
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.navigation.NavOptions
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.media.RenderNavigationTarget
import com.ayushkataria.bikeryde.media.RenderRepository
import com.ayushkataria.bikeryde.media.RenderType
import com.ayushkataria.bikeryde.media.VideoRenderWorker
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
    private lateinit var saveButton: MaterialButton
    private lateinit var shareButton: MaterialButton
    private lateinit var regenerateButton: View

    private lateinit var renderRepository: RenderRepository
    private var resultUri: Uri? = null
    private var rideId: Long = -1L
    private lateinit var renderType: RenderType

    /** Where to save a copy — the render itself only lives in private app storage until the user
     * picks a real destination here via the system file picker (Storage Access Framework). */
    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destinationUri ->
        if (destinationUri != null) saveResultTo(destinationUri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rideId = requireArguments().getLong(ARG_RIDE_ID)
        renderType = RenderType.valueOf(requireArguments().getString(ARG_RENDER_TYPE)!!)
        val initialUri = requireArguments().getString(ARG_RESULT_URI)
        val workId = requireArguments().getString(ARG_WORK_ID)

        view.findViewById<TextView>(R.id.topBarTitle).setText(
            if (renderType == RenderType.VIDEO) R.string.post_ride_create_animation else R.string.post_ride_create_image
        )
        view.findViewById<View>(R.id.topBarBack).setOnClickListener { findNavController().navigateUp() }

        previewImage = view.findViewById(R.id.previewImage)
        previewVideo = view.findViewById(R.id.previewVideo)
        loadingSpinner = view.findViewById(R.id.loadingSpinner)
        statusText = view.findViewById(R.id.statusText)
        saveButton = view.findViewById(R.id.saveButton)
        shareButton = view.findViewById(R.id.shareButton)
        regenerateButton = view.findViewById(R.id.regenerateButton)
        saveButton.setOnClickListener { launchSavePicker() }
        shareButton.setOnClickListener { shareResult() }
        regenerateButton.setOnClickListener { regenerate() }

        renderRepository = RenderRepository(requireContext())

        when {
            initialUri != null -> showResult(Uri.parse(initialUri))
            // A fresh generate/regenerate just enqueued this exact WorkRequest — follow it
            // directly rather than asking "what's the latest done render for this ride", which
            // can still answer with the *previous* render (old file, buttons enabled, no
            // progress) if this new work hasn't started running yet.
            workId != null -> followVideoWork(UUID.fromString(workId))
            else -> loadExistingOrFollowWork(rideId)
        }
    }

    private fun loadExistingOrFollowWork(rideId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            when (val target = renderRepository.resolveNavigationTarget(context, rideId, renderType)) {
                is RenderNavigationTarget.ShowResult -> showResult(target.fileUri)
                is RenderNavigationTarget.FollowInProgress -> followVideoWork(UUID.fromString(target.workId))
                RenderNavigationTarget.OpenCustomize -> {
                    statusText.visibility = View.VISIBLE
                    statusText.setText(R.string.render_not_available)
                    loadingSpinner.visibility = View.GONE
                }
            }
        }
    }

    private fun regenerate() {
        findNavController().navigate(
            R.id.renderEditFragment,
            RenderEditFragment.args(rideId, renderType),
            NavOptions.Builder().setPopUpTo(R.id.renderPreviewFragment, true).build()
        )
    }

    private fun followVideoWork(workId: UUID) {
        statusText.visibility = View.VISIBLE
        WorkManager.getInstance(requireContext()).getWorkInfoByIdLiveData(workId).observe(viewLifecycleOwner) { info ->
            if (info == null) return@observe
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
        saveButton.isEnabled = true
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

    private fun launchSavePicker() {
        val timestamp = System.currentTimeMillis()
        val filename = if (renderType == RenderType.VIDEO) "bikeryde_$timestamp.mp4" else "bikeryde_$timestamp.png"
        createDocumentLauncher.launch(filename)
    }

    private fun saveResultTo(destinationUri: Uri) {
        val sourceUri = resultUri ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val resolver = requireContext().contentResolver
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(sourceUri)?.use { input ->
                        resolver.openOutputStream(destinationUri)?.use { output -> input.copyTo(output) }
                    } ?: error("Unable to open source stream")
                }.isSuccess
            }
            Toast.makeText(
                requireContext(),
                if (success) R.string.render_saved else R.string.render_save_failed,
                Toast.LENGTH_SHORT
            ).show()
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
        const val ARG_WORK_ID = "workId"

        fun args(rideId: Long, renderType: RenderType, resultUri: String? = null, workId: String? = null): Bundle = bundleOf(
            ARG_RIDE_ID to rideId,
            ARG_RENDER_TYPE to renderType.name,
            ARG_RESULT_URI to resultUri,
            ARG_WORK_ID to workId
        )
    }
}
