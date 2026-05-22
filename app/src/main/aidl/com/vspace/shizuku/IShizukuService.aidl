// Interface to the helper that runs inside a Shizuku-spawned, ADB-shell-privileged process.
package com.vspace.shizuku;

interface IShizukuService {

    // Transaction id Shizuku itself uses to tear the service down.
    void destroy() = 16777114;

    void exit() = 1;

    /** Launch packageName/activityName onto the given display. Returns true on success. */
    boolean launchOnDisplay(int displayId, String packageName, String activityName) = 2;

    /** Inject a tap at (x, y) on the given display. */
    void tap(int displayId, int x, int y) = 3;

    /** Inject a swipe on the given display, lasting durationMs. */
    void swipe(int displayId, int fromX, int fromY, int toX, int toY, int durationMs) = 4;

    /** Inject a key event (a KeyEvent key code) on the given display. */
    void key(int displayId, int keyCode) = 5;

    /** Type text into the focused field on the given display. */
    void text(int displayId, String value) = 6;

    /** Force-stop a package — closes its activities and kills its process. */
    void forceStop(String packageName) = 7;
}
