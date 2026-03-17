package com.airos.pos.device.camera

import android.content.Context
import com.airos.pos.core.model.CameraPreviewRequest
import com.airos.pos.core.model.CameraPreviewState
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.EglBase
import org.webrtc.VideoSink

interface CameraPreviewService {
    val previewState: StateFlow<CameraPreviewState>
    val eglBaseContext: EglBase.Context?

    suspend fun startPreview()
    suspend fun startPreview(request: CameraPreviewRequest)
    suspend fun stopPreview()
    fun attachVideoSink(sink: VideoSink)
    fun detachVideoSink(sink: VideoSink)
}

/**
 * Production-facing camera preview service for the Android POS app.
 *
 * Historical note:
 * - This class originally returned a TODO placeholder for native vendor camera preview.
 * - The table-map live preview feature already expects a CameraPreviewService and a WebRTC-backed
 *   renderer path exists and is confirmed working in WebRtcSmokeTestActivity.
 *
 * Current policy:
 * - Keep the public type stable (SunmiCameraPreviewService) so existing DI/container wiring
 *   does not have to change.
 * - Internally delegate to the proven WebRTC viewer implementation until or unless a true vendor
 *   camera SDK integration is introduced later behind the same contract.
 */
class SunmiCameraPreviewService(
    context: Context,
) : CameraPreviewService {
    private val delegate = WebRtcViewerPreviewService(context.applicationContext)

    override val previewState: StateFlow<CameraPreviewState>
        get() = delegate.previewState

    override val eglBaseContext: EglBase.Context?
        get() = delegate.eglBaseContext

    override suspend fun startPreview() {
        delegate.startPreview()
    }

    override suspend fun startPreview(request: CameraPreviewRequest) {
        delegate.startPreview(request)
    }

    override suspend fun stopPreview() {
        delegate.stopPreview()
    }

    override fun attachVideoSink(sink: VideoSink) {
        delegate.attachVideoSink(sink)
    }

    override fun detachVideoSink(sink: VideoSink) {
        delegate.detachVideoSink(sink)
    }
}
