package com.airos.pos.app

import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airos.pos.core.datastore.TerminalPreferencesStore
import com.airos.pos.core.model.CameraConnectionState
import com.airos.pos.core.model.CameraPreviewRequest
import com.airos.pos.core.model.CameraPreviewState
import com.airos.pos.device.camera.WebRtcViewerPreviewService
import java.net.URI
import java.util.ArrayDeque
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

private const val TAG = "WebRtcSmokeTest"
private const val DEFAULT_CAMERA_ID = "cam1"
private const val SIGNALING_PORT = 8000
private const val MAX_LOG_LINES = 80

class WebRtcSmokeTestActivity : ComponentActivity() {
    private lateinit var terminalPreferencesStore: TerminalPreferencesStore
    private lateinit var cameraPreviewService: WebRtcViewerPreviewService
    private lateinit var cameraIdInput: EditText
    private lateinit var signalingUrlInput: EditText
    private lateinit var connectButton: Button
    private lateinit var stopButton: Button
    private lateinit var rendererContainer: FrameLayout
    private lateinit var rendererPlaceholder: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private val logLines = ArrayDeque<String>()
    private var renderer: SurfaceViewRenderer? = null
    private var lastPreviewLogSignature: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webrtc_smoke_test)

        terminalPreferencesStore = TerminalPreferencesStore(applicationContext)
        cameraPreviewService = WebRtcViewerPreviewService(applicationContext)

        cameraIdInput = findViewById(R.id.cameraIdInput)
        signalingUrlInput = findViewById(R.id.signalingUrlInput)
        connectButton = findViewById(R.id.connectButton)
        stopButton = findViewById(R.id.stopButton)
        rendererContainer = findViewById(R.id.rendererContainer)
        rendererPlaceholder = findViewById(R.id.rendererPlaceholder)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        cameraIdInput.setText(DEFAULT_CAMERA_ID)
        statusText.text = "Initializing smoke-test viewer..."

        appendLog("Smoke-test activity created")
        appendLog("Viewer service constructed")
        appendLog("Service EGL context=${cameraPreviewService.eglBaseContext?.javaClass?.name ?: "null"}")
        appendLog("Renderer attachment is deferred until the service reports remote-track-ready or LIVE")

        connectButton.setOnClickListener {
            releaseRendererIfPresent("Connect requested; clearing any existing renderer before new preview")
            startSmokeTestPreview()
        }
        stopButton.setOnClickListener {
            appendLog("Stop button pressed")
            lifecycleScope.launch {
                cameraPreviewService.stopPreview()
            }
        }

        lifecycleScope.launch {
            val settings = terminalPreferencesStore.settings.first()
            val signalingBaseUrl = settings.edgeBaseUrl.toSignalingBaseUrl()
            signalingUrlInput.setText(signalingBaseUrl)
            appendLog("Loaded signaling base URL from terminal settings: $signalingBaseUrl")
        }

        observePreviewState()
    }

    override fun onStop() {
        super.onStop()
        appendLog("Activity onStop -> stopping preview session")
        lifecycleScope.launch {
            cameraPreviewService.stopPreview()
        }
    }

    override fun onDestroy() {
        appendLog("Activity onDestroy")
        releaseRendererIfPresent("Activity destroy")
        super.onDestroy()
    }

    private fun observePreviewState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                cameraPreviewService.previewState.collect { state ->
                    statusText.text = buildStatusText(state)
                    val shouldAttachRenderer = isRendererSafeState(state)
                    if (shouldAttachRenderer) {
                        ensureRendererAttached(
                            reason = "Preview reached renderer-safe state ${state.connectionState.name} detail=${state.detailMessage}",
                        )
                    } else if (renderer != null) {
                        releaseRendererIfPresent(
                            "Preview left safe state ${state.connectionState.name}",
                        )
                    }

                    rendererPlaceholder.visibility = if (renderer != null && state.isStreaming) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }

                    val signature = listOf(
                        state.connectionState.name,
                        state.detailMessage,
                        state.errorMessage.orEmpty(),
                        state.isStreaming.toString(),
                        state.cameraId.orEmpty(),
                        state.signalingUrl.orEmpty(),
                    ).joinToString("|")
                    if (signature != lastPreviewLogSignature) {
                        lastPreviewLogSignature = signature
                        appendLog(
                            "Preview state=${state.connectionState.name} streaming=${state.isStreaming} detail=${state.detailMessage} error=${state.errorMessage ?: "-"}",
                        )
                    }
                }
            }
        }
    }

    private fun startSmokeTestPreview() {
        val cameraId = cameraIdInput.text.toString().trim()
        val signalingBaseUrl = signalingUrlInput.text.toString().trim()
        if (cameraId.isBlank()) {
            appendLog("Connect blocked: cameraId is blank")
            statusText.text = "Camera ID is required."
            return
        }
        if (signalingBaseUrl.isBlank()) {
            appendLog("Connect blocked: signaling base URL is blank")
            statusText.text = "Signaling Base URL is required."
            return
        }

        appendLog("Connect button pressed cameraId=$cameraId signalingBaseUrl=$signalingBaseUrl")
        appendLog("Renderer will not be attached until the service reports remote-track-ready or LIVE")
        lifecycleScope.launch {
            cameraPreviewService.startPreview(
                CameraPreviewRequest(
                    cameraId = cameraId,
                    signalingBaseUrl = signalingBaseUrl,
                    sourceLabel = "Smoke test $cameraId",
                ),
            )
        }
    }

    private fun ensureRendererAttached(reason: String) {
        if (renderer != null) {
            return
        }
        val sharedContext = cameraPreviewService.eglBaseContext
        if (sharedContext == null) {
            appendLog("Renderer init skipped because shared EGL context is null")
            return
        }

        val currentRenderer = SurfaceViewRenderer(this).apply {
            setMirror(false)
            setEnableHardwareScaler(true)
        }

        try {
            appendLog(
                "Initializing renderer ${currentRenderer.logLabel()} sharedContext=${sharedContext.javaClass.name} reason=$reason",
            )
            currentRenderer.init(sharedContext, null)
            rendererContainer.removeAllViews()
            rendererContainer.addView(
                currentRenderer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            renderer = currentRenderer
            appendLog("Renderer attached to container ${currentRenderer.logLabel()}")
            cameraPreviewService.attachVideoSink(currentRenderer)
            appendLog("Renderer attached as video sink ${currentRenderer.logLabel()}")
        } catch (error: RuntimeException) {
            appendLog("Renderer init failed: ${error.message ?: error::class.java.simpleName}")
            try {
                currentRenderer.release()
            } catch (_: RuntimeException) {
                Unit
            }
        }
    }

    private fun releaseRendererIfPresent(reason: String) {
        val currentRenderer = renderer ?: return
        appendLog("Releasing renderer ${currentRenderer.logLabel()} reason=$reason")
        cameraPreviewService.detachVideoSink(currentRenderer)
        try {
            currentRenderer.release()
        } catch (_: RuntimeException) {
            Unit
        }
        renderer = null
        rendererContainer.removeAllViews()
        rendererPlaceholder.visibility = View.VISIBLE
    }

    private fun isRendererSafeState(state: CameraPreviewState): Boolean {
        return state.isStreaming ||
            state.connectionState == CameraConnectionState.LIVE ||
            (
                state.connectionState == CameraConnectionState.WAITING_FOR_VIDEO &&
                    state.detailMessage == WebRtcViewerPreviewService.DETAIL_REMOTE_TRACK_BOUND
                )
    }

    private fun buildStatusText(state: CameraPreviewState): String {
        val lines = buildList {
            add("Connection: ${state.connectionState.name}")
            add("Streaming: ${state.isStreaming}")
            add("Camera ID: ${state.cameraId ?: "-"}")
            add("Detail: ${state.detailMessage}")
            add("Error: ${state.errorMessage ?: "-"}")
            add("Signaling URL: ${state.signalingUrl ?: "-"}")
            add("Last frame: ${state.lastFrameAtEpochMillis?.toString() ?: "-"}")
        }
        return lines.joinToString(separator = "\n")
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        val timestamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString()
        logLines += "$timestamp  $message"
        while (logLines.size > MAX_LOG_LINES) {
            logLines.removeFirst()
        }
        logText.text = logLines.joinToString(separator = "\n")
    }

    private fun String.toSignalingBaseUrl(): String {
        val normalized = trim()
        if (normalized.isBlank()) {
            return normalized
        }

        val candidate = if (
            normalized.startsWith("http://") ||
            normalized.startsWith("https://") ||
            normalized.startsWith("ws://") ||
            normalized.startsWith("wss://")
        ) {
            normalized
        } else {
            "http://$normalized"
        }

        return try {
            val uri = URI(candidate)
            val host = uri.host ?: return candidate
            val scheme = when (uri.scheme?.lowercase()) {
                "ws" -> "http"
                "wss" -> "https"
                else -> uri.scheme ?: "http"
            }
            URI(
                scheme,
                uri.userInfo,
                host,
                SIGNALING_PORT,
                null,
                null,
                null,
            ).toString()
        } catch (_: Exception) {
            candidate
        }
    }

    private fun SurfaceViewRenderer.logLabel(): String {
        return "SurfaceViewRenderer@${Integer.toHexString(hashCode())}"
    }
}
