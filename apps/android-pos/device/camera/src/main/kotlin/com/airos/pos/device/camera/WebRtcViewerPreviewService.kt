package com.airos.pos.device.camera

import android.content.Context
import android.util.Log
import com.airos.pos.core.model.CameraConnectionState
import com.airos.pos.core.model.CameraPreviewRequest
import com.airos.pos.core.model.CameraPreviewState
import com.airos.pos.core.network.NetworkFoundation
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

private const val TAG = "WebRtcViewerSvc"
private const val PREVIEW_PATH = "/ws/webrtc"
private const val FRAME_STATE_PUBLISH_INTERVAL_MILLIS = 250L

private open class ViewerSdpObserver : SdpObserver {
    override fun onCreateSuccess(sessionDescription: SessionDescription?) = Unit

    override fun onSetSuccess() = Unit

    override fun onCreateFailure(error: String?) = Unit

    override fun onSetFailure(error: String?) = Unit
}

class WebRtcViewerPreviewService(
    context: Context,
) : CameraPreviewService {
    companion object {
        const val DETAIL_ANSWER_APPLIED_WAITING_FOR_TRACK = "Answer applied; waiting for remote track"
        const val DETAIL_REMOTE_TRACK_BOUND = "Remote video track bound; waiting for frames"
    }

    private val appContext = context.applicationContext
    private val trackLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val websocketClient: OkHttpClient = NetworkFoundation.createHttpClient()
        .newBuilder()
        .readTimeout(0, MILLISECONDS)
        .build()
    private val previewStateFlow = MutableStateFlow(CameraPreviewState())
    private val attachedSinks = CopyOnWriteArraySet<VideoSink>()

    private var eglBase: EglBase? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var initError: String? = null
    private var activeSession: PreviewSession? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteVideoTrackId: String? = null
    private val lastFrameStatePublishedAtEpochMillis = AtomicLong(0L)

    private val frameObserver = VideoSink { frame: VideoFrame? ->
        if (frame == null) {
            return@VideoSink
        }
        frame.retain()
        try {
            publishFrameArrivalStateIfDue(frame, System.currentTimeMillis())
        } finally {
            frame.release()
        }
    }

    override val previewState: StateFlow<CameraPreviewState> = previewStateFlow.asStateFlow()

    override val eglBaseContext: EglBase.Context?
        get() = eglBase?.eglBaseContext

    init {
        initializePeerConnectionFactory()
    }

    override suspend fun startPreview() {
        Log.w(TAG, "startPreview called without CameraPreviewRequest")
        previewStateFlow.value = previewStateFlow.value.copy(
            connectionState = CameraConnectionState.ERROR,
            detailMessage = "Preview request missing",
            errorMessage = "Viewer preview needs a cameraId and a backend base URL.",
            videoWidth = null,
            videoHeight = null,
            videoRotationDeg = null,
        )
    }

    override suspend fun startPreview(request: CameraPreviewRequest) {
        Log.i(
            TAG,
            "startPreview cameraId=${request.cameraId} tableId=${request.tableId} source=${request.sourceLabel}",
        )
        val startupError = initError
        if (startupError != null) {
            Log.e(TAG, "startPreview blocked initError=$startupError")
            publishState(
                request = request,
                connectionState = CameraConnectionState.ERROR,
                detailMessage = "Viewer unavailable",
                errorMessage = startupError,
                isStreaming = false,
                signalingUrl = null,
                lastFrameAtEpochMillis = null,
            )
            return
        }
        sessionMutex.withLock {
            closeActiveSessionLocked(resetState = false)
            openSessionLocked(request)
        }
    }

    override suspend fun stopPreview() {
        Log.i(TAG, "stopPreview requested")
        sessionMutex.withLock {
            closeActiveSessionLocked(resetState = true)
        }
    }

    override fun attachVideoSink(sink: VideoSink) {
        attachedSinks += sink
        Log.i(
            TAG,
            "attachVideoSink sink=${sink.logLabel()} count=${attachedSinks.size} remoteTrack=${remoteVideoTrack != null}",
        )
        synchronized(trackLock) {
            remoteVideoTrack?.addSink(sink)
        }
    }

    override fun detachVideoSink(sink: VideoSink) {
        attachedSinks -= sink
        Log.i(
            TAG,
            "detachVideoSink sink=${sink.logLabel()} count=${attachedSinks.size} remoteTrack=${remoteVideoTrack != null}",
        )
        synchronized(trackLock) {
            remoteVideoTrack?.removeSink(sink)
        }
    }

    private fun initializePeerConnectionFactory() {
        var createdEglBase: EglBase? = null
        var createdAudioModule: JavaAudioDeviceModule? = null
        try {
            Log.i(TAG, "Initializing viewer PeerConnectionFactory")
            createdEglBase = EglBase.create()
            Log.i(
                TAG,
                "EglBase.create success eglBaseClass=${createdEglBase.javaClass.name} eglBaseContext=${createdEglBase.eglBaseContext?.javaClass?.name ?: "null"}",
            )

            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(appContext)
                    .createInitializationOptions(),
            )
            Log.i(TAG, "PeerConnectionFactory.initialize success")

            createdAudioModule = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()
            Log.i(TAG, "JavaAudioDeviceModule created for viewer factory")

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(createdAudioModule)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(createdEglBase.eglBaseContext))
                .createPeerConnectionFactory()

            eglBase = createdEglBase
            audioDeviceModule = createdAudioModule
            Log.i(
                TAG,
                "Viewer PeerConnectionFactory ready eglBaseContext=${eglBaseContext?.javaClass?.name ?: "null"}",
            )
        } catch (error: Throwable) {
            initError = "${error::class.java.simpleName}: ${error.message ?: "Unknown error"}"
            Log.e(TAG, "Viewer PeerConnectionFactory init failed error=$initError", error)
            try {
                createdAudioModule?.release()
            } catch (_: Throwable) {
                Unit
            }
            try {
                createdEglBase?.release()
            } catch (_: Throwable) {
                Unit
            }
            previewStateFlow.value = previewStateFlow.value.copy(
                isStreaming = false,
                connectionState = CameraConnectionState.ERROR,
                detailMessage = "Viewer unavailable",
                errorMessage = initError,
                videoWidth = null,
                videoHeight = null,
                videoRotationDeg = null,
            )
        }
    }

    private fun openSessionLocked(request: CameraPreviewRequest) {
        val wsUrl = try {
            resolveWebSocketUrl(request.signalingBaseUrl)
        } catch (error: IllegalArgumentException) {
            Log.e(
                TAG,
                "Invalid signaling base url cameraId=${request.cameraId} base=${request.signalingBaseUrl}",
                error,
            )
            publishState(
                request = request,
                connectionState = CameraConnectionState.ERROR,
                detailMessage = "Invalid signaling URL",
                errorMessage = error.message,
                isStreaming = false,
                signalingUrl = null,
                lastFrameAtEpochMillis = null,
            )
            return
        }

        val session = PreviewSession(
            request = request,
            wsUrl = wsUrl,
            pcId = UUID.randomUUID().toString(),
        )
        activeSession = session

        publishState(
            request = request,
            connectionState = CameraConnectionState.CONNECTING,
            detailMessage = "Connecting viewer signaling session",
            errorMessage = null,
            isStreaming = false,
            signalingUrl = wsUrl,
            lastFrameAtEpochMillis = null,
        )
        Log.i(
            TAG,
            "Opening viewer session cameraId=${request.cameraId} tableId=${request.tableId} wsUrl=$wsUrl pcId=${session.pcId}",
        )
        Log.i(
            TAG,
            "Viewer signaling direction: Android POS viewer initiates the offer because this repo's only confirmed signaling contract is the parked Android implementation that sends offer -> answer over /ws/webrtc",
        )

        val peerConnection = createPeerConnection(session) ?: run {
            activeSession = null
            return
        }
        session.peerConnection = peerConnection

        val webSocketRequest = Request.Builder()
            .url(wsUrl)
            .build()
        session.webSocket = websocketClient.newWebSocket(webSocketRequest, createSignalingListener(session))
    }

    private fun createPeerConnection(session: PreviewSession): PeerConnection? {
        val factory = peerConnectionFactory
        if (factory == null) {
            Log.e(TAG, "PeerConnectionFactory unavailable for cameraId=${session.request.cameraId}")
            failSessionAsync(session, "Viewer factory missing")
            return null
        }

        return try {
            Log.i(TAG, "Creating PeerConnection cameraId=${session.request.cameraId}")
            val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            factory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                        Log.i(
                            TAG,
                            "onSignalingChange cameraId=${session.request.cameraId} state=${newState?.name ?: "null"}",
                        )
                    }

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                        Log.i(
                            TAG,
                            "onIceConnectionChange cameraId=${session.request.cameraId} state=${newState?.name ?: "null"}",
                        )
                        when (newState) {
                            PeerConnection.IceConnectionState.DISCONNECTED,
                            PeerConnection.IceConnectionState.FAILED,
                            -> failSessionAsync(session, "ICE connection ${newState?.name ?: "unknown"}")

                            else -> Unit
                        }
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {
                        Log.i(
                            TAG,
                            "onIceConnectionReceivingChange cameraId=${session.request.cameraId} receiving=$receiving",
                        )
                    }

                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                        Log.i(
                            TAG,
                            "onIceGatheringChange cameraId=${session.request.cameraId} state=${newState?.name ?: "null"}",
                        )
                    }

                    override fun onIceCandidate(candidate: IceCandidate?) {
                        val webSocket = session.webSocket
                        if (candidate == null || !isActiveSession(session) || webSocket == null) {
                            return
                        }
                        Log.i(
                            TAG,
                            "onIceCandidate cameraId=${session.request.cameraId} sdpMid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex}",
                        )
                        val candidateJson = JSONObject()
                            .put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        val payload = JSONObject()
                            .put("type", "ice")
                            .put("cameraId", session.request.cameraId)
                            .put("pcId", session.pcId)
                            .put("candidate", candidateJson)
                        webSocket.send(payload.toString())
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

                    override fun onAddStream(stream: org.webrtc.MediaStream?) {
                        Log.i(TAG, "onAddStream cameraId=${session.request.cameraId} stream=${stream != null}")
                    }

                    override fun onRemoveStream(stream: org.webrtc.MediaStream?) {
                        Log.i(TAG, "onRemoveStream cameraId=${session.request.cameraId} stream=${stream != null}")
                    }

                    override fun onDataChannel(channel: org.webrtc.DataChannel?) {
                        Log.i(TAG, "onDataChannel cameraId=${session.request.cameraId} channel=${channel != null}")
                    }

                    override fun onRenegotiationNeeded() {
                        Log.i(TAG, "onRenegotiationNeeded cameraId=${session.request.cameraId}")
                    }

                    override fun onAddTrack(
                        receiver: RtpReceiver?,
                        mediaStreams: Array<out org.webrtc.MediaStream>?,
                    ) {
                        val track = receiver?.track()
                        Log.i(
                            TAG,
                            "onAddTrack cameraId=${session.request.cameraId} track=${track?.kind()} mediaStreams=${mediaStreams?.size ?: 0}",
                        )
                        if (track is VideoTrack) {
                            bindRemoteTrack(track, session, source = "onAddTrack")
                        }
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {
                        val track = transceiver?.receiver?.track()
                        Log.i(
                            TAG,
                            "onTrack cameraId=${session.request.cameraId} direction=${transceiver?.direction?.name ?: "null"} track=${track?.kind()}",
                        )
                        if (track is VideoTrack) {
                            bindRemoteTrack(track, session, source = "onTrack")
                        }
                    }

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        Log.i(
                            TAG,
                            "onConnectionChange cameraId=${session.request.cameraId} state=${newState?.name ?: "null"}",
                        )
                    }

                    override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) {
                        Log.i(
                            TAG,
                            "onSelectedCandidatePairChanged cameraId=${session.request.cameraId} event=${event != null}",
                        )
                    }

                    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                        Log.i(
                            TAG,
                            "onStandardizedIceConnectionChange cameraId=${session.request.cameraId} state=${newState?.name ?: "null"}",
                        )
                    }
                },
            )?.also { peerConnection ->
                val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)
                Log.i(
                    TAG,
                    "Added RECV_ONLY video transceiver for viewer cameraId=${session.request.cameraId}",
                )
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to create PeerConnection cameraId=${session.request.cameraId}", error)
            failSessionAsync(session, "${error::class.java.simpleName}: ${error.message ?: "PeerConnection create failed"}")
            null
        }
    }

    private fun createSignalingListener(session: PreviewSession): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isActiveSession(session)) {
                    Log.i(
                        TAG,
                        "Ignoring onOpen for stale viewer session cameraId=${session.request.cameraId}",
                    )
                    webSocket.close(1000, "Stale viewer session")
                    return
                }
                Log.i(
                    TAG,
                    "WebSocket onOpen cameraId=${session.request.cameraId} wsUrl=${session.wsUrl}",
                )
                publishState(
                    request = session.request,
                    connectionState = CameraConnectionState.CONNECTING,
                    detailMessage = "Signaling connected; sending start",
                    errorMessage = null,
                    isStreaming = false,
                    signalingUrl = session.wsUrl,
                    lastFrameAtEpochMillis = null,
                )
                sendStart(session)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isActiveSession(session)) {
                    Log.i(
                        TAG,
                        "Ignoring onMessage for stale viewer session cameraId=${session.request.cameraId}",
                    )
                    return
                }
                Log.i(
                    TAG,
                    "WebSocket onMessage cameraId=${session.request.cameraId} bytes=${text.length}",
                )
                handleSignalingMessage(session, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(
                    TAG,
                    "WebSocket onFailure cameraId=${session.request.cameraId} reason=${t.message}",
                    t,
                )
                failSessionAsync(session, "Signaling failure: ${t.message ?: "Unknown error"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(
                    TAG,
                    "WebSocket onClosed cameraId=${session.request.cameraId} code=$code reason=$reason manualStop=${session.manualStop}",
                )
                if (!session.manualStop) {
                    failSessionAsync(session, "Signaling closed: $reason")
                }
            }
        }
    }

    private fun sendStart(session: PreviewSession) {
        if (!isActiveSession(session)) {
            return
        }
        val payload = JSONObject()
            .put("type", "start")
            .put("cameraId", session.request.cameraId)
            .put("pcId", session.pcId)
        Log.i(TAG, "Sending viewer start cameraId=${session.request.cameraId}")
        session.webSocket?.send(payload.toString())
        publishState(
            request = session.request,
            connectionState = CameraConnectionState.CONNECTING,
            detailMessage = "Viewer start sent; waiting for ready",
            errorMessage = null,
            isStreaming = false,
            signalingUrl = session.wsUrl,
            lastFrameAtEpochMillis = null,
        )
    }

    private fun handleReady(
        session: PreviewSession,
        payload: JSONObject,
    ) {
        if (!isActiveSession(session)) {
            return
        }
        synchronized(session) {
            if (session.signalingReadyReceived) {
                Log.i(TAG, "Ignoring duplicate ready cameraId=${session.request.cameraId}")
                return
            }
            session.signalingReadyReceived = true
        }
        Log.i(
            TAG,
            "Received ready cameraId=${session.request.cameraId} payload=${payload.toString()}",
        )
        publishState(
            request = session.request,
            connectionState = CameraConnectionState.CONNECTING,
            detailMessage = "Viewer ready received; creating offer",
            errorMessage = null,
            isStreaming = false,
            signalingUrl = session.wsUrl,
            lastFrameAtEpochMillis = null,
        )
        createAndSendOffer(session)
    }

    private fun createAndSendOffer(session: PreviewSession) {
        if (!isActiveSession(session)) {
            return
        }
        val peerConnection = session.peerConnection ?: run {
            failSessionAsync(session, "Missing PeerConnection before offer")
            return
        }
        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
            mandatory += MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false")
        }
        Log.i(TAG, "Creating viewer offer cameraId=${session.request.cameraId}")
        peerConnection.createOffer(
            object : ViewerSdpObserver() {
                override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                    if (!isActiveSession(session)) {
                        Log.i(
                            TAG,
                            "Ignoring created offer for stale viewer session cameraId=${session.request.cameraId}",
                        )
                        return
                    }
                    if (sessionDescription == null) {
                        failSessionAsync(session, "Empty offer SDP")
                        return
                    }
                    Log.i(
                        TAG,
                        "Offer created cameraId=${session.request.cameraId} sdpLength=${sessionDescription.description.length}",
                    )
                    peerConnection.setLocalDescription(
                        object : ViewerSdpObserver() {
                            override fun onSetSuccess() {
                                if (!isActiveSession(session)) {
                                    Log.i(
                                        TAG,
                                        "Ignoring setLocalDescription success for stale viewer session cameraId=${session.request.cameraId}",
                                    )
                                    return
                                }
                                synchronized(session) {
                                    session.localDescriptionApplied = true
                                }
                                Log.i(TAG, "Local viewer offer applied cameraId=${session.request.cameraId}")
                                val payload = JSONObject()
                                    .put("type", "offer")
                                    .put("cameraId", session.request.cameraId)
                                    .put("pcId", session.pcId)
                                    .put("sdp", sessionDescription.description)
                                Log.i(TAG, "Sending viewer offer cameraId=${session.request.cameraId}")
                                session.webSocket?.send(payload.toString())
                                publishState(
                                    request = session.request,
                                    connectionState = CameraConnectionState.CONNECTING,
                                    detailMessage = "Viewer offer sent; waiting for answer",
                                    errorMessage = null,
                                    isStreaming = false,
                                    signalingUrl = session.wsUrl,
                                    lastFrameAtEpochMillis = null,
                                )
                            }

                            override fun onSetFailure(error: String?) {
                                failSessionAsync(session, error ?: "Failed to apply local offer")
                            }
                        },
                        sessionDescription,
                    )
                }

                override fun onCreateFailure(error: String?) {
                    failSessionAsync(session, error ?: "Failed to create offer")
                }
            },
            constraints,
        )
    }

    private fun handleSignalingMessage(
        session: PreviewSession,
        text: String,
    ) {
        val json = try {
            JSONObject(text)
        } catch (error: JSONException) {
            Log.e(TAG, "Invalid signaling JSON cameraId=${session.request.cameraId}", error)
            failSessionAsync(session, "Invalid signaling JSON")
            return
        }

        when (json.optString("type")) {
            "ready" -> handleReady(session, json)
            "answer" -> handleAnswer(session, json)
            "ice" -> handleRemoteIceCandidate(session, json.optJSONObject("candidate"))
            "error" -> handleSignalingError(session, json)
            "offer" -> {
                Log.e(
                    TAG,
                    "Unexpected relay offer cameraId=${session.request.cameraId}; current repo contract is viewer-initiated offerer",
                )
                failSessionAsync(session, "Unexpected relay-initiated offer")
            }

            else -> {
                Log.w(
                    TAG,
                    "Ignoring unknown signaling type cameraId=${session.request.cameraId} type=${json.optString("type")}",
                )
            }
        }
    }

    private fun handleSignalingError(
        session: PreviewSession,
        payload: JSONObject,
    ) {
        if (!isActiveSession(session)) {
            return
        }
        val payloadCameraId = payload.optString("cameraId").ifBlank { session.request.cameraId }
        val code = payload.optString("code").takeIf { it.isNotBlank() }
        val reason = payload.optString("reason").takeIf { it.isNotBlank() }
        val message = payload.optString("message").takeIf { it.isNotBlank() }
            ?: "Unknown signaling error"
        Log.e(
            TAG,
            "Received signaling error sessionCameraId=${session.request.cameraId} payloadCameraId=$payloadCameraId " +
                "code=${code ?: "null"} reason=${reason ?: "null"} message=$message payload=${payload}",
        )
        val composedReason = buildString {
            append(message)
            if (!reason.isNullOrBlank()) {
                append(" (reason=")
                append(reason)
                append(')')
            }
            if (!code.isNullOrBlank()) {
                append(" [code=")
                append(code)
                append(']')
            }
            if (payloadCameraId != session.request.cameraId) {
                append(" [payloadCameraId=")
                append(payloadCameraId)
                append(']')
            }
        }
        failSessionAsync(session, composedReason)
    }

    private fun handleAnswer(
        session: PreviewSession,
        payload: JSONObject,
    ) {
        if (!isActiveSession(session)) {
            return
        }
        val peerConnection = session.peerConnection ?: return

        val nestedSdp = payload.optJSONObject("sdp")
        val sdpType = nestedSdp?.optString("type").orEmpty().ifBlank { payload.optString("sdpType") }
        val sdp = nestedSdp?.optString("sdp").orEmpty().ifBlank { payload.optString("sdp") }

        if (sdp.isBlank()) {
            Log.e(TAG, "Answer payload missing SDP cameraId=${session.request.cameraId} payload=$payload")
            failSessionAsync(session, "Empty answer SDP")
            return
        }

        val localApplied = synchronized(session) { session.localDescriptionApplied }
        if (!localApplied) {
            failSessionAsync(session, "Answer arrived before local description was applied")
            return
        }

        val answerType = when (sdpType.lowercase()) {
            "", "answer" -> SessionDescription.Type.ANSWER
            else -> {
                Log.w(
                    TAG,
                    "Unexpected answer SDP type cameraId=${session.request.cameraId} sdpType=$sdpType; forcing ANSWER",
                )
                SessionDescription.Type.ANSWER
            }
        }

        Log.i(
            TAG,
            "Applying remote answer cameraId=${session.request.cameraId} sdpType=${answerType.canonicalForm()} sdpLength=${sdp.length}",
        )
        peerConnection.setRemoteDescription(
            object : ViewerSdpObserver() {
                override fun onSetSuccess() {
                    if (!isActiveSession(session)) {
                        Log.i(
                            TAG,
                            "Ignoring remote answer success for stale viewer session cameraId=${session.request.cameraId}",
                        )
                        return
                    }
                    synchronized(session) {
                        session.remoteDescriptionApplied = true
                    }
                    Log.i(TAG, "Remote answer applied cameraId=${session.request.cameraId}")
                    drainPendingRemoteIceCandidates(session)
                    publishState(
                        request = session.request,
                        connectionState = CameraConnectionState.CONNECTING,
                        detailMessage = DETAIL_ANSWER_APPLIED_WAITING_FOR_TRACK,
                        errorMessage = null,
                        isStreaming = false,
                        signalingUrl = session.wsUrl,
                        lastFrameAtEpochMillis = null,
                    )
                }

                override fun onSetFailure(error: String?) {
                    failSessionAsync(session, error ?: "Failed to apply remote answer")
                }
            },
            SessionDescription(answerType, sdp),
        )
    }

    private fun handleRemoteIceCandidate(
        session: PreviewSession,
        candidateJson: JSONObject?,
    ) {
        if (!isActiveSession(session)) {
            return
        }
        val peerConnection = session.peerConnection ?: return
        val candidateSdp = candidateJson?.optString("candidate").orEmpty()
        if (candidateSdp.isBlank()) {
            Log.w(TAG, "Ignoring blank remote ICE candidate cameraId=${session.request.cameraId}")
            return
        }
        val candidate = IceCandidate(
            candidateJson?.optString("sdpMid"),
            candidateJson?.optInt("sdpMLineIndex", 0) ?: 0,
            candidateSdp,
        )
        val shouldQueue = synchronized(session) {
            if (!session.remoteDescriptionApplied) {
                session.pendingRemoteIceCandidates += candidate
                true
            } else {
                false
            }
        }
        if (shouldQueue) {
            Log.i(
                TAG,
                "Queueing remote ICE cameraId=${session.request.cameraId} sdpMid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex}",
            )
            return
        }
        Log.i(
            TAG,
            "Applying remote ICE cameraId=${session.request.cameraId} sdpMid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex}",
        )
        peerConnection.addIceCandidate(candidate)
    }

    private fun drainPendingRemoteIceCandidates(session: PreviewSession) {
        if (!isActiveSession(session)) {
            return
        }
        val pendingCandidates = synchronized(session) {
            if (!session.remoteDescriptionApplied || session.pendingRemoteIceCandidates.isEmpty()) {
                emptyList()
            } else {
                val drained = session.pendingRemoteIceCandidates.toList()
                session.pendingRemoteIceCandidates.clear()
                drained
            }
        }
        if (pendingCandidates.isEmpty()) {
            return
        }
        val peerConnection = session.peerConnection ?: return
        pendingCandidates.forEach { candidate ->
            if (!isActiveSession(session)) {
                return
            }
            Log.i(
                TAG,
                "Draining queued remote ICE cameraId=${session.request.cameraId} sdpMid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex}",
            )
            peerConnection.addIceCandidate(candidate)
        }
    }

    private fun bindRemoteTrack(
        videoTrack: VideoTrack,
        session: PreviewSession,
        source: String,
    ) {
        if (!isActiveSession(session)) {
            return
        }
        val trackId = videoTrack.id()
        val shouldPublish = synchronized(trackLock) {
            if (!isActiveSession(session)) {
                return@synchronized false
            }
            if (remoteVideoTrackId == trackId) {
                Log.i(
                    TAG,
                    "bindRemoteTrack skip duplicate cameraId=${session.request.cameraId} source=$source trackId=$trackId",
                )
                return@synchronized false
            }
            Log.i(
                TAG,
                "bindRemoteTrack cameraId=${session.request.cameraId} source=$source trackId=$trackId sinks=${attachedSinks.size}",
            )
            clearRemoteTrackLocked()
            remoteVideoTrack = videoTrack
            remoteVideoTrackId = trackId
            videoTrack.setEnabled(true)
            videoTrack.addSink(frameObserver)
            attachedSinks.forEach(videoTrack::addSink)
            true
        }
        if (shouldPublish) {
            publishState(
                request = session.request,
                connectionState = CameraConnectionState.WAITING_FOR_VIDEO,
                detailMessage = DETAIL_REMOTE_TRACK_BOUND,
                errorMessage = null,
                isStreaming = false,
                signalingUrl = session.wsUrl,
                lastFrameAtEpochMillis = null,
            )
        }
    }

    private fun clearRemoteTrack() {
        synchronized(trackLock) {
            clearRemoteTrackLocked()
        }
    }

    private fun clearRemoteTrackLocked() {
        val track = remoteVideoTrack
        if (track == null) {
            remoteVideoTrackId = null
            return
        }
        Log.i(TAG, "clearRemoteTrack trackId=${remoteVideoTrackId ?: track.id()} sinks=${attachedSinks.size}")
        attachedSinks.forEach(track::removeSink)
        track.removeSink(frameObserver)
        remoteVideoTrack = null
        remoteVideoTrackId = null
    }

    private fun publishFrameArrivalStateIfDue(
        frame: VideoFrame,
        nowEpochMillis: Long,
    ) {
        val frameWidth = frame.buffer.width.takeIf { it > 0 }
        val frameHeight = frame.buffer.height.takeIf { it > 0 }
        val frameRotation = frame.rotation
        val lastPublishedAt = lastFrameStatePublishedAtEpochMillis.get()
        val shouldPublishFrameFreshness = lastPublishedAt == 0L ||
            nowEpochMillis - lastPublishedAt >= FRAME_STATE_PUBLISH_INTERVAL_MILLIS
        var geometryChanged = false
        previewStateFlow.update { current ->
            geometryChanged = frameWidth != null &&
                frameHeight != null &&
                (
                    current.videoWidth != frameWidth ||
                        current.videoHeight != frameHeight ||
                        current.videoRotationDeg != frameRotation
                    )

            if (!shouldPublishFrameFreshness && !geometryChanged) {
                return@update current
            }

            current.copy(
                isStreaming = if (shouldPublishFrameFreshness) true else current.isStreaming,
                connectionState = if (shouldPublishFrameFreshness) CameraConnectionState.LIVE else current.connectionState,
                detailMessage = if (shouldPublishFrameFreshness) "Live viewer frame received" else current.detailMessage,
                errorMessage = if (shouldPublishFrameFreshness) null else current.errorMessage,
                lastFrameAtEpochMillis = if (shouldPublishFrameFreshness) nowEpochMillis else current.lastFrameAtEpochMillis,
                videoWidth = frameWidth ?: current.videoWidth,
                videoHeight = frameHeight ?: current.videoHeight,
                videoRotationDeg = if (frameWidth != null && frameHeight != null) frameRotation else current.videoRotationDeg,
            )
        }
        if (shouldPublishFrameFreshness) {
            lastFrameStatePublishedAtEpochMillis.set(nowEpochMillis)
        }
        if (geometryChanged && frameWidth != null && frameHeight != null) {
            Log.i(
                TAG,
                "Preview geometry updated cameraId=${previewStateFlow.value.cameraId} " +
                    "size=${frameWidth}x${frameHeight} rotation=$frameRotation",
            )
        }
    }

    private fun failSessionAsync(session: PreviewSession, reason: String) {
        serviceScope.launch {
            sessionMutex.withLock {
                if (!isActiveSession(session)) {
                    return@withLock
                }
                Log.e(TAG, "Failing viewer session cameraId=${session.request.cameraId} reason=$reason")
                closeActiveSessionLocked(resetState = false)
                publishState(
                    request = session.request,
                    connectionState = CameraConnectionState.ERROR,
                    detailMessage = "Viewer session failed",
                    errorMessage = reason,
                    isStreaming = false,
                    signalingUrl = session.wsUrl,
                    lastFrameAtEpochMillis = null,
                )
            }
        }
    }

    private fun closeActiveSessionLocked(resetState: Boolean) {
        val session = activeSession
        activeSession = null
        if (session == null) {
            Log.i(TAG, "closeActiveSession no active session resetState=$resetState")
        } else {
            session.manualStop = true
            session.closed = true
            Log.i(
                TAG,
                "closeActiveSession cameraId=${session.request.cameraId} pcId=${session.pcId} resetState=$resetState",
            )
        }

        val webSocket = session?.webSocket
        val peerConnection = session?.peerConnection
        if (session != null) {
            synchronized(session) {
                session.signalingReadyReceived = false
                session.localDescriptionApplied = false
                session.remoteDescriptionApplied = false
                session.pendingRemoteIceCandidates.clear()
            }
            session.webSocket = null
            session.peerConnection = null
        }

        clearRemoteTrack()

        try {
            webSocket?.close(1000, "Viewer preview closed")
        } catch (_: Throwable) {
            Unit
        }
        try {
            peerConnection?.close()
        } catch (_: Throwable) {
            Unit
        }
        try {
            peerConnection?.dispose()
        } catch (_: Throwable) {
            Unit
        }

        if (resetState) {
            lastFrameStatePublishedAtEpochMillis.set(0L)
            previewStateFlow.value = previewStateFlow.value.copy(
                isStreaming = false,
                connectionState = CameraConnectionState.IDLE,
                detailMessage = "Preview idle",
                errorMessage = null,
                signalingUrl = null,
                lastFrameAtEpochMillis = null,
                videoWidth = null,
                videoHeight = null,
                videoRotationDeg = null,
            )
        }
    }

    private fun publishState(
        request: CameraPreviewRequest,
        connectionState: CameraConnectionState,
        detailMessage: String,
        errorMessage: String?,
        isStreaming: Boolean,
        signalingUrl: String?,
        lastFrameAtEpochMillis: Long?,
    ) {
        lastFrameStatePublishedAtEpochMillis.set(0L)
        previewStateFlow.value = CameraPreviewState(
            isStreaming = isStreaming,
            sourceLabel = request.sourceLabel,
            cameraId = request.cameraId,
            tableId = request.tableId,
            tableLabel = request.tableLabel,
            connectionState = connectionState,
            detailMessage = detailMessage,
            errorMessage = errorMessage,
            signalingUrl = signalingUrl,
            lastFrameAtEpochMillis = lastFrameAtEpochMillis,
            videoWidth = null,
            videoHeight = null,
            videoRotationDeg = null,
        )
    }

    private fun resolveWebSocketUrl(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        if (normalized.isBlank()) {
            throw IllegalArgumentException("Signaling base URL is blank.")
        }

        val candidate = when {
            normalized.startsWith("ws://") || normalized.startsWith("wss://") -> normalized
            normalized.startsWith("http://") || normalized.startsWith("https://") -> normalized
            else -> "http://$normalized"
        }

        return when {
            candidate.startsWith("ws://") || candidate.startsWith("wss://") -> appendPreviewPath(candidate)
            candidate.startsWith("http://") -> appendPreviewPath(candidate.replaceFirst("http://", "ws://"))
            candidate.startsWith("https://") -> appendPreviewPath(candidate.replaceFirst("https://", "wss://"))
            else -> throw IllegalArgumentException("Unsupported signaling URL: $baseUrl")
        }
    }

    private fun appendPreviewPath(baseUrl: String): String {
        return if (baseUrl.endsWith(PREVIEW_PATH)) {
            baseUrl
        } else {
            "$baseUrl$PREVIEW_PATH"
        }
    }

    private fun isActiveSession(session: PreviewSession): Boolean {
        return activeSession === session && !session.closed && session.peerConnection != null
    }

    private fun VideoSink.logLabel(): String {
        val typeName = javaClass.simpleName.ifBlank { javaClass.name }
        return "$typeName@${Integer.toHexString(hashCode())}"
    }

    private data class PreviewSession(
        val request: CameraPreviewRequest,
        val wsUrl: String,
        val pcId: String,
        var manualStop: Boolean = false,
        var closed: Boolean = false,
        var webSocket: WebSocket? = null,
        var peerConnection: PeerConnection? = null,
        var signalingReadyReceived: Boolean = false,
        var localDescriptionApplied: Boolean = false,
        var remoteDescriptionApplied: Boolean = false,
        val pendingRemoteIceCandidates: MutableList<IceCandidate> = mutableListOf(),
    )
}
