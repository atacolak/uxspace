// Callback Binder the app registers with PrivilegedService so the shell-uid helper can
// forward globally-monitored Ctrl+Alt+X hotkeys back to it. See IPrivilegedService for
// the registration entry points. Hotkey codes are the HK_* constants in
// com.uxspace.privileged.PrivilegedHotkeys (a Kotlin object kept in sync between sides).
package com.uxspace.privileged;

oneway interface IPrivilegedHotkeyListener {
    /** A Ctrl+Alt+X combo fired. [code] is a PrivilegedHotkeys.HK_* constant. */
    void onHotkey(int code) = 1;

    /**
     * Batched relative-mouse motion, taken straight from the kernel's evdev stream
     * (EV_REL deltas summed across one SYN_REPORT group). Bypasses the system
     * cursor's screen-edge clamping — drives the workspace cursor directly so it
     * never gets stuck just because the phone-side pointer hit a phone edge.
     * [wheel] is REL_WHEEL ticks (positive = up).
     */
    void onMouseDelta(int dx, int dy, int wheel) = 2;

    /**
     * Mouse button transitioned. [code] is the kernel BTN_* code (272 left,
     * 273 right, 274 middle). [pressed] is true on press, false on release.
     * Forwarded by the helper because once it EVIOCGRABs the mouse device,
     * system_server stops seeing it — `dispatchGenericMotionEvent` would
     * otherwise miss button events too.
     */
    void onMouseButton(int code, boolean pressed) = 3;

    /**
     * A non-hotkey keyboard key transitioned, forwarded so the workspace can consume
     * hardware-keyboard text where no Android window holds focus for it — namely the
     * drawer search box (the desktop Presentation is non-focusable by design, so a USB/BT
     * keyboard's keys never reach it the way they reach a launched app). [keyCode] is the
     * kernel KEY_* code; [pressed] is true on press/autorepeat, false on release; [shift]
     * is the shift-held state at that moment. Ctrl+Alt combos are delivered via [onHotkey]
     * instead and are never forwarded here.
     */
    void onKey(int keyCode, boolean pressed, boolean shift) = 4;

    /**
     * A two-finger pinch on a touchpad. [scale] is the incremental span ratio for this
     * frame (>1 = fingers spreading = zoom in, <1 = zoom out). Forwarded so the app can
     * drive WorkspaceController.pinch, the same way the phone trackpad's pinch does.
     */
    void onZoom(float scale) = 5;
}
