package com.viturekit.internal

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import com.viturekit.dex.DexEnvironment

/**
 * Observes changes to the set of system displays.
 *
 * Abstracted behind an interface so that [com.viturekit.VitureSession] stays a plain JVM
 * object — unit tests inject [NoOpDisplayWatcher] instead of touching the Android framework.
 */
internal interface DisplayWatcher {

    /** Whether a Samsung DeX session is currently active. */
    val isDexActive: Boolean

    /** Begin observing; [onDisplaysChanged] fires when a display is added or removed. */
    fun start(onDisplaysChanged: () -> Unit)

    /** Stop observing. Safe to call when not started. */
    fun stop()
}

/**
 * The production [DisplayWatcher]: bridges Android's [DisplayManager] and tracks DeX state.
 *
 * A display add/remove is the signal that the user has entered or left a DeX session (or
 * plugged/unplugged the glasses as an external display), so the VITURE display set may need
 * re-enumeration.
 */
internal class AndroidDisplayWatcher(context: Context) : DisplayWatcher {

    private val appContext = context.applicationContext
    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: DisplayManager.DisplayListener? = null

    override val isDexActive: Boolean
        get() = DexEnvironment.isDexActive(appContext)

    override fun start(onDisplaysChanged: () -> Unit) {
        if (listener != null) return
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = onDisplaysChanged()
            override fun onDisplayRemoved(displayId: Int) = onDisplaysChanged()
            override fun onDisplayChanged(displayId: Int) {
                // Pure metric/rotation changes don't alter the display set — ignore them.
            }
        }
        listener = displayListener
        displayManager.registerDisplayListener(displayListener, handler)
    }

    override fun stop() {
        listener?.let(displayManager::unregisterDisplayListener)
        listener = null
    }
}

/** A no-op [DisplayWatcher] for unit tests and non-Android hosts. */
internal class NoOpDisplayWatcher : DisplayWatcher {
    override val isDexActive: Boolean = false
    override fun start(onDisplaysChanged: () -> Unit) = Unit
    override fun stop() = Unit
}
