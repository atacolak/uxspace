package com.uxspace.desktop

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** How a wallpaper image is laid across the screen. */
enum class PlacementMode(val displayName: String) {
    CENTER_CROP("Center crop"),
    ONE_TO_ONE("1:1 centered"),
    STRETCH("Stretch"),
    FIT("Fit"),
    TILE("Tile"),
}

/** Origin of a wallpaper image — either a bundled asset path or a SAF / content URI. */
sealed class WallpaperSource {
    data class Asset(val assetPath: String) : WallpaperSource()
    data class Uri(val uri: String) : WallpaperSource()
}

/** A wallpaper choice = where the image lives + how to place it. */
data class WallpaperSpec(
    val source: WallpaperSource,
    val mode: PlacementMode,
)

/**
 * SharedPreferences-backed per-desktop-index wallpaper choice. Three logical desktops
 * (0/1/2) share the same key model as [DesktopShortcutsStore]: a shortcut placed on
 * desktop 0 follows whichever physical screen is wired to desktop 0 in the active
 * layout, and so does its wallpaper.
 *
 * The store is intentionally tiny — `JSON` per key, parsed on every read. Three keys,
 * a handful of fields each.
 */
object DesktopWallpaperStore {

    private const val PREFS_NAME = "uxspace_desktop_wallpapers"
    private const val DEFAULT_ASSET = "workspace_background.jpg"

    /**
     * Default wallpaper used by every desktop until the user picks one. Matches
     * the asset DesktopPresentation used to hardcode.
     */
    val DEFAULT_SPEC: WallpaperSpec = WallpaperSpec(
        source = WallpaperSource.Asset(DEFAULT_ASSET),
        mode = PlacementMode.CENTER_CROP,
    )

    /** Bundled wallpaper assets the user can pick in settings. Append to grow. */
    val BUNDLED_ASSETS: List<String> = listOf(DEFAULT_ASSET)

    @Volatile
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun keyOf(desktopIdx: Int): String = "desktop_$desktopIdx"

    fun specFor(desktopIdx: Int): WallpaperSpec {
        if (!::prefs.isInitialized) return DEFAULT_SPEC
        val raw = prefs.getString(keyOf(desktopIdx), null) ?: return DEFAULT_SPEC
        return runCatching { parse(raw) }.getOrDefault(DEFAULT_SPEC)
    }

    fun setSource(desktopIdx: Int, source: WallpaperSource) {
        val current = specFor(desktopIdx)
        write(desktopIdx, current.copy(source = source))
    }

    fun setMode(desktopIdx: Int, mode: PlacementMode) {
        val current = specFor(desktopIdx)
        write(desktopIdx, current.copy(mode = mode))
    }

    private fun write(desktopIdx: Int, spec: WallpaperSpec) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(keyOf(desktopIdx), encode(spec)).apply()
        listeners.forEach { runCatching { it(desktopIdx) } }
    }

    private fun encode(spec: WallpaperSpec): String {
        val obj = JSONObject()
        obj.put("mode", spec.mode.name)
        when (val s = spec.source) {
            is WallpaperSource.Asset -> {
                obj.put("kind", "asset")
                obj.put("value", s.assetPath)
            }
            is WallpaperSource.Uri -> {
                obj.put("kind", "uri")
                obj.put("value", s.uri)
            }
        }
        return obj.toString()
    }

    private fun parse(raw: String): WallpaperSpec {
        val obj = JSONObject(raw)
        val mode = runCatching { PlacementMode.valueOf(obj.optString("mode")) }
            .getOrDefault(PlacementMode.CENTER_CROP)
        val kind = obj.optString("kind", "asset")
        val value = obj.optString("value")
        val source = when (kind) {
            "uri" -> WallpaperSource.Uri(value)
            else -> WallpaperSource.Asset(value.ifEmpty { DEFAULT_ASSET })
        }
        return WallpaperSpec(source, mode)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Int) -> Unit>()
    fun addChangeListener(l: (Int) -> Unit) { listeners.add(l) }
    fun removeChangeListener(l: (Int) -> Unit) { listeners.remove(l) }
}
