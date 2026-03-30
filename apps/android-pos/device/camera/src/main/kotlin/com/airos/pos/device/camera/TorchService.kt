package com.airos.pos.device.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

interface TorchService {
    suspend fun setTorch(enabled: Boolean)
}

class AndroidTorchService(
    context: Context,
) : TorchService {
    private val appContext = context.applicationContext
    private val cameraManager: CameraManager =
        appContext.getSystemService(CameraManager::class.java)

    override suspend fun setTorch(enabled: Boolean) {
        val cameraId = findTorchCameraId()
            ?: throw IllegalStateException("No camera with flash is available for Android torch control.")

        runCatching {
            cameraManager.setTorchMode(cameraId, enabled)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to set Android torch enabled=$enabled cameraId=$cameraId", error)
            throw error
        }
    }

    private fun findTorchCameraId(): String? {
        val backCameraWithFlash = cameraManager.cameraIdList.firstOrNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            hasFlash && lensFacing == CameraCharacteristics.LENS_FACING_BACK
        }
        if (backCameraWithFlash != null) {
            return backCameraWithFlash
        }

        return cameraManager.cameraIdList.firstOrNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    private companion object {
        private const val TAG = "AndroidTorchService"
    }
}
