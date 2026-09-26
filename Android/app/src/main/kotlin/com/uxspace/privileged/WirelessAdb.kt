package com.uxspace.privileged

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Tiny helper around [Settings.Global] `adb_wifi_enabled`.
 *
 * UxSpace cannot toggle Wireless Debugging as an ordinary app. After a one-time
 * `adb shell pm grant com.uxspace android.permission.WRITE_SECURE_SETTINGS` on a
 * development device, this helper can flip the setting so [PrivilegedService]
 * reconnects without opening Developer Options on every launch.
 *
 * A genuinely new Wi-Fi network may still show Android's "Allow wireless debugging
 * on this network" trust prompt — that is not bypassed here. Pairing keys are not
 * touched; they survive off/on resets of this setting.
 */
internal object WirelessAdb {

    private const val TAG = "UxSpace/Privileged"

    /** AOSP / Samsung One UI key for Wireless Debugging. */
    const val SETTING = "adb_wifi_enabled"

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isEnabled(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, SETTING, 0) != 0
    }.getOrDefault(false)

    fun setEnabled(ctx: Context, enabled: Boolean): Boolean {
        if (!canWrite(ctx)) return false
        return try {
            val ok = Settings.Global.putInt(
                ctx.contentResolver,
                SETTING,
                if (enabled) 1 else 0,
            )
            Log.i(TAG, "set $SETTING=${if (enabled) 1 else 0} ok=$ok")
            ok
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS rejected while setting $SETTING", e)
            false
        }
    }
}
