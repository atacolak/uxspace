package com.viturekit.dex

import android.content.Context
import android.os.Build

/**
 * Detects whether the app is currently running inside a Samsung DeX desktop session.
 *
 * DeX is still ordinary Android, so VitureKit works there without special handling. What
 * *does* matter is the transition: entering or leaving DeX reconfigures the active display
 * set, which [com.viturekit.VitureSession] surfaces as
 * [com.viturekit.model.VitureEvent.DisplaysChanged]. Use [isDexActive] to branch UI that
 * should differ between a phone layout and a desktop layout.
 */
object DexEnvironment {

    /**
     * `true` while a Samsung DeX session is active.
     *
     * Implemented via the reflective check Samsung documents for DeX-aware apps; it always
     * returns `false` on non-Samsung devices, where the relevant `Configuration` fields are
     * absent.
     */
    fun isDexActive(context: Context): Boolean {
        val configuration = context.resources.configuration
        return try {
            val configClass: Class<*> = configuration.javaClass
            val enabledConstant = configClass
                .getField("SEM_DESKTOP_MODE_ENABLED")
                .getInt(configClass)
            val currentMode = configClass
                .getField("semDesktopModeEnabled")
                .getInt(configuration)
            currentMode == enabledConstant
        } catch (_: Throwable) {
            // Fields absent — not a Samsung build, or this build predates DeX.
            false
        }
    }

    /** `true` on Samsung hardware, where DeX may be available. */
    fun isSamsungDevice(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)
}
