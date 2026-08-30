package com.ayushkataria.bikeryde

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import com.ayushkataria.bikeryde.media.RenderType
import com.ayushkataria.bikeryde.ui.render.RenderPreviewFragment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.nav_host_fragment)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Edge-to-edge draws behind the keyboard too, so the OS never shrinks this view for us
            // the way plain windowSoftInputMode="adjustResize" would in a non-edge-to-edge app —
            // without folding the IME inset in here ourselves, an on-screen text field near the
            // bottom of a scrolling screen (e.g. a stop's name field on the render customize
            // screen) stays covered by the keyboard instead of the ScrollView resizing to reveal it.
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, maxOf(systemBars.bottom, ime.bottom))
            insets
        }
        handleOpenRenderIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenRenderIntent(intent)
    }

    /** Deep-links in from the "your ride video is ready" notification (see [com.ayushkataria.bikeryde.media.RenderNotifications]). */
    private fun handleOpenRenderIntent(intent: Intent) {
        val rideId = intent.getLongExtra(EXTRA_OPEN_RENDER_RIDE_ID, -1L)
        if (rideId == -1L) return
        val renderType = intent.getStringExtra(EXTRA_OPEN_RENDER_TYPE)?.let(RenderType::valueOf) ?: return
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        navHostFragment?.navController?.navigate(
            R.id.renderPreviewFragment,
            RenderPreviewFragment.args(rideId, renderType)
        )
    }

    companion object {
        const val EXTRA_OPEN_RENDER_RIDE_ID = "openRenderRideId"
        const val EXTRA_OPEN_RENDER_TYPE = "openRenderType"
    }
}
