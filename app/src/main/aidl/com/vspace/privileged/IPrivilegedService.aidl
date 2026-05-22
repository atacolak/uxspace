// Interface to VSpace's shell-uid privileged helper — the process that launches apps onto
// the workspace's virtual displays, injects input, and creates the displays themselves
// (trusted, so apps don't escape to the phone). See docs/PRIVILEGE.md.
package com.vspace.privileged;

import android.view.Surface;

interface IPrivilegedService {

    // Transaction id used while the helper is still bound through Shizuku, which uses this
    // specific id to tear the service down. Kept after we move to our own ADB bootstrap so a
    // Shizuku-bound build remains interchangeable.
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

    /** Whether the given display currently has an activity on it. */
    boolean displayHasActivity(int displayId) = 8;

    /**
     * Create a *trusted* virtual display rendering into surface. Created from this shell-uid
     * process so the TRUSTED flag is honoured — only a trusted display lets a launched app
     * follow its own splash-screen / new-task launches instead of escaping to the phone.
     * Returns the new display's id, or -1 on failure.
     */
    int createVirtualDisplay(String name, int width, int height, int densityDpi, in Surface surface) = 9;

    /** Release a virtual display previously created via createVirtualDisplay. */
    void releaseVirtualDisplay(int displayId) = 10;
}
