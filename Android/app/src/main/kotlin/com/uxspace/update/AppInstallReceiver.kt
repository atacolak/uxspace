package com.uxspace.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Receives status callbacks from the [AppUpdater] PackageInstaller session. The important case is
 * [PackageInstaller.STATUS_PENDING_USER_ACTION]: the system hands back a confirm Intent that we must
 * launch to show the user the install screen (a non-privileged app can't install without it).
 */
class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(confirm) }
                        .onFailure { Log.w(TAG, "could not launch install confirm: ${it.message}") }
                } else {
                    Log.w(TAG, "pending user action but no confirm intent")
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "update installed successfully")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install failed (status $status): $msg")
                runCatching { Toast.makeText(ctx, "Update failed: ${msg ?: "code $status"}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    companion object {
        private const val TAG = "AppUpdater"
    }
}
