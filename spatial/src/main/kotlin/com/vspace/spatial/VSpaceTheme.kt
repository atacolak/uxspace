package com.vspace.spatial

/**
 * VSpace's colour palette.
 *
 * Every piece of VSpace-drawn chrome — window frames, the taskbar — takes its colours from
 * here, so the workspace has one consistent look and a single place to retheme it.
 */
object VSpaceTheme {

    /** Window border: the light-grey frame around an app, à la a desktop OS window. */
    const val windowBorder: Int = 0xFFEAEAEA.toInt()

    /** Window title bar background — its own entry; currently the same grey as the border. */
    const val windowTitleBar: Int = 0xFFEAEAEA.toInt()

    /** Text and button glyphs drawn on the (light) window frame. */
    const val windowFrameText: Int = 0xFF2B2B2B.toInt()

    /** Taskbar background. */
    const val taskbar: Int = 0xF0121620.toInt()

    /** Taskbar text. */
    const val taskbarText: Int = 0xFFE6E8EE.toInt()
}
