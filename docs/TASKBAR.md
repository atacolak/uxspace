# Taskbar redesign

The plan for turning today's minimal taskbar into a full DeX-style bar — a left launch
cluster, the running-app strip in the middle, and a right system-status tray.

## Current state

[`DesktopPresentation`](../app/src/main/kotlin/com/vspace/desktop/DesktopPresentation.kt)
draws a full-width bar flush with the bottom edge holding three things: an app-drawer
button, a centred strip of one icon per open window (`runningIcons`), and a clock pinned
right. It is a real Android view hierarchy on `Theme.DeviceDefault`, so on a Samsung
device the widgets are styled as One UI.

---

## Target layout

A single horizontal bar in three zones — left and right pinned, middle centred:

```
[ ☰ | ⟳  ▭  🔍 ]      ( app  app  app )      [ 🔊  ✉  📶  ▮▮▮  🔋  12:30 PM ]
  └ left cluster ┘      └ running apps ┘       └─────── status tray ───────┘
```

Build it as a horizontal `LinearLayout` filling the bar width:
`[left cluster] [spacer weight=1] [running apps] [spacer weight=1] [status tray]`.
The two weighted spacers keep the running-app strip centred while the clusters stay
pinned to their edges.

---

## Left cluster

In order: **app-drawer button**, a thin **divider**, then **Recent apps**, **Show
desktop**, **Search**.

| Item | Action |
|---|---|
| App drawer (`ic_apps`, exists) | Toggles the drawer overlay — `WorkspaceController.setDrawerOpen`. Already wired; just moved to the far left. |
| Divider | A 1dp-wide view at `VSpaceTheme` divider colour, with vertical margins, separating the launcher from the buttons. |
| Recent apps | A panel of apps recently opened **in the workspace** — Android's system recents API is not available to apps, so "recent" means VSpace's own launch history. Needs a small most-recently-used list in `WorkspaceController` (`launchApp` pushes onto it). The panel reuses the drawer's overlay surface or is a small popup. |
| Show desktop | Minimises every open window; a second tap restores them. Each `AppWindow` already has a `minimized` flag — add `WorkspaceController.toggleShowDesktop()` that sets/clears it on all windows. |
| Search | Opens the drawer with its search field focused — `DrawerPresentation` already has the field; add a way to open the drawer *and* request focus on it. |

New vector drawables needed: recent apps, show desktop, search, and the divider.

---

## Running apps (middle)

Already implemented — `runningIcons`, one icon per open window, tap to focus, double-tap
to un-maximise. No change beyond moving it into the centre zone of the new layout. A
later refinement: highlight the icon of the front-most (focused) window.

---

## Status tray (right)

Right-to-left as the user sees it: **Clock**, **Battery level**, **Signal strength**,
**Wifi**, **Message indicator**, **Volume**. All of these read the **host phone's** state
— the desktop runs in the app's own process, so the normal system services work.

| Item | Source | Notes |
|---|---|---|
| Clock | `SimpleDateFormat`, re-ticked (exists) | Configurable — default is the time on top (AM/PM) with the date underneath. Format options (12/24h, show/hide date) belong in [SETTINGS.md](SETTINGS.md). |
| Battery | `ACTION_BATTERY_CHANGED` sticky broadcast / `BatteryManager` | Icon + percentage; charging glyph when plugged in. No permission. |
| Signal strength | `TelephonyManager` + `TelephonyCallback.SignalStrengthsListener` (API 31+) | Bars from `SignalStrength.getLevel()` (0–4). Reading the level needs no permission. Hide on Wi-Fi-only devices. |
| Wifi | `ConnectivityManager` network callback + `WifiManager` RSSI → `calculateSignalLevel` | Connected / disconnected, plus signal bars. `ACCESS_WIFI_STATE` is a normal (auto-granted) permission. |
| Message indicator | `NotificationListenerService` | There is no general "unread count" API. Show a dot when notifications are present. **Requires the user to grant notification access** in system settings — surface this as an opt-in, like the Shizuku setup banner. |
| Volume | `AudioManager` | A speaker icon; a tap opens a small volume slider that calls `setStreamVolume` / `adjustStreamVolume` on `STREAM_MUSIC`. No permission. |

### Wiring

Add a `SystemStatus` helper in `:app` that registers the broadcast receivers and
telephony / connectivity callbacks once, holds the latest values, and exposes a change
listener. `DesktopPresentation` binds the tray views to it and updates them when it
fires — the same shape as the existing `clockTick` and the
`WorkspaceController.onAppLaunched` / `onAppClosed` hooks. Receivers are registered in
`onStart` / `onCreate` and unregistered in `onStop` so nothing leaks when the glasses
disconnect.

---

## Build order

1. Restructure `buildTaskbar()` into the three-zone layout (no new behaviour yet).
2. Left cluster: divider, Show desktop (`toggleShowDesktop`), Search (open drawer +
   focus), Recent apps (workspace MRU list + panel).
3. `SystemStatus` helper; status tray items, easiest first — clock (done), battery,
   volume, wifi, signal.
4. Message indicator last — it needs the notification-access opt-in flow.

**Touch points.** `DesktopPresentation` (three-zone layout, tray views); new
`SystemStatus` helper in `:app`; `WorkspaceController` (`toggleShowDesktop`, workspace
MRU list, open-drawer-with-search); new vector drawables; notification-access opt-in for
the message indicator.
