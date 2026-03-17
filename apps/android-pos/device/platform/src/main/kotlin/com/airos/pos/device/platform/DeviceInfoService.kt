package com.airos.pos.device.platform

import android.os.Build
import com.airos.pos.core.model.DeviceProfile
import com.airos.pos.core.model.DeviceVendor

interface DeviceInfoService {
    fun currentProfile(): DeviceProfile
}

class AndroidDeviceInfoService : DeviceInfoService {
    override fun currentProfile(): DeviceProfile {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val vendor = if (manufacturer.contains("sunmi", ignoreCase = true)) {
            DeviceVendor.SUNMI
        } else {
            DeviceVendor.GENERIC_ANDROID
        }
        return DeviceProfile(
            manufacturer = manufacturer,
            model = Build.MODEL.orEmpty(),
            vendor = vendor,
            hasBuiltInPrinter = vendor == DeviceVendor.SUNMI,
            hasScanner = vendor == DeviceVendor.SUNMI,
            hasCashDrawer = vendor == DeviceVendor.SUNMI,
            hasRearCamera = true,
        )
    }
}
