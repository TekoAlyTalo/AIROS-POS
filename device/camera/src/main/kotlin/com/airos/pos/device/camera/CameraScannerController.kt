package com.airos.pos.device.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.airos.pos.core.model.ScanEvent
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Local CameraX + MLKit barcode scanner for the Scan tab.
 *
 * This is a standalone device-layer component introduced by Package 1 of the custom Scan
 * preview work. It is NOT yet wired into the UI or the [com.airos.pos.app.AppContainer].
 * Package 2 will attach it to ScannerViewModel / ScannerScreen.
 *
 * Key points:
 * - Uses CameraX [ProcessCameraProvider] bound to a Compose-owned [LifecycleOwner].
 * - Runs MLKit barcode analyzer on a dedicated single-thread executor.
 * - Emits results through a [Flow] of [ScanEvent] mirroring the existing Sunmi scanEvents API,
 *   so the ViewModel wiring in Package 2 can treat both sources uniformly.
 * - Torch control goes through [Camera.getCameraControl] so it stays on the same CameraX session
 *   that owns the preview. The existing [AndroidTorchService] is untouched in this package.
 *
 * The CAMERA runtime permission must be granted by the caller before [bindToLifecycle] is invoked.
 * Package 2 will handle the permission request in the Scan tab UI layer.
 */
interface CameraScannerController {
    enum class BindingState { UNBOUND, BOUND, ERROR }

    val scanEvents: Flow<ScanEvent>
    val bindingState: StateFlow<BindingState>

    fun createPreviewView(context: Context): View

    suspend fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        previewView: View,
    )

    suspend fun unbind()

    suspend fun setTorch(enabled: Boolean)

    fun close()
}

class CameraXMlKitScannerController(
    context: Context,
) : CameraScannerController {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val scanEventsFlow = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 8)
    private val bindingStateFlow = MutableStateFlow(CameraScannerController.BindingState.UNBOUND)

    private val barcodeScanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_ITF,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_AZTEC,
                )
                .build(),
        )
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    override val scanEvents: Flow<ScanEvent> = scanEventsFlow.asSharedFlow()
    override val bindingState: StateFlow<CameraScannerController.BindingState> =
        bindingStateFlow.asStateFlow()

    override fun createPreviewView(context: Context): View =
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }

    override suspend fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        previewView: View,
    ) {
        val typedPreviewView = previewView as? PreviewView
        if (typedPreviewView == null) {
            Log.w(TAG, "bindToLifecycle received a non-PreviewView host.")
            bindingStateFlow.value = CameraScannerController.BindingState.ERROR
            return
        }
        val provider = runCatching { awaitCameraProvider() }
            .getOrElse { error ->
                Log.w(TAG, "ProcessCameraProvider init failed.", error)
                bindingStateFlow.value = CameraScannerController.BindingState.ERROR
                return
            }

        withContext(Dispatchers.Main) {
            val preview = Preview.Builder().build().also { useCase ->
                useCase.setSurfaceProvider(typedPreviewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(analysisExecutor, ::analyzeFrame)
                }

            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                cameraProvider = provider
                bindingStateFlow.value = CameraScannerController.BindingState.BOUND
            }.onFailure { error ->
                Log.w(TAG, "CameraX bindToLifecycle failed.", error)
                bindingStateFlow.value = CameraScannerController.BindingState.ERROR
            }
        }
    }

    override suspend fun unbind() {
        withContext(Dispatchers.Main) {
            cameraProvider?.unbindAll()
            camera = null
            bindingStateFlow.value = CameraScannerController.BindingState.UNBOUND
        }
    }

    override suspend fun setTorch(enabled: Boolean) {
        withContext(Dispatchers.Main) {
            val cam = camera ?: return@withContext
            runCatching { cam.cameraControl.enableTorch(enabled) }
                .onFailure { Log.w(TAG, "Failed to set CameraX torch enabled=$enabled", it) }
        }
    }

    override fun close() {
        runCatching { barcodeScanner.close() }
        runCatching { analysisExecutor.shutdown() }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        barcodeScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val barcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                    ?: barcodes.firstOrNull { !it.displayValue.isNullOrBlank() }
                if (barcode != null) {
                    val rawValue = barcode.rawValue ?: barcode.displayValue ?: return@addOnSuccessListener
                    scanEventsFlow.tryEmit(
                        ScanEvent(
                            rawValue = rawValue,
                            symbology = formatName(barcode.format),
                            scannedAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "MLKit barcode analysis failed.", error)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (error: Throwable) {
                        continuation.resumeWithException(error)
                    }
                },
                mainExecutor,
            )
        }

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_128 -> "CODE-128"
        Barcode.FORMAT_CODE_39 -> "CODE-39"
        Barcode.FORMAT_CODE_93 -> "CODE-93"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_DATA_MATRIX -> "DATAMATRIX"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        else -> "BARCODE"
    }

    private companion object {
        private const val TAG = "CameraXScannerCtrl"
    }
}
