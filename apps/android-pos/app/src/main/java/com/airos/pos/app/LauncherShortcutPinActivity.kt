package com.airos.pos.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

private const val LauncherShortcutPinLogTag = "AIROS_LAUNCHER_PIN"

/**
 * One-shot helper activity for pinning AIROS Kassa to Sunmi Launcher3 Home.
 *
 * Sunmi/Launcher3 badges pinned shortcuts from the launch alias icon. The visible
 * shortcut icon is the full AIROS logo, while the AirosKassaLauncher alias icon is
 * transparent in the manifest to hide the corner badge.
 *
 * This stays isolated from MainActivity and POS runtime logic.
 */
class LauncherShortcutPinActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPinnedShortcutWithFullLogoIcon()
    }

    private fun requestPinnedShortcutWithFullLogoIcon() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(LauncherShortcutPinLogTag, "Pinned shortcuts are not supported on this Android version")
            Toast.makeText(this, "Aloitusnäyttöön lisääminen ei ole tuettu tällä Android-versiolla", Toast.LENGTH_LONG).show()
            finishSoon()
            return
        }

        val shortcutManager = getSystemService(ShortcutManager::class.java)
        if (shortcutManager == null) {
            Log.w(LauncherShortcutPinLogTag, "ShortcutManager is unavailable")
            Toast.makeText(this, "Launcher shortcut -palvelu ei ole käytettävissä", Toast.LENGTH_LONG).show()
            finishSoon()
            return
        }

        if (!shortcutManager.isRequestPinShortcutSupported) {
            Log.w(LauncherShortcutPinLogTag, "Launcher does not support requestPinShortcut")
            Toast.makeText(this, "Launcher ei tue automaattista lisäystä aloitusnäyttöön", Toast.LENGTH_LONG).show()
            finishSoon()
            return
        }

        val shortcutId = "airos-kassa-home-shortcut-full-logo-alias-badge-transparent-v2"
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(packageName, "$packageName.AirosKassaLauncher")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        val shortcut = ShortcutInfo.Builder(this, shortcutId)
            .setShortLabel("AIROS")
            .setLongLabel("AIROS Kassa")
            .setIcon(Icon.createWithResource(this, R.mipmap.airos_pos_icon_legacy))
            .setIntent(launchIntent)
            .build()

        val requested = shortcutManager.requestPinShortcut(shortcut, null)
        Log.i(LauncherShortcutPinLogTag, "requestPinShortcut full-logo alias-badge-transparent result=$requested")
        Toast.makeText(this, "Pyydetään AIROS Kassa aloitusnäyttöön", Toast.LENGTH_SHORT).show()

        finishSoon()
    }

    private fun finishSoon() {
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 900L)
    }
}
