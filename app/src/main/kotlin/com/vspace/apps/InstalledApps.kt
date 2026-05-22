package com.vspace.apps

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** A launchable application installed on the device. */
data class InstalledApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
)

/** Enumerates launchable apps and builds intents that target a specific [activityName]. */
object InstalledApps {

    /** Every app with a launcher activity, sorted by display name. */
    fun query(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val mainLauncher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(mainLauncher, 0)
            .mapNotNull { resolved ->
                val activity = resolved.activityInfo ?: return@mapNotNull null
                if (activity.packageName == context.packageName) return@mapNotNull null
                InstalledApp(
                    label = resolved.loadLabel(pm).toString(),
                    packageName = activity.packageName,
                    activityName = activity.name,
                    icon = resolved.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * An intent that launches [packageName]/[activityName] as a fresh task.
     *
     * `NEW_TASK` is required to start it from a non-activity context and to place it on
     * another display; `MULTIPLE_TASK` lets it land on the virtual display even when an
     * instance is already running on the phone.
     */
    fun launchIntent(packageName: String, activityName: String): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(packageName, activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
}
