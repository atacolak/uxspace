package com.uxspace.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.uxspace.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * In-app APK self-updater. Reads the publish manifest ([MANIFEST_URL], `index.json`, written by
 * `publish-uxspace.ps1`); if it advertises a higher `versionCode` than this build, it downloads the
 * APK (SHA-256 verified) and hands it to the system [PackageInstaller]. No app store, no Obtainium.
 *
 * A sideloaded app cannot install *silently* — Android always shows its own install-confirmation
 * screen, and the user must have granted "install unknown apps" to UxSpace ([canInstall]). This
 * class automates everything up to that final system tap.
 *
 * Debug builds are signed with the machine's stable `debug.keystore`, so successive builds share a
 * signature and the system treats each as an in-place UPDATE (not a conflicting install).
 *
 * Ported from the MyNavvy updater; the two apps share this design.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    const val MANIFEST_URL = "https://dist.darkclad.org/uxspace/index.json"
    const val INSTALL_ACTION = "com.uxspace.update.APP_INSTALL_STATUS"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val PREFS = "app_update"

    /** A published build newer than this one, from the manifest. */
    data class Release(
        val versionName: String,
        val versionCode: Int,
        val url: String,
        val sha256: String,
        val size: Long,
    )

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "app-update").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Fetch the manifest and report a release NEWER than this build (else null). Callback on the
     * main thread. Fails soft: offline or any error → (null, message), never throws — an auto-check
     * must never disrupt startup. `error` is null when we reached the server and are simply current.
     */
    fun check(ctx: Context, cb: (release: Release?, error: String?) -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                if (!online(app)) { main.post { cb(null, "offline") }; return@execute }
                val r = fetch()
                val newer = r != null && r.versionCode > BuildConfig.VERSION_CODE
                main.post { cb(if (newer) r else null, null) }
            } catch (t: Throwable) {
                Log.w(TAG, "update check failed: ${t.message}")
                main.post { cb(null, t.message ?: "check failed") }
            }
        }
    }

    /** Download [r] (SHA-256 verified) then commit it to the system installer. Main-thread callbacks. */
    fun downloadAndInstall(
        ctx: Context,
        r: Release,
        onProgress: (done: Long, total: Long) -> Unit,
        onDone: (ok: Boolean, message: String) -> Unit,
    ) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                val apk = download(app, r) { got -> main.post { onProgress(got, r.size) } }
                install(app, apk)
                main.post { onDone(true, "installer launched") }
            } catch (t: Throwable) {
                Log.e(TAG, "update failed: ${t.message}", t)
                main.post { onDone(false, t.message ?: "update failed") }
            }
        }
    }

    // --- "install unknown apps" gate -----------------------------------------

    /** Whether the system will let us launch an install (API 26+ per-app "install unknown apps"). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** Settings screen to grant "install unknown apps" to UxSpace (API 26+). */
    fun unknownSourcesSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    // --- "don't nag" bookkeeping ---------------------------------------------

    /** The versionCode the user last tapped "Later" on, so we don't re-prompt for the same build. */
    fun dismissedCode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("dismissed", 0)

    fun setDismissed(ctx: Context, code: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("dismissed", code).apply()
    }

    // --- internals -----------------------------------------------------------

    private fun fetch(): Release? {
        val j = JSONObject(httpGetText(MANIFEST_URL))
        if (j.optString("package") != BuildConfig.APPLICATION_ID) {
            Log.w(TAG, "manifest package mismatch: ${j.optString("package")}"); return null
        }
        val code = j.optInt("versionCode", -1)
        val url = j.optString("apk")
        val sha = j.optString("sha256").lowercase()
        if (code < 0 || url.isEmpty() || sha.length != 64) {
            Log.w(TAG, "manifest incomplete (code=$code url=$url sha=${sha.length})"); return null
        }
        return Release(j.optString("versionName", "?"), code, url, sha, j.optLong("size", -1L))
    }

    /** Download to a private temp file and verify its SHA-256 before returning it. */
    private fun download(ctx: Context, r: Release, onBytes: (Long) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        // Prune any stale APKs from earlier attempts (transient — cacheDir is fine to lose).
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "uxspace-${r.versionCode}.apk")

        val conn = (URL(r.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS; requestMethod = "GET"
            addAccessHeaders()
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK)
                throw IllegalStateException("HTTP ${conn.responseCode} fetching the APK")
            val md = MessageDigest.getInstance("SHA-256")
            var got = 0L
            var lastTick = 0L
            conn.inputStream.use { ins ->
                out.outputStream().use { outs ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        outs.write(buf, 0, n); md.update(buf, 0, n); got += n
                        if (got - lastTick > 500_000) { lastTick = got; onBytes(got) }
                    }
                }
            }
            val actual = md.digest().joinToString("") { String.format("%02x", it) }
            if (!actual.equals(r.sha256, ignoreCase = true)) {
                out.delete(); throw IllegalStateException("APK checksum mismatch — download corrupt")
            }
            onBytes(if (r.size > 0) r.size else got)
            Log.i(TAG, "downloaded ${out.name} (${out.length()} bytes), sha ok")
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** Stream the APK into a [PackageInstaller] session and commit — the system shows the install UI. */
    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { ins ->
                session.openWrite("uxspace.apk", 0, apk.length()).use { outs ->
                    ins.copyTo(outs, 128 * 1024)
                    session.fsync(outs)
                }
            }
            // The installer fills in EXTRA_STATUS/EXTRA_INTENT, so the PendingIntent must be mutable.
            val intent = Intent(ctx, AppInstallReceiver::class.java).setAction(INSTALL_ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
            session.commit(pending.intentSender)
            Log.i(TAG, "PackageInstaller session $sessionId committed")
        }
    }

    private fun httpGetText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS; requestMethod = "GET"
            addAccessHeaders()
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Attach the Cloudflare Access service-token headers when this build embeds them, so the
     *  updater's requests pass the (gated) dist server's Service Auth policy. No-op when empty
     *  (LAN/dev builds). */
    private fun HttpURLConnection.addAccessHeaders() {
        if (BuildConfig.CF_ACCESS_CLIENT_ID.isNotEmpty() && BuildConfig.CF_ACCESS_CLIENT_SECRET.isNotEmpty()) {
            setRequestProperty("CF-Access-Client-Id", BuildConfig.CF_ACCESS_CLIENT_ID)
            setRequestProperty("CF-Access-Client-Secret", BuildConfig.CF_ACCESS_CLIENT_SECRET)
        }
    }

    private fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (t: Throwable) {
        true // can't tell — try rather than refuse
    }
}
