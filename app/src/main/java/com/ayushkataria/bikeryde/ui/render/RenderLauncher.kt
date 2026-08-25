package com.ayushkataria.bikeryde.ui.render

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ayushkataria.bikeryde.R
import com.ayushkataria.bikeryde.media.RenderNavigationTarget
import com.ayushkataria.bikeryde.media.RenderRepository
import com.ayushkataria.bikeryde.media.RenderType
import kotlinx.coroutines.launch

/**
 * Opens the render flow for a ride's static image or animation — shared by every screen with
 * "Create Static Image"/"Create Animation" buttons (the live tracking screen, ride history's
 * detail screen). Routes through [RenderRepository.resolveNavigationTarget] so it never opens the
 * customize screen when there's already a finished render to show, or a video still rendering to
 * reattach to (opening customize again there would let the user kick off a second, conflicting
 * render of the same ride).
 */
object RenderLauncher {
    fun open(fragment: Fragment, rideId: Long, renderType: RenderType) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val context = fragment.requireContext()
            val target = RenderRepository(context).resolveNavigationTarget(context, rideId, renderType)
            val navController = fragment.findNavController()
            when (target) {
                is RenderNavigationTarget.ShowResult -> navController.navigate(
                    R.id.renderPreviewFragment,
                    RenderPreviewFragment.args(rideId, renderType, resultUri = target.fileUri.toString())
                )
                is RenderNavigationTarget.FollowInProgress -> navController.navigate(
                    R.id.renderPreviewFragment,
                    RenderPreviewFragment.args(rideId, renderType, workId = target.workId)
                )
                RenderNavigationTarget.OpenCustomize -> navController.navigate(
                    R.id.renderEditFragment,
                    RenderEditFragment.args(rideId, renderType)
                )
            }
        }
    }
}
