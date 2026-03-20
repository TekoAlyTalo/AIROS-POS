package com.airos.pos.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.airos.pos.core.ui.AirosPosTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSunmiStickFullScreen(window)
        sendSunmiStatusBarBroadcast()
        MainScope().launch {
            runCatching {
                (application as AirosPosApplication)
                    .appContainer
                    .customerDisplayService
                    .updateCustomerTotalDisplay(null)
            }
        }

        setContent {
            AirosPosTheme {
                AirosPosApp((application as AirosPosApplication).appContainer)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setSunmiStickFullScreen(window)
            sendSunmiStatusBarBroadcast()
        }
    }

    private fun sendSunmiStatusBarBroadcast() {
        runCatching {
            val intent = Intent("com.sunmi.ntp.SET_STATUSBAR")
            intent.putExtra("enabled", false)
            sendBroadcast(intent)
        }
    }
}

@Suppress("DEPRECATION")
private fun setSunmiStickFullScreen(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val attributes = window.attributes
        attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = attributes
    }

    val decorView = window.decorView
    val uiOptions = (
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    decorView.systemUiVisibility = uiOptions
}
