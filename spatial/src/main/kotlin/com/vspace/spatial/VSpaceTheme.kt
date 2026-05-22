package com.vspace.spatial

/**
 * VSpace's colour palette.
 *
 * Every piece of VSpace-drawn chrome — window frames, the taskbar — takes its colours from
 * here, so the workspace has one consistent look and a single place to retheme it.
 */
object VSpaceTheme {

    /** Window frame: the border around an app and its title bar. A light grey, à la Windows. */
    const val windowFrame: Int = 0xFFDDDDDD.toInt()

    /** A slightly darker grey for the frame's edges and the title-bar divider. */
    const val windowFrameEdge: Int = 0xFFB6B6B6.toInt()

    /** Text and button glyphs drawn on the (light) window frame. */
    const val windowFrameText: Int = 0xFF2B2B2B.toInt()

    /** Taskbar background. */
    const val taskbar: Int = 0xF0121620.toInt()

    /** Taskbar text. */
    const val taskbarText: Int = 0xFFE6E8EE.toInt()
}
