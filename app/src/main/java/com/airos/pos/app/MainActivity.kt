package com.airos.pos.app

import android.content.Intent
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.airos.pos.core.ui.AirosPosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private const val MenuPushLogTag = "AIROS_MENU_PUSH"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val appContainer = (application as AirosPosApplication).appContainer
        setSunmiStickFullScreen(window)
        sendSunmiStatusBarBroadcast()

        lifecycleScope.launch {
            runCatching {
                appContainer.customerDisplayService.updateCustomerTotalDisplay(null)
            }
            val syncResult = appContainer.menuRepository.refresh()
            Log.d("AIROS", "[MainActivity] menuRepository.refresh() -> $syncResult")
        }

        startMenuPushListener(appContainer)

        setContent {
            AirosPosTheme {
                AirosPosStartupHost(appContainer = appContainer)
            }
        }
    }

    private fun startMenuPushListener(appContainer: AppContainer) {
        lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                val baseUrl = runCatching {
                    appContainer.terminalPreferencesStore.settings.first().edgeBaseUrl.trim().trimEnd('/')
                }.getOrDefault("")

                if (baseUrl.isBlank()) {
                    Log.w(MenuPushLogTag, "Listener skipped: backend base URL is empty")
                    kotlinx.coroutines.delay(5000)
                    continue
                }

                val eventUrl = "$baseUrl/api/menu/catalog/events"
                var connection: HttpURLConnection? = null

                try {
                    Log.d(MenuPushLogTag, "Connecting to $eventUrl")
                    connection = (URL(eventUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 0
                        doInput = true
                        useCaches = false
                        setRequestProperty("Accept", "text/event-stream")
                    }

                    val status = connection.responseCode
                    if (status !in 200..299) {
                        Log.w(MenuPushLogTag, "Listener HTTP $status from $eventUrl")
                        connection.disconnect()
                        kotlinx.coroutines.delay(3000)
                        continue
                    }

                    BufferedReader(
                        InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)
                    ).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (!line.startsWith("data:")) continue

                            val payload = line.removePrefix("data:").trim()
                            if (payload.isBlank()) continue

                            Log.d(MenuPushLogTag, "Event received: $payload")
                            val syncResult = appContainer.menuRepository.refresh()
                            Log.d(MenuPushLogTag, "Refresh after push -> $syncResult")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(MenuPushLogTag, "Listener error: ${t.javaClass.simpleName}: ${t.message}")
                    kotlinx.coroutines.delay(1500)
                } finally {
                    runCatching { connection?.disconnect() }
                }
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
