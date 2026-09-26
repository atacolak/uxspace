package com.uxspace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.uxspace.spatial.WorkspaceController

/**
 * Foreground service whose lifetime brackets a UxSpace glasses session — started by
 * [MainActivity] when the glasses display comes up, stopped when it goes away.
 *
 * Its job is to be a reliable teardown anchor: [onDestroy] calls
 * [WorkspaceController.closeAllLaunchedApps] so every app UxSpace pushed onto a
 * trusted display dies with the session instead of being re-homed onto the phone's
 * own screen. The normal `WorkspacePresentation.dismiss()` path already runs the
 * same cleanup; this service covers the cases where the activity disappears without
 * dismissing the presentation cleanly (task-removed, low-memory kill, etc.).
 *
 * Marked `connectedDevice` because the session is tied to a USB-attached peripheral
 * (the glasses) — the official foreground-service type for "driven by an external
 * device connection" on Android 14+.
 */
class WorkspaceService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("UxSpace workspace")
            .setContentText("Glasses session active")
            .setSmallIcon(R.drawable.ic_usb)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed — continuing as a plain service", t)
        }
        Log.i(TAG, "started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        Log.i(TAG, "destroying — closing UxSpace-launched apps")
        WorkspaceController.closeAllLaunchedApps()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UxSpace/WorkspaceService"
        private const val CHANNEL_ID = "uxspace_workspace"
        private const val NOTIFICATION_ID = 0x5553  // "US"

        /** Idempotent: starting an already-started service just re-delivers onStartCommand. */
        fun start(context: Context) {
            val intent = Intent(context, WorkspaceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Idempotent: stopping a non-running service is a no-op. */
        fun stop(context: Context) {
            context.stopService(Intent(context, WorkspaceService::class.java))
        }

        private fun ensureNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Workspace session",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while UxSpace is using the glasses"
                    setShowBadge(false)
                },
            )
        }
    }
}
