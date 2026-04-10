package com.airos.pos.app

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
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
private const val NfcLogTag = "AIROS_NFC"

class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private val appContainer: AppContainer
        get() = (application as AirosPosApplication).appContainer
    private val nfcStaffResolver: NfcStaffResolver by lazy { appContainer.nfcStaffResolver }
    private val nfcIdentityRepository by lazy { appContainer.nfcIdentityRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setSunmiStickFullScreen(window)
        sendSunmiStatusBarBroadcast()

        probeNfcAdapter()

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

    override fun onResume() {
        super.onResume()
        // Re-probe: the user may have toggled NFC in system settings and returned.
        probeNfcAdapter()
        enableNfcReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableNfcReaderMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setSunmiStickFullScreen(window)
            sendSunmiStatusBarBroadcast()
        }
    }

    // ── NFC probe ──────────────────────────────────────────────────────────────

    /**
     * Checks NFC adapter presence and enabled state, updates [NfcProbe], logs result.
     * Idempotent — safe to call on every resume.
     */
    private fun probeNfcAdapter() {
        val manager = getSystemService(NfcManager::class.java)
        val adapter = manager?.defaultAdapter
        nfcAdapter = adapter

        val present = adapter != null
        val enabled = adapter?.isEnabled == true
        NfcProbe.updateAdapterStatus(adapterPresent = present, enabled = enabled)

        when {
            !present -> Log.d(NfcLogTag, "Probe: no NFC adapter on this device")
            !enabled -> Log.d(NfcLogTag, "Probe: adapter present but NFC is disabled in system settings")
            else     -> Log.d(NfcLogTag, "Probe: adapter present and enabled")
        }
    }

    /**
     * Activates NFC reader mode for all common tag technologies.
     *
     * Reader mode delivers tags to [onNfcTagDiscovered] while the app is in the
     * foreground, without requiring intent filters or foreground dispatch setup.
     * [FLAG_READER_SKIP_NDEF_CHECK] speeds up tag detection by skipping NDEF parsing.
     */
    private fun enableNfcReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            Log.d(NfcLogTag, "Reader mode NOT started — NFC is disabled")
            return
        }
        val flags =
            NfcAdapter.FLAG_READER_NFC_A or   // ISO 14443-A (Mifare, most cards)
            NfcAdapter.FLAG_READER_NFC_B or   // ISO 14443-B
            NfcAdapter.FLAG_READER_NFC_F or   // FeliCa
            NfcAdapter.FLAG_READER_NFC_V or   // ISO 15693
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(this, ::onNfcTagDiscovered, flags, null)
        Log.d(NfcLogTag, "Reader mode enabled (flags=0x${flags.toString(16)})")
    }

    private fun disableNfcReaderMode() {
        runCatching { nfcAdapter?.disableReaderMode(this) }
        Log.d(NfcLogTag, "Reader mode disabled")
    }

    /**
     * Called on the NFC binder thread when a tag enters the field.
     * Extracts UID (uppercase colon-separated hex) and tech list, publishes to
     * [NfcProbe], then resolves the UID to a staff member via [nfcStaffResolver].
     *
     * UID canonical form: uppercase hex bytes joined by ":", e.g. "08:7D:F6:83".
     * This form is used consistently in [NfcStaffResolver] mappings.
     */
    private fun onNfcTagDiscovered(tag: Tag) {
        val uid = tag.id.joinToString(":") { "%02X".format(it) }
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val detectedAt = System.currentTimeMillis()
        Log.d(NfcLogTag, "Tag detected | uid=$uid | techs=${techs.joinToString()}")
        NfcProbe.onTagDetected(
            NfcTagEvent(
                uid = uid,
                techList = techs,
                action = "TAG_DISCOVERED",
                timestamp = detectedAt,
            )
        )
        lifecycleScope.launch(Dispatchers.IO) {
            val resolution = runCatching {
                nfcStaffResolver.resolve(uid)?.let { NfcStaffResolution.Matched(it) }
                    ?: run {
                        nfcIdentityRepository.recordUnknownTag(uid)
                        NfcStaffResolution.Unknown(uid, detectedAt)
                    }
            }.getOrElse { error ->
                Log.e(NfcLogTag, "NFC resolve failed | uid=$uid reason=${error.message}", error)
                nfcIdentityRepository.recordUnknownTag(uid)
                NfcStaffResolution.Unknown(uid, detectedAt)
            }

            NfcProbe.onTagResolved(resolution)
            when (resolution) {
                is NfcStaffResolution.Matched ->
                    Log.d(
                        NfcLogTag,
                        "Staff match | uid=$uid staffId=${resolution.match.staffId} name=${resolution.match.displayName}",
                    )

                is NfcStaffResolution.Unknown ->
                    Log.d(NfcLogTag, "Unknown NFC tag | uid=$uid — not enrolled")
            }
        }
    }

    // ── Menu push listener ─────────────────────────────────────────────────────

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
