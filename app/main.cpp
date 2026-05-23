// UxSpace :app — W1.
//
// - Hosts D3D11 + a dev window with ImGui status + previews.
// - Drives spatial::VirtualScreen (DDA capture of the IddCx monitor).
// - Renders a stereo 3D scene into:
//     a) the Viture glasses output if detected (borderless fullscreen),
//     b) an off-screen render target shown in the dev window as a windowed
//        SBS preview (so iteration is possible without glasses plugged in).
// Head tracking lands in W2; the quad is screen-locked here.

#include <windows.h>
#include <d3d11.h>
#include <dwmapi.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

#include <imgui.h>
#include <backends/imgui_impl_win32.h>
#include <backends/imgui_impl_dx11.h>

#include <uxspace/ipc.h>
#include <uxspace/log.h>
#include <uxspace/spatial/VirtualScreen.h>
#include <uxspace/spatial/Renderer.h>
#include <uxspace/spatial/Camera.h>
#include <uxspace/spatial/Surface3D.h>
#include <uxspace/spatial/Scene.h>
#include <uxspace/spatial/GlassesOutput.h>
#include <uxspace/spatial/ScreenLayout.h>
#include <uxspace/spatial/layouts/Single.h>
#include <uxspace/tracking/HeadPose.h>
#include <uxspace/tracking/ViewMode.h>
#include <uxspace/viture/VitureTracker.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND, UINT, WPARAM, LPARAM);

namespace {

using Microsoft::WRL::ComPtr;
namespace sp = uxspace::spatial;

constexpr UINT kDevPreviewWidth  = 1280;
constexpr UINT kDevPreviewHeight = 360;
constexpr wchar_t kGlassesNameMatch[] = L"VITURE";

struct D3D {
    ComPtr<ID3D11Device>           device;
    ComPtr<ID3D11DeviceContext>    context;
    ComPtr<IDXGISwapChain1>        swap;
    ComPtr<ID3D11RenderTargetView> rtv;
};

struct DevPreview {
    ComPtr<ID3D11Texture2D>          tex;
    ComPtr<ID3D11RenderTargetView>   rtv;
    ComPtr<ID3D11ShaderResourceView> srv;
};

bool CreateD3D(HWND hwnd, D3D& d) {
    DXGI_SWAP_CHAIN_DESC1 sd{};
    sd.Format       = DXGI_FORMAT_R8G8B8A8_UNORM;
    sd.BufferUsage  = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.BufferCount  = 2;
    sd.SampleDesc   = { 1, 0 };
    sd.SwapEffect   = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    sd.AlphaMode    = DXGI_ALPHA_MODE_IGNORE;

    UINT flags = 0;
#ifdef _DEBUG
    flags |= D3D11_CREATE_DEVICE_DEBUG;
#endif
    const D3D_FEATURE_LEVEL levels[] = { D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0 };
    if (FAILED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags,
            levels, _countof(levels), D3D11_SDK_VERSION,
            &d.device, nullptr, &d.context))) {
        return false;
    }

    ComPtr<IDXGIDevice> dxgiDev;
    d.device.As(&dxgiDev);
    ComPtr<IDXGIAdapter> adapter;
    dxgiDev->GetAdapter(&adapter);
    ComPtr<IDXGIFactory2> factory;
    adapter->GetParent(IID_PPV_ARGS(&factory));
    if (FAILED(factory->CreateSwapChainForHwnd(d.device.Get(), hwnd, &sd, nullptr, nullptr, &d.swap))) {
        return false;
    }

    ComPtr<ID3D11Texture2D> back;
    d.swap->GetBuffer(0, IID_PPV_ARGS(&back));
    return SUCCEEDED(d.device->CreateRenderTargetView(back.Get(), nullptr, &d.rtv));
}

void ResizeRTV(D3D& d, UINT w, UINT h) {
    if (!d.swap) return;
    d.rtv.Reset();
    d.swap->ResizeBuffers(0, w, h, DXGI_FORMAT_UNKNOWN, 0);
    ComPtr<ID3D11Texture2D> back;
    d.swap->GetBuffer(0, IID_PPV_ARGS(&back));
    d.device->CreateRenderTargetView(back.Get(), nullptr, &d.rtv);
}

bool CreateDevPreview(ID3D11Device* device, UINT w, UINT h, DevPreview& p) {
    D3D11_TEXTURE2D_DESC td{};
    td.Width      = w;
    td.Height     = h;
    td.MipLevels  = 1;
    td.ArraySize  = 1;
    td.Format     = DXGI_FORMAT_R8G8B8A8_UNORM;
    td.SampleDesc = { 1, 0 };
    td.Usage      = D3D11_USAGE_DEFAULT;
    td.BindFlags  = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(device->CreateTexture2D(&td, nullptr, &p.tex))) return false;
    if (FAILED(device->CreateRenderTargetView(p.tex.Get(), nullptr, &p.rtv))) return false;
    if (FAILED(device->CreateShaderResourceView(p.tex.Get(), nullptr, &p.srv))) return false;
    return true;
}

D3D                  g_d3d;
sp::VirtualScreen    g_vscreen;
sp::Renderer         g_renderer;
sp::Scene            g_scene;
sp::StereoCamera     g_camera;
sp::GlassesOutput    g_glasses;
DevPreview           g_devPreview;
uxspace::viture::VitureTracker g_tracker;
uxspace::tracking::ViewMode    g_viewMode = uxspace::tracking::ViewMode::PINNED;
uxspace::spatial::layouts::Single g_layout;     // W3 will swap this for layout cycling
constexpr int                  kHotkeyRecenter = 2;
int                  g_screenBandIndex = 2;            // index into kScreenBandPresets (default = 0.90)

// --- Zoom (Win+Shift+wheel over the UxSpace virtual monitor) -------------
//
// The hook runs in the thread that installs it (the main thread). Wheel
// events are delivered via the message pump, so we share state with the
// render loop without locks. Range 1.0..4.0 (i.e. up to 400%); step per
// notch is fixed. The focal point is the cursor's normalised position on
// the captured monitor at the time of the wheel event; we cache it so the
// zoomed region stays put when the cursor wanders off the UxSpace display.
constexpr float kZoomMin       = 1.0f;
constexpr float kZoomMax       = 4.0f;
constexpr float kZoomStep      = 0.25f;
float            g_zoomLevel    = 1.0f;
DirectX::XMFLOAT2 g_zoomFocusUV = { 0.5f, 0.5f };
HHOOK            g_mouseHook    = nullptr;
HHOOK            g_keyboardHook = nullptr;

// --- W1.5: per-window pseudo-3D layering -------------------------------
//
// Back plane (Surface3D[0]) at kBackZ, identity uvRect. When pseudo-3D is
// enabled AND we're not zoomed, additional Surface3Ds are appended for
// each visible Win32 window whose rect intersects the UxSpace virtual
// monitor; each window's uvRect samples just that window's pixels from
// the same DDA texture. Z is staggered by focus-history index so the
// most recently focused window sits closest to the camera. Painter's
// algorithm: we push back-to-front so no depth buffer is needed.
// Back-plane Z/size now come from the active ScreenLayout (g_layout).
// kDepthStep is a render concern (W1.5 per-window depth stagger), keep
// it local.
constexpr float kDepthStep      = 0.015f;  // 1.5 cm per W1.5 default
constexpr int   kFocusHistoryCap = 32;
constexpr int   kHotkeyTogglePseudo3D = 1;
bool             g_pseudo3D     = false;
HWINEVENTHOOK    g_winEventHook = nullptr;
std::vector<HWND> g_focusHistory;
// Last per-frame snapshot of the layered windows, for the dev UI.
int              g_lastLayeredCount = 0;


std::vector<sp::GlassesOutput::DetectedMonitor> g_lastSeenMonitors;

// --- Driver IPC client (W3) -------------------------------------------
//
// One-shot RPC over the :shared named-pipe protocol. Each call opens
// the pipe, sends the request, reads the response, closes — keeps the
// client simple and matches the driver-side single-instance pipe.
//
// Synchronous + blocking is fine because all callers are :app's main
// thread (startup probe, UI button click). The driver responds within
// a few ms in practice.

struct IpcResult {
    bool                     ok           = false;
    uxspace::ipc::MessageType replyType   = uxspace::ipc::MessageType::Nack;
    uxspace::ipc::ErrorCode  nackCode     = uxspace::ipc::ErrorCode::Internal;
    DWORD                    win32Error   = 0;
    char                     nackMessage[128] = {};  // ASCII diagnostic from driver
};

IpcResult IpcRequest(uxspace::ipc::MessageType type,
                     const void* payload, std::uint32_t payloadBytes) {
    using namespace uxspace::ipc;
    static std::atomic<std::uint32_t> s_requestId{ 0 };

    IpcResult r;
    HANDLE pipe = CreateFileW(kPipeName, GENERIC_READ | GENERIC_WRITE,
                              0, nullptr, OPEN_EXISTING, 0, nullptr);
    if (pipe == INVALID_HANDLE_VALUE) {
        r.win32Error = GetLastError();
        return r;
    }
    DWORD pipeMode = PIPE_READMODE_MESSAGE;
    SetNamedPipeHandleState(pipe, &pipeMode, nullptr, nullptr);

    Header req{};
    req.protocol_version = kProtocolVersion;
    req.type             = type;
    req.payload_bytes    = payloadBytes;
    req.request_id       = s_requestId.fetch_add(1) + 1;

    DWORD wrote = 0;
    if (!WriteFile(pipe, &req, sizeof(req), &wrote, nullptr) ||
        wrote != sizeof(req)) {
        r.win32Error = GetLastError();
        CloseHandle(pipe);
        return r;
    }
    if (payloadBytes > 0) {
        if (!WriteFile(pipe, payload, payloadBytes, &wrote, nullptr) ||
            wrote != payloadBytes) {
            r.win32Error = GetLastError();
            CloseHandle(pipe);
            return r;
        }
    }

    Header rsp{};
    DWORD read = 0;
    if (!ReadFile(pipe, &rsp, sizeof(rsp), &read, nullptr) ||
        read != sizeof(rsp)) {
        r.win32Error = GetLastError();
        CloseHandle(pipe);
        return r;
    }
    r.replyType = rsp.type;

    if (rsp.payload_bytes == sizeof(NackPayload) && rsp.type == MessageType::Nack) {
        NackPayload np{};
        ReadFile(pipe, &np, sizeof(np), &read, nullptr);
        r.nackCode = np.code;
        // Defensive copy + NUL-terminate so we can pass to %s without
        // worrying about driver-side termination.
        std::memcpy(r.nackMessage, np.message,
                    std::min(sizeof(r.nackMessage) - 1, sizeof(np.message)));
        r.nackMessage[sizeof(r.nackMessage) - 1] = '\0';
    } else if (rsp.payload_bytes > 0) {
        std::vector<BYTE> scratch(rsp.payload_bytes);
        ReadFile(pipe, scratch.data(), rsp.payload_bytes, &read, nullptr);
    }

    CloseHandle(pipe);
    r.ok = (rsp.type != MessageType::Nack);
    return r;
}

IpcResult IpcPing() { return IpcRequest(uxspace::ipc::MessageType::Ping, nullptr, 0); }

IpcResult IpcSetMonitorCount(std::uint8_t count) {
    uxspace::ipc::SetMonitorCountPayload payload{};
    payload.count = count;
    return IpcRequest(uxspace::ipc::MessageType::SetMonitorCount,
                      &payload, sizeof(payload));
}

const char* IpcMessageName(uxspace::ipc::MessageType t) {
    using uxspace::ipc::MessageType;
    switch (t) {
        case MessageType::Ping:            return "Ping";
        case MessageType::SetMonitorCount: return "SetMonitorCount";
        case MessageType::SetMonitorMode:  return "SetMonitorMode";
        case MessageType::Pong:            return "Pong";
        case MessageType::Ack:             return "Ack";
        case MessageType::Nack:            return "Nack";
    }
    return "?";
}

const char* IpcErrorName(uxspace::ipc::ErrorCode c) {
    using uxspace::ipc::ErrorCode;
    switch (c) {
        case ErrorCode::None:             return "None";
        case ErrorCode::UnknownMessage:   return "UnknownMessage";
        case ErrorCode::VersionMismatch:  return "VersionMismatch";
        case ErrorCode::InvalidMonitorId: return "InvalidMonitorId";
        case ErrorCode::InvalidMode:      return "InvalidMode";
        case ErrorCode::TooManyMonitors:  return "TooManyMonitors";
        case ErrorCode::DriverBusy:       return "DriverBusy";
        case ErrorCode::Internal:         return "Internal";
    }
    return "?";
}

// Set when the glasses connect; consumed on the first frame the UxSpace
// virtual monitor is also live so we can place the cursor on the surface
// the wearer is actually looking at. The two events are independent, so
// we can't just SetCursorPos right after open().
bool g_pendingCenterCursorOnUxSpace = false;

// Defer tracker start by one frame so the dev window paints before the
// SDK's xr_device_provider_initialize/start (which can block for a few
// seconds on calibration init / USB negotiation).
bool g_pendingTrackerStart = true;

bool CursorInRect(POINT p, const RECT& r) {
    return p.x >= r.left && p.x < r.right && p.y >= r.top && p.y < r.bottom;
}

bool WinAndShiftHeld() {
    const bool winDown   = (GetAsyncKeyState(VK_LWIN)  & 0x8000)
                        || (GetAsyncKeyState(VK_RWIN)  & 0x8000);
    const bool shiftDown = (GetAsyncKeyState(VK_SHIFT) & 0x8000) != 0;
    return winDown && shiftDown;
}

// Forward decls — definitions live alongside the dev-UI / tracker code.
void CycleScreenBand();
void AdjustZoom(float delta);
void ToggleViewMode();
void RecenterTracker();

// Global hotkeys, all gated on Win+Shift held + a key press transition:
//   Z       → cycle screen-band preset (0.80 / 0.85 / 0.90)
//   + / =   → zoom in by kZoomStep (covers both shifted and unshifted)
//   - / _   → zoom out by kZoomStep
//   X       → toggle view mode (PINNED ↔ FREE — "tracking on/off")
//   R       → recenter: wearer's current physical pose becomes the new origin
//
// The hook consumes the keystroke when it acts, so the focused app
// doesn't receive a stray character.
LRESULT CALLBACK LowLevelKeyboardProc(int nCode, WPARAM wParam, LPARAM lParam) {
    static bool held[256] = {};
    if (nCode == HC_ACTION) {
        const auto* info = reinterpret_cast<const KBDLLHOOKSTRUCT*>(lParam);
        const bool down  = (wParam == WM_KEYDOWN || wParam == WM_SYSKEYDOWN);
        const bool up    = (wParam == WM_KEYUP   || wParam == WM_SYSKEYUP);
        const DWORD vk   = info->vkCode;
        if (vk >= std::size(held)) return CallNextHookEx(nullptr, nCode, wParam, lParam);

        if (up)   { held[vk] = false; }
        if (down && !held[vk]) {
            held[vk] = true;
            if (WinAndShiftHeld()) {
                switch (vk) {
                case 'Z':
                    uxspace::log::info("hotkey: Win+Shift+Z (cycle screen band).");
                    CycleScreenBand();
                    return 1;
                case 'X':
                    uxspace::log::info("hotkey: Win+Shift+X (toggle view mode).");
                    ToggleViewMode();
                    return 1;
                case 'R':
                    uxspace::log::info("hotkey: Win+Shift+R (recenter).");
                    RecenterTracker();
                    return 1;
                case VK_OEM_PLUS:
                case VK_ADD:
                    uxspace::log::info("hotkey: Win+Shift++ (zoom in).");
                    AdjustZoom(+kZoomStep);
                    return 1;
                case VK_OEM_MINUS:
                case VK_SUBTRACT:
                    uxspace::log::info("hotkey: Win+Shift+- (zoom out).");
                    AdjustZoom(-kZoomStep);
                    return 1;
                default: break;
                }
            }
        }
    }
    return CallNextHookEx(nullptr, nCode, wParam, lParam);
}

LRESULT CALLBACK LowLevelMouseProc(int nCode, WPARAM wParam, LPARAM lParam) {
    if (nCode == HC_ACTION && wParam == WM_MOUSEWHEEL) {
        const auto* info = reinterpret_cast<const MSLLHOOKSTRUCT*>(lParam);
        const RECT  rect = g_vscreen.desktopRect();
        const bool  inside = g_vscreen.present() && CursorInRect(info->pt, rect);
        if (inside && WinAndShiftHeld()) {
            // wheel delta is in the high word of mouseData, signed.
            const SHORT delta = static_cast<SHORT>(HIWORD(info->mouseData));
            const float notches = static_cast<float>(delta) / static_cast<float>(WHEEL_DELTA);
            g_zoomLevel = std::clamp(g_zoomLevel + notches * kZoomStep,
                                     kZoomMin, kZoomMax);

            const float u = float(info->pt.x - rect.left)
                          / float(std::max<LONG>(1, rect.right  - rect.left));
            const float v = float(info->pt.y - rect.top)
                          / float(std::max<LONG>(1, rect.bottom - rect.top));
            g_zoomFocusUV = { u, v };
            return 1; // consume the event so the focused app doesn't also scroll
        }
    }
    return CallNextHookEx(nullptr, nCode, wParam, lParam);
}

void UpdateZoomFromCursor() {
    if (!g_vscreen.present()) return;
    POINT p{};
    if (!GetCursorPos(&p)) return;
    const RECT rect = g_vscreen.desktopRect();
    if (CursorInRect(p, rect)) {
        g_zoomFocusUV = {
            float(p.x - rect.left)
                / float(std::max<LONG>(1, rect.right  - rect.left)),
            float(p.y - rect.top)
                / float(std::max<LONG>(1, rect.bottom - rect.top)),
        };
    }
}

// Updates g_zoomFocusUV from the cursor's current position when the
// cursor is on the UxSpace virtual monitor; otherwise keeps the last
// focus. Used by the keyboard zoom hotkeys (Win+Shift++ / Win+Shift+-)
// so a quick keyboard zoom-in re-centres on whatever the user is
// looking at via the cursor.
void RefreshZoomFocusFromCursor() {
    if (!g_vscreen.present()) return;
    POINT p{};
    if (!GetCursorPos(&p)) return;
    const RECT r = g_vscreen.desktopRect();
    if (!CursorInRect(p, r)) return;
    g_zoomFocusUV = {
        float(p.x - r.left) / float(std::max<LONG>(1, r.right  - r.left)),
        float(p.y - r.top ) / float(std::max<LONG>(1, r.bottom - r.top)),
    };
}

void AdjustZoom(float delta) {
    RefreshZoomFocusFromCursor();
    const float before = g_zoomLevel;
    g_zoomLevel = std::clamp(g_zoomLevel + delta, kZoomMin, kZoomMax);
    uxspace::log::info("zoom: %.2fx -> %.2fx (focus %.2f, %.2f).",
                       before, g_zoomLevel, g_zoomFocusUV.x, g_zoomFocusUV.y);
}

void ToggleViewMode() {
    using uxspace::tracking::ViewMode;
    g_viewMode = (g_viewMode == ViewMode::FREE) ? ViewMode::PINNED : ViewMode::FREE;
    uxspace::log::info("view mode: %s%s",
                       g_viewMode == ViewMode::FREE ? "FREE" : "PINNED",
                       g_viewMode == ViewMode::FREE && !g_tracker.isConnected()
                           ? " (no tracker — still rendering PINNED)" : "");
}

void RecenterTracker() {
    g_tracker.recenter();
}

DirectX::XMFLOAT4 ComputeZoomUVRect() {
    if (g_zoomLevel <= 1.0001f) return { 0.0f, 0.0f, 1.0f, 1.0f };
    const float half = 0.5f / g_zoomLevel;
    float uMin = g_zoomFocusUV.x - half;
    float vMin = g_zoomFocusUV.y - half;
    if (uMin < 0.0f)        uMin = 0.0f;
    if (vMin < 0.0f)        vMin = 0.0f;
    if (uMin + 2 * half > 1.0f) uMin = 1.0f - 2 * half;
    if (vMin + 2 * half > 1.0f) vMin = 1.0f - 2 * half;
    return { uMin, vMin, uMin + 2 * half, vMin + 2 * half };
}

// --- W1.5 helpers ------------------------------------------------------

bool RectsIntersect(const RECT& a, const RECT& b) {
    return !(a.right <= b.left || a.left >= b.right ||
             a.bottom <= b.top || a.top >= b.bottom);
}

void RememberFocusedWindow(HWND hwnd) {
    if (!hwnd) return;
    auto it = std::find(g_focusHistory.begin(), g_focusHistory.end(), hwnd);
    if (it != g_focusHistory.end()) g_focusHistory.erase(it);
    g_focusHistory.insert(g_focusHistory.begin(), hwnd);
    if (g_focusHistory.size() > kFocusHistoryCap) g_focusHistory.resize(kFocusHistoryCap);
}

void CALLBACK WinEventProc(HWINEVENTHOOK, DWORD event, HWND hwnd,
                           LONG idObject, LONG /*idChild*/,
                           DWORD /*dwEventThread*/, DWORD /*dwmsEventTime*/) {
    if (event != EVENT_SYSTEM_FOREGROUND) return;
    if (idObject != OBJID_WINDOW) return;
    RememberFocusedWindow(hwnd);
}

struct EnumeratedWindow {
    HWND hwnd;
    RECT rect; // screen coords
};

BOOL CALLBACK EnumUxSpaceWindowsProc(HWND hwnd, LPARAM lParam) {
    auto* out = reinterpret_cast<std::vector<EnumeratedWindow>*>(lParam);
    if (!IsWindowVisible(hwnd) || IsIconic(hwnd)) return TRUE;

    const LONG_PTR style   = GetWindowLongPtrW(hwnd, GWL_STYLE);
    const LONG_PTR exStyle = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
    if (exStyle & WS_EX_TOOLWINDOW)       return TRUE;  // tray icons, dialog pickers
    if (!(style & WS_VISIBLE))            return TRUE;
    // Heuristic: real app windows have a caption or a sizing border. This
    // filters out windowed popups, shell hidden surfaces, etc.
    if (!(style & (WS_CAPTION | WS_SIZEBOX))) return TRUE;

    // Prefer DWM's "extended frame bounds", which exclude the invisible
    // drop-shadow border (~7 px each side on Win10/11). GetWindowRect
    // alone includes that border, so the per-window quad would extend
    // past the visible window edges and sample whatever is behind —
    // typically the taskbar at the bottom.
    RECT r{};
    if (FAILED(DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, &r, sizeof(r)))) {
        if (!GetWindowRect(hwnd, &r)) return TRUE;
    }
    if (r.right - r.left < 50 || r.bottom - r.top < 50) return TRUE;

    const RECT ux = g_vscreen.desktopRect();
    if (!RectsIntersect(r, ux)) return TRUE;

    out->push_back({ hwnd, r });
    return TRUE;
}

// Returns the work-area rect for the UxSpace virtual monitor — the
// desktop rect minus the taskbar. Without this clip, a window whose
// geometric rect extends through the taskbar overlay (Windows reports
// `GetWindowRect` unaffected by the taskbar) would have its per-window
// quad sample taskbar pixels at the bottom, painting the taskbar twice
// in the 3D scene.
RECT UxSpaceWorkArea() {
    const RECT ux = g_vscreen.desktopRect();
    HMONITOR mon = MonitorFromRect(&ux, MONITOR_DEFAULTTONULL);
    MONITORINFO mi{ sizeof(mi) };
    if (mon && GetMonitorInfoW(mon, &mi)) return mi.rcWork;
    return ux;
}

RECT IntersectRects(const RECT& a, const RECT& b) {
    RECT r;
    r.left   = std::max(a.left,   b.left);
    r.top    = std::max(a.top,    b.top);
    r.right  = std::min(a.right,  b.right);
    r.bottom = std::min(a.bottom, b.bottom);
    return r;
}

// Sorts enumerated windows so the most-recently-focused window appears
// first. Windows not yet seen in g_focusHistory go to the back in EnumWindows
// order. Used by both UpdateScene (for depth ordering) and the dev UI's
// focus-overlay (for eyeballing focus-history correctness).
std::vector<EnumeratedWindow>
OrderWindowsByFocusHistory(std::vector<EnumeratedWindow> windows) {
    std::vector<EnumeratedWindow> ordered;
    ordered.reserve(windows.size());
    std::vector<bool> taken(windows.size(), false);
    for (HWND f : g_focusHistory) {
        for (size_t i = 0; i < windows.size(); ++i) {
            if (!taken[i] && windows[i].hwnd == f) {
                ordered.push_back(windows[i]);
                taken[i] = true;
                break;
            }
        }
    }
    for (size_t i = 0; i < windows.size(); ++i) {
        if (!taken[i]) ordered.push_back(windows[i]);
    }
    return ordered;
}

std::vector<EnumeratedWindow> EnumerateUxSpaceWindows() {
    std::vector<EnumeratedWindow> r;
    if (!g_vscreen.present()) return r;
    EnumWindows(EnumUxSpaceWindowsProc, reinterpret_cast<LPARAM>(&r));
    return r;
}

// Rebuilds g_scene.surfaces each frame.
//
// surfaces[0] is always the back plane (Surface3D occupying the whole
// captured monitor at kBackZ). Its uvRect carries the zoom state. When
// pseudo-3D is enabled and we're not zoomed, additional per-window quads
// are appended (back-to-front) so the most-recently-focused window draws
// last and sits closest to the camera.
void UpdateScene() {
    g_scene.surfaces.clear();

    // Slot-0 placement from the active ScreenLayout strategy. For W2 the
    // layout is always Single; W3 swaps in ArcOfThree / Stack and this
    // loop iterates monitorCount() back planes (one per virtual monitor).
    const sp::ScreenPlacement back0 = g_layout.placement(0);

    sp::Surface3D backPlane;
    backPlane.position = back0.position;
    backPlane.size     = back0.size;
    backPlane.uvRect   = ComputeZoomUVRect();
    backPlane.texture  = g_vscreen.srv();
    g_scene.surfaces.push_back(backPlane);

    const bool layered = g_pseudo3D
                      && g_zoomLevel <= 1.0001f
                      && g_vscreen.present();
    if (!layered) { g_lastLayeredCount = 0; return; }

    auto windows = EnumerateUxSpaceWindows();
    if (windows.empty()) { g_lastLayeredCount = 0; return; }

    auto ordered = OrderWindowsByFocusHistory(std::move(windows));

    const RECT mon  = g_vscreen.desktopRect();
    const RECT work = UxSpaceWorkArea();
    const float mw  = float(std::max<LONG>(1, mon.right  - mon.left));
    const float mh  = float(std::max<LONG>(1, mon.bottom - mon.top));
    const int   N   = static_cast<int>(ordered.size());

    // Painter's algorithm: push farthest-from-camera first. ordered[0] is
    // most-recent (closest to camera, drawn last); ordered[N-1] is oldest
    // (closest to back plane, drawn first).
    for (int idx = N - 1; idx >= 0; --idx) {
        const auto& w = ordered[idx];
        // Clip to the work area so the per-window quad doesn't include
        // the taskbar overlay region (which still gets drawn by the back
        // plane underneath).
        const RECT clipped = IntersectRects(w.rect, work);
        if (clipped.right <= clipped.left || clipped.bottom <= clipped.top) continue;
        const float left   = std::clamp(float(clipped.left   - mon.left) / mw, 0.0f, 1.0f);
        const float top    = std::clamp(float(clipped.top    - mon.top ) / mh, 0.0f, 1.0f);
        const float right  = std::clamp(float(clipped.right  - mon.left) / mw, 0.0f, 1.0f);
        const float bottom = std::clamp(float(clipped.bottom - mon.top ) / mh, 0.0f, 1.0f);
        if (right <= left || bottom <= top) continue;

        const float cx  = (left + right ) * 0.5f;
        const float cy  = (top  + bottom) * 0.5f;
        // Per-window quads inherit the active layout's slot-0 dimensions
        // so they line up with the back plane (this also keeps the
        // pseudo-3D feature future-compatible with non-Single layouts).
        sp::Surface3D q;
        q.position = { back0.position.x + (cx  - 0.5f) * back0.size.x,
                       back0.position.y + (0.5f - cy ) * back0.size.y,
                       back0.position.z - kDepthStep * float(N - idx) };
        q.size     = { (right - left) * back0.size.x,
                       (bottom - top) * back0.size.y };
        q.uvRect   = { left, top, right, bottom };
        q.texture  = g_vscreen.srv();
        g_scene.surfaces.push_back(q);
    }
    g_lastLayeredCount = N;
}

void TryOpenGlasses() {
    sp::GlassesOutput::DetectedMonitor m;
    if (!sp::GlassesOutput::find(kGlassesNameMatch, m, &g_lastSeenMonitors)) {
        uxspace::log::info("glasses: TryOpenGlasses — no matching monitor; will retry on next display change.");
        return;
    }
    if (g_glasses.open(g_d3d.device.Get(), m)) {
        g_pendingCenterCursorOnUxSpace = true;
        uxspace::log::info("glasses: TryOpenGlasses — opened, will centre cursor on UxSpace on next vscreen tick.");
    } else {
        uxspace::log::warn("glasses: TryOpenGlasses — match found but open() returned false.");
    }
}

// On display topology changes (hotplug, mode change, screen lock/unlock):
// close any existing glasses swap chain and re-open. Closing-first is
// what stops the orphaned WS_POPUP from covering the desktop when the
// output goes away, AND it ensures we pick up new modes when the EDID
// re-publishes (e.g. after :viture toggles 3D and 3840xN SBS modes
// become available where only 1920xN mono existed before).
void RefreshGlassesState() {
    std::vector<sp::GlassesOutput::DetectedMonitor> active;
    sp::GlassesOutput::DetectedMonitor dummy;
    sp::GlassesOutput::find(kGlassesNameMatch, dummy, &active);
    g_lastSeenMonitors = std::move(active);

    g_glasses.close();
    TryOpenGlasses();
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (ImGui_ImplWin32_WndProcHandler(hwnd, msg, wp, lp)) return true;

    switch (msg) {
    case WM_SIZE:
        if (g_d3d.device && wp != SIZE_MINIMIZED) {
            ResizeRTV(g_d3d, LOWORD(lp), HIWORD(lp));
        }
        return 0;
    case WM_DISPLAYCHANGE:
        // Display topology changed (hotplug, mode change, etc.).
        uxspace::log::info("event: WM_DISPLAYCHANGE (depth=%u, size=%ux%u).",
                           static_cast<unsigned>(wp), LOWORD(lp), HIWORD(lp));
        RefreshGlassesState();
        return 0;
    case WM_HOTKEY:
        if (wp == kHotkeyTogglePseudo3D) {
            g_pseudo3D = !g_pseudo3D;
            uxspace::log::info("hotkey: Win+Shift+D pseudo-3D -> %s.",
                               g_pseudo3D ? "ON" : "off");
        }
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

void RenderStereoTo(ID3D11RenderTargetView* rtv,
                    UINT w,
                    UINT h,
                    bool stereo) {
    const float clear[4] = { 0.0f, 0.0f, 0.0f, 1.0f };
    g_d3d.context->OMSetRenderTargets(1, &rtv, nullptr);
    g_d3d.context->ClearRenderTargetView(rtv, clear);
    if (stereo) {
        g_scene.renderStereo(g_d3d.context.Get(), g_renderer, g_camera, w, h);
    } else {
        g_scene.renderMono(g_d3d.context.Get(), g_renderer, g_camera, w, h);
    }
}

void CycleScreenBand() {
    g_screenBandIndex = (g_screenBandIndex + 1) % static_cast<int>(std::size(sp::kScreenBandPresets));
    g_scene.screenBand = sp::kScreenBandPresets[g_screenBandIndex];
}

// --- Dev UI: Hotkeys panel ---------------------------------------------
//
// Single source of truth for the bindings the app responds to. Each new
// hotkey added in later milestones (recenter in W2, view-mode toggle in
// W4, etc.) means adding one row here.
void DrawHotkeysWindow() {
    ImGui::SetNextWindowPos(ImVec2(12, 482),  ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowSize(ImVec2(520, 150), ImGuiCond_FirstUseEver);
    ImGui::Begin("Hotkeys");

    if (ImGui::BeginTable("hotkeys", 3,
            ImGuiTableFlags_Borders | ImGuiTableFlags_RowBg | ImGuiTableFlags_SizingFixedFit)) {
        ImGui::TableSetupColumn("Keys",   ImGuiTableColumnFlags_WidthFixed, 150);
        ImGui::TableSetupColumn("Scope",  ImGuiTableColumnFlags_WidthFixed, 60);
        ImGui::TableSetupColumn("Action", ImGuiTableColumnFlags_WidthStretch);
        ImGui::TableHeadersRow();

        auto row = [](const char* keys, const char* scope, const char* action) {
            ImGui::TableNextRow();
            ImGui::TableNextColumn(); ImGui::TextUnformatted(keys);
            ImGui::TableNextColumn(); ImGui::TextUnformatted(scope);
            ImGui::TableNextColumn(); ImGui::TextUnformatted(action);
        };
        row("Win+Shift+Wheel", "global", "Zoom over the UxSpace virtual monitor (1.0x-4.0x).");
        row("Win+Shift++",     "global", "Zoom in by 0.25x (keyboard alternative).");
        row("Win+Shift+-",     "global", "Zoom out by 0.25x.");
        row("Win+Shift+Z",     "global", "Cycle screen band (0.80 / 0.85 / 0.90).");
        row("Win+Shift+D",     "global", "Toggle pseudo-3D per-window layering.");
        row("Win+Shift+X",     "global", "Toggle view mode: PINNED (head-locked) <-> FREE (world-locked).");
        row("Win+Shift+R",     "global", "Recenter: wearer's current physical pose becomes the new origin.");
        ImGui::EndTable();
    }
    ImGui::End();
}

// --- Dev UI: Display layout window -------------------------------------
//
// Top-down schematic of every monitor in its desktop coordinates. Colour-
// codes the UxSpace virtual monitor and the detected Viture output so a
// glance reveals which is which. Optional checkbox overlays the focus-
// ordered window rects on top of the UxSpace monitor — useful for
// debugging the W1.5 focus-history.

struct EnumeratedMonitor {
    RECT rect;
    std::wstring deviceName;
    UINT  width;
    UINT  height;
};

BOOL CALLBACK EnumMonitorsProc_(HMONITOR hMon, HDC, LPRECT, LPARAM lParam) {
    auto* out = reinterpret_cast<std::vector<EnumeratedMonitor>*>(lParam);
    MONITORINFOEXW mi{};
    mi.cbSize = sizeof(mi);
    if (GetMonitorInfoW(hMon, &mi)) {
        out->push_back({
            mi.rcMonitor,
            mi.szDevice,
            static_cast<UINT>(mi.rcMonitor.right  - mi.rcMonitor.left),
            static_cast<UINT>(mi.rcMonitor.bottom - mi.rcMonitor.top),
        });
    }
    return TRUE;
}

void DrawDisplayLayoutWindow() {
    static bool s_showFocusOverlay = false;

    ImGui::SetNextWindowPos(ImVec2(12, 642),  ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowSize(ImVec2(520, 240), ImGuiCond_FirstUseEver);
    ImGui::Begin("Display layout");

    ImGui::Checkbox("Show focus-ordered windows on UxSpace", &s_showFocusOverlay);
    ImGui::Separator();

    std::vector<EnumeratedMonitor> monitors;
    EnumDisplayMonitors(nullptr, nullptr, EnumMonitorsProc_,
                        reinterpret_cast<LPARAM>(&monitors));
    if (monitors.empty()) { ImGui::Text("No monitors detected."); ImGui::End(); return; }

    LONG minX = monitors[0].rect.left,   minY = monitors[0].rect.top;
    LONG maxX = monitors[0].rect.right,  maxY = monitors[0].rect.bottom;
    for (const auto& m : monitors) {
        minX = std::min(minX, m.rect.left);
        minY = std::min(minY, m.rect.top);
        maxX = std::max(maxX, m.rect.right);
        maxY = std::max(maxY, m.rect.bottom);
    }
    const float boxW = float(std::max<LONG>(1, maxX - minX));
    const float boxH = float(std::max<LONG>(1, maxY - minY));

    const ImVec2 avail  = ImGui::GetContentRegionAvail();
    const float  scale  = std::min(avail.x / boxW, avail.y / boxH) * 0.95f;
    const ImVec2 origin = ImGui::GetCursorScreenPos();
    const ImVec2 panelOrigin{
        origin.x + (avail.x - boxW * scale) * 0.5f,
        origin.y + (avail.y - boxH * scale) * 0.5f,
    };
    auto toScreen = [&](LONG x, LONG y) {
        return ImVec2(panelOrigin.x + (x - minX) * scale,
                      panelOrigin.y + (y - minY) * scale);
    };

    const bool        haveUx     = g_vscreen.present();
    const RECT        uxRect     = haveUx ? g_vscreen.desktopRect() : RECT{};
    const std::wstring vitName    = g_glasses.opened() ? g_glasses.deviceName() : std::wstring{};
    auto* draw = ImGui::GetWindowDrawList();

    for (const auto& m : monitors) {
        const bool isUx = haveUx
            && m.rect.left == uxRect.left && m.rect.top == uxRect.top
            && m.rect.right == uxRect.right && m.rect.bottom == uxRect.bottom;
        const bool isViture = !vitName.empty() && m.deviceName == vitName;

        ImU32 fill, edge;
        if (isUx) {
            fill = IM_COL32( 40, 100, 130, 220);
            edge = IM_COL32(120, 200, 255, 255);
        } else if (isViture) {
            fill = IM_COL32(120,  80,  30, 220);
            edge = IM_COL32(255, 180,  80, 255);
        } else {
            fill = IM_COL32( 55,  55,  55, 220);
            edge = IM_COL32(160, 160, 160, 255);
        }
        const ImVec2 tl = toScreen(m.rect.left, m.rect.top);
        const ImVec2 br = toScreen(m.rect.right, m.rect.bottom);
        draw->AddRectFilled(tl, br, fill);
        draw->AddRect(tl, br, edge, 0.0f, 0, 2.0f);

        char nameUtf8[64];
        WideCharToMultiByte(CP_UTF8, 0, m.deviceName.c_str(), -1,
                            nameUtf8, sizeof(nameUtf8), nullptr, nullptr);
        char label[128];
        const char* tag = isUx ? " [UxSpace]" : (isViture ? " [Viture]" : "");
        snprintf(label, sizeof(label), "%s%s\n%ux%u",
                 nameUtf8, tag, m.width, m.height);
        draw->AddText(ImVec2(tl.x + 5, tl.y + 5),
                      IM_COL32(255, 255, 255, 255), label);
    }

    if (s_showFocusOverlay && haveUx) {
        auto ordered = OrderWindowsByFocusHistory(EnumerateUxSpaceWindows());
        const int N = static_cast<int>(ordered.size());
        for (int i = 0; i < N; ++i) {
            const ImVec2 tl = toScreen(ordered[i].rect.left,  ordered[i].rect.top);
            const ImVec2 br = toScreen(ordered[i].rect.right, ordered[i].rect.bottom);
            // Most recent = yellow; older = orange -> red.
            const float t = (N > 1) ? float(i) / float(N - 1) : 0.0f;
            const ImU32 col = IM_COL32(255, int(255 * (1.0f - t * 0.7f)), 0, 230);
            draw->AddRect(tl, br, col, 0.0f, 0, 2.0f);
            char rank[8]; snprintf(rank, sizeof(rank), "%d", i);
            draw->AddText(ImVec2(tl.x + 3, tl.y + 3),
                          IM_COL32(255, 255, 255, 255), rank);
        }
    }

    POINT cursor{};
    if (GetCursorPos(&cursor)) {
        const ImVec2 c = toScreen(cursor.x, cursor.y);
        draw->AddCircleFilled(c, 4.0f, IM_COL32(255,  80,  80, 255));
        draw->AddCircle      (c, 5.0f, IM_COL32(  0,   0,   0, 255), 0, 1.5f);
    }

    ImGui::Dummy(ImVec2(avail.x, avail.y));
    ImGui::End();
}

void DrawDevUI(HWND devWnd) {
    ImGui_ImplDX11_NewFrame();
    ImGui_ImplWin32_NewFrame();
    ImGui::NewFrame();

    ImGui::SetNextWindowPos(ImVec2(12, 12),    ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowSize(ImVec2(520, 460), ImGuiCond_FirstUseEver);
    ImGui::Begin("UxSpace");

    // Screen-band picker first — most-used control during W1 tuning.
    ImGui::TextUnformatted("Screen band (Win+Shift+Z to cycle):");
    for (int i = 0; i < static_cast<int>(std::size(sp::kScreenBandPresets)); ++i) {
        if (i > 0) ImGui::SameLine();
        char label[32];
        snprintf(label, sizeof(label), "%.2f##band%d", sp::kScreenBandPresets[i], i);
        const bool active = (i == g_screenBandIndex);
        if (active) ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.20f, 0.55f, 0.90f, 1.0f));
        if (ImGui::Button(label, ImVec2(80, 32))) {
            g_screenBandIndex   = i;
            g_scene.screenBand  = sp::kScreenBandPresets[i];
        }
        if (active) ImGui::PopStyleColor();
    }
    ImGui::SameLine();
    ImGui::TextDisabled("(current: %.2f)", g_scene.screenBand);
    ImGui::Separator();

    // Zoom (Win+Shift+wheel over the UxSpace virtual monitor).
    ImGui::Text("Zoom: %.2fx", g_zoomLevel);
    if (g_zoomLevel > 1.0001f) {
        ImGui::SameLine();
        ImGui::TextDisabled("focus (%.2f, %.2f)", g_zoomFocusUV.x, g_zoomFocusUV.y);
        ImGui::SameLine();
        if (ImGui::SmallButton("Reset")) g_zoomLevel = 1.0f;
    } else {
        ImGui::SameLine();
        ImGui::TextDisabled("Win+Shift+wheel over the UxSpace display to zoom (max 4.00x)");
    }
    ImGui::Separator();

    ImGui::Text("W1 \xe2\x80\x94 stereo output to Viture + dev preview");
    ImGui::Text("IPC protocol version: %u (max monitors: %u)",
                uxspace::ipc::kProtocolVersion, uxspace::ipc::kMaxMonitors);
    ImGui::Separator();

    // Capture status
    if (g_vscreen.present()) {
        ImGui::Text("Capture: %ls  %ux%u  frames=%llu",
                    g_vscreen.monitorName().c_str(),
                    g_vscreen.width(), g_vscreen.height(),
                    static_cast<unsigned long long>(g_vscreen.frameCount()));
    } else {
        ImGui::TextColored(ImVec4(1.0f, 0.55f, 0.55f, 1.0f),
            "No UxSpace virtual monitor found.");
        ImGui::TextDisabled("Install the driver via driver/scripts/Install-Driver.ps1");
    }
    if (FAILED(g_vscreen.lastError()) && g_vscreen.lastError() != E_FAIL) {
        ImGui::TextColored(ImVec4(1.0f, 0.55f, 0.55f, 1.0f),
            "DDA error: 0x%08X", static_cast<unsigned>(g_vscreen.lastError()));
    }
    ImGui::Separator();

    // Glasses status
    if (g_glasses.opened()) {
        const bool stereo = g_glasses.isStereoMode();
        ImGui::Text("Glasses: %ls (%ls)",
                    g_glasses.friendlyName().c_str(), g_glasses.deviceName().c_str());
        ImGui::Text("Mode: %ux%u @ %.2f Hz  (%s)",
                    g_glasses.width(), g_glasses.height(),
                    g_glasses.refreshDen() ? double(g_glasses.refreshNum()) / g_glasses.refreshDen() : 0.0,
                    stereo ? "SBS stereo" : "mono \xe2\x80\x94 :viture 3D toggle pending (W2)");
        if (ImGui::Button("Close glasses output")) {
            g_glasses.close();
        }
    } else {
        ImGui::TextColored(ImVec4(1.0f, 0.85f, 0.4f, 1.0f),
            "No \"%ls\" display detected.", kGlassesNameMatch);
        if (ImGui::Button("Rescan + open glasses")) {
            TryOpenGlasses();
        }
        if (!g_lastSeenMonitors.empty()) {
            ImGui::TextDisabled("Detected monitors:");
            for (const auto& m : g_lastSeenMonitors) {
                ImGui::BulletText("%ls  (%ls)", m.friendlyName.c_str(), m.deviceName.c_str());
            }
        }
    }
    ImGui::Separator();

    // Head tracker (W2)
    using uxspace::tracking::ViewMode;
    if (g_tracker.isConnected()) {
        ImGui::Text("Tracker: %s  (%s)",
                    g_tracker.deviceName().c_str(),
                    g_tracker.supportsTranslation() ? "6DOF" : "3DOF");
        ImGui::SameLine();
        if (ImGui::SmallButton("Stop###tracker")) g_tracker.stop();

        const auto& p = g_camera.headPose;
        ImGui::Text("View: %s   (Win+Shift+X to toggle, Win+Shift+R to recenter)",
                    g_camera.mode == ViewMode::FREE ? "FREE" : "PINNED");
        ImGui::Text("Pose: pos=[%+.3f %+.3f %+.3f] q=[%+.3f %+.3f %+.3f %+.3f] valid=%d",
                    p.position.x, p.position.y, p.position.z,
                    p.orientation.x, p.orientation.y, p.orientation.z, p.orientation.w,
                    p.valid ? 1 : 0);
    } else {
        ImGui::TextColored(ImVec4(1.0f, 0.85f, 0.4f, 1.0f),
                           "Tracker: not connected.");
        ImGui::SameLine();
        if (ImGui::SmallButton("Connect###tracker")) g_tracker.start();
        ImGui::TextDisabled("View: PINNED (no tracker)");
    }
    ImGui::Separator();

    // Driver IPC + monitor count (W3)
    ImGui::Text("Driver IPC:");
    ImGui::SameLine();
    if (ImGui::SmallButton("Ping")) {
        const IpcResult r = IpcPing();
        if (r.ok) uxspace::log::info("ipc: manual Ping -> %s.", IpcMessageName(r.replyType));
        else      uxspace::log::warn("ipc: manual Ping failed (win32=%lu, type=%s/%s: %s).",
                                     r.win32Error,
                                     IpcMessageName(r.replyType),
                                     IpcErrorName(r.nackCode),
                                     r.nackMessage);
    }
    ImGui::SameLine();
    ImGui::TextDisabled("Monitor count:");
    for (std::uint8_t n = 1; n <= uxspace::ipc::kMaxMonitors; ++n) {
        ImGui::SameLine();
        char label[16]; snprintf(label, sizeof(label), "%u##mcount", n);
        if (ImGui::SmallButton(label)) {
            const IpcResult r = IpcSetMonitorCount(n);
            if (r.replyType == uxspace::ipc::MessageType::Nack) {
                uxspace::log::warn("ipc: SetMonitorCount(%u) -> Nack/%s: %s", n,
                                   IpcErrorName(r.nackCode),
                                   r.nackMessage);
            } else {
                uxspace::log::info("ipc: SetMonitorCount(%u) -> %s.", n,
                                   IpcMessageName(r.replyType));
            }
        }
    }
    ImGui::Separator();

    // Pseudo-3D layering (W1.5)
    ImGui::Text("Pseudo-3D: %s", g_pseudo3D ? "ON" : "off");
    ImGui::SameLine();
    if (ImGui::Button(g_pseudo3D ? "Disable###p3d" : "Enable###p3d")) {
        g_pseudo3D = !g_pseudo3D;
    }
    ImGui::SameLine();
    ImGui::TextDisabled("Win+Shift+D toggles globally");
    if (g_pseudo3D) {
        if (g_zoomLevel > 1.0001f) {
            ImGui::TextColored(ImVec4(1.0f, 0.85f, 0.4f, 1.0f),
                "Zoom active -> collapsed to single back plane.");
        } else {
            ImGui::TextDisabled("Layered windows: %d (focus history: %zu)",
                                g_lastLayeredCount, g_focusHistory.size());
        }
    }
    ImGui::Separator();

    ImGui::End();

    if (g_devPreview.srv) {
        ImGui::SetNextWindowPos(ImVec2(548, 12),    ImGuiCond_FirstUseEver);
        ImGui::SetNextWindowSize(ImVec2(840, 280),  ImGuiCond_FirstUseEver);
        ImGui::Begin("Stereo preview (windowed SBS)");
        ImVec2 avail = ImGui::GetContentRegionAvail();
        const float srcAspect = float(kDevPreviewWidth) / float(kDevPreviewHeight);
        float w = avail.x;
        float h = w / srcAspect;
        if (h > avail.y) { h = avail.y; w = h * srcAspect; }
        ImGui::Image(reinterpret_cast<ImTextureID>(g_devPreview.srv.Get()), ImVec2(w, h));
        ImGui::End();
    }

    if (g_vscreen.present() && g_vscreen.srv()) {
        ImGui::SetNextWindowPos(ImVec2(548, 308),   ImGuiCond_FirstUseEver);
        ImGui::SetNextWindowSize(ImVec2(840, 560),  ImGuiCond_FirstUseEver);
        ImGui::Begin("Capture preview");
        ImVec2 avail = ImGui::GetContentRegionAvail();
        const float srcAspect = float(g_vscreen.width()) / float(g_vscreen.height());
        float w = avail.x;
        float h = w / srcAspect;
        if (h > avail.y) { h = avail.y; w = h * srcAspect; }
        ImGui::Image(reinterpret_cast<ImTextureID>(g_vscreen.srv()), ImVec2(w, h));
        ImGui::End();
    }

    DrawHotkeysWindow();
    DrawDisplayLayoutWindow();

    ImGui::Render();

    const float clear[4] = { 0.07f, 0.08f, 0.10f, 1.0f };
    g_d3d.context->OMSetRenderTargets(1, g_d3d.rtv.GetAddressOf(), nullptr);
    g_d3d.context->ClearRenderTargetView(g_d3d.rtv.Get(), clear);

    RECT cr{};
    GetClientRect(devWnd, &cr);
    D3D11_VIEWPORT vp{};
    vp.Width    = float(cr.right - cr.left);
    vp.Height   = float(cr.bottom - cr.top);
    vp.MaxDepth = 1.0f;
    g_d3d.context->RSSetViewports(1, &vp);

    ImGui_ImplDX11_RenderDrawData(ImGui::GetDrawData());
    g_d3d.swap->Present(1, 0);
}

} // namespace

int APIENTRY wWinMain(HINSTANCE hInst, HINSTANCE, LPWSTR, int) {
    // Open the diagnostic log first thing — every subsequent step
    // (D3D init, glasses discovery, tracker probe) records here, so a
    // user reporting a problem can just ship %TEMP%\UxSpace-app.log.
    {
        wchar_t tempDir[MAX_PATH] = {};
        wchar_t logPath[MAX_PATH] = {};
        if (GetTempPathW(MAX_PATH, tempDir) > 0) {
            swprintf_s(logPath, L"%sUxSpace-app.log", tempDir);
            uxspace::log::init(logPath);
        }
        SYSTEMTIME st;
        GetLocalTime(&st);
        uxspace::log::info("UxSpace boot at %04u-%02u-%02u %02u:%02u:%02u.",
                           st.wYear, st.wMonth, st.wDay,
                           st.wHour, st.wMinute, st.wSecond);
        uxspace::log::info("Log file: %ls", logPath);
    }

    WNDCLASSEXW wc{ sizeof(wc) };
    wc.style         = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc   = WndProc;
    wc.hInstance     = hInst;
    wc.hCursor       = LoadCursor(nullptr, IDC_ARROW);
    wc.lpszClassName = L"UxSpaceAppWindow";
    RegisterClassExW(&wc);

    HWND hwnd = CreateWindowExW(0, wc.lpszClassName, L"UxSpace",
        WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 1400, 900,
        nullptr, nullptr, hInst, nullptr);
    if (!hwnd) return 1;

    if (!CreateD3D(hwnd, g_d3d)) {
        MessageBoxW(nullptr, L"Failed to create D3D11 device + swap chain.", L"UxSpace", MB_ICONERROR);
        return 1;
    }

    if (!g_renderer.init(g_d3d.device.Get())) {
        MessageBoxW(nullptr, L"Failed to initialise spatial::Renderer (shader compile?).",
                    L"UxSpace", MB_ICONERROR);
        return 1;
    }
    CreateDevPreview(g_d3d.device.Get(), kDevPreviewWidth, kDevPreviewHeight, g_devPreview);

    // Surfaces are rebuilt every frame by UpdateScene(): back plane + (if
    // pseudo-3D is on and we're not zoomed) per-window quads. Just seed
    // the screen-band preset here.
    g_scene.screenBand = sp::kScreenBandPresets[g_screenBandIndex];

    // Try the glasses up-front; user can rescan via the UI later.
    TryOpenGlasses();

    // W3: probe the driver's named-pipe control channel. Retried a few
    // times with backoff because the driver's pipe-server thread starts
    // shortly after WUDFHost loads us — there's a tiny race window
    // where the pipe doesn't exist yet on a fresh boot.
    {
        IpcResult ping;
        for (int attempt = 0; attempt < 8; ++attempt) {
            ping = IpcPing();
            if (ping.ok || ping.win32Error == 0) break;
            Sleep(250);
        }
        if (ping.ok && ping.replyType == uxspace::ipc::MessageType::Pong) {
            uxspace::log::info("ipc: driver pipe Ping OK (Pong received).");
        } else if (ping.win32Error != 0) {
            uxspace::log::warn("ipc: driver pipe Ping never came up after retries "
                               "(last err=%lu). Driver may be pre-W3 or the pipe "
                               "is being held by another process.",
                               ping.win32Error);
        } else {
            uxspace::log::warn("ipc: driver pipe responded with %s/%s: %s",
                               IpcMessageName(ping.replyType),
                               IpcErrorName(ping.nackCode),
                               ping.nackMessage);
        }
    }

    // Global low-level hooks for the zoom gestures:
    //   - Win+Shift+wheel over the UxSpace display: continuous zoom (mouse hook)
    //   - Win+Shift+Z:                              cycle screen band (keyboard hook)
    // Both live for the lifetime of the app.
    g_mouseHook    = SetWindowsHookExW(WH_MOUSE_LL,    &LowLevelMouseProc,
                                       GetModuleHandleW(nullptr), 0);
    g_keyboardHook = SetWindowsHookExW(WH_KEYBOARD_LL, &LowLevelKeyboardProc,
                                       GetModuleHandleW(nullptr), 0);

    // W1.5: focus-history listener for per-window depth ordering, and
    // Win+Shift+D global toggle for pseudo-3D layering. RegisterHotKey is
    // cleaner than a low-level keyboard hook for a non-wheel chord — the
    // OS delivers WM_HOTKEY to our window without the keystroke ever
    // reaching another app.
    g_winEventHook = SetWinEventHook(EVENT_SYSTEM_FOREGROUND,
                                     EVENT_SYSTEM_FOREGROUND,
                                     nullptr, &WinEventProc, 0, 0,
                                     WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNPROCESS);
    RememberFocusedWindow(GetForegroundWindow()); // seed so first frame has order
    RegisterHotKey(hwnd, kHotkeyTogglePseudo3D, MOD_WIN | MOD_SHIFT, 'D');

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGui::StyleColorsDark();
    ImGui_ImplWin32_Init(hwnd);
    ImGui_ImplDX11_Init(g_d3d.device.Get(), g_d3d.context.Get());

    ShowWindow(hwnd, SW_SHOWDEFAULT);
    UpdateWindow(hwnd);

    bool running = true;
    while (running) {
        MSG msg;
        while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
            if (msg.message == WM_QUIT) running = false;
        }
        if (!running) break;

        g_vscreen.tick(g_d3d.device.Get(), g_d3d.context.Get());

        // Bring up the head tracker on the second frame so the window has
        // painted once before we block in the SDK. The 3D-mode toggle
        // performed by start() re-publishes the EDID with SBS modes,
        // which triggers WM_DISPLAYCHANGE → RefreshGlassesState reopens
        // the glasses on the new wider mode automatically.
        if (g_pendingTrackerStart) {
            uxspace::log::info("main: invoking deferred tracker.start() (frame 2).");
            const bool ok = g_tracker.start();
            uxspace::log::info("main: tracker.start() returned %s; connected=%d.",
                               ok ? "true" : "false",
                               g_tracker.isConnected() ? 1 : 0);
            // Default to FREE on first successful connect so the wearer
            // gets head tracking out of the box; explicit Win+Shift+X
            // toggles back to PINNED.
            if (ok) g_viewMode = uxspace::tracking::ViewMode::FREE;
            g_pendingTrackerStart = false;
        }

        // If the glasses connected before the UxSpace virtual monitor was
        // captured, drop the cursor onto the captured monitor as soon as
        // both are live. This is what the wearer sees through the glasses,
        // so it's the right surface to land on.
        if (g_pendingCenterCursorOnUxSpace && g_vscreen.present()) {
            const RECT r = g_vscreen.desktopRect();
            SetCursorPos((r.left + r.right) / 2, (r.top + r.bottom) / 2);
            g_pendingCenterCursorOnUxSpace = false;
        }

        // Refresh the zoom focus from the cursor's current position; the
        // wheel hook only updates the focus on a wheel event, so this keeps
        // it tracking when the user moves the cursor around at a fixed
        // zoom. Then rebuild the whole scene: back plane (with zoom-driven
        // uvRect) + per-window quads if pseudo-3D is active. VirtualScreen
        // recycles its SRV on ACCESS_LOST, so the pointer is only valid
        // this frame.
        UpdateZoomFromCursor();

        // Push the tracker's latest head pose into the camera. If the
        // user has requested FREE but the tracker isn't producing,
        // render PINNED anyway — the user's intent stays sticky, the
        // visible behaviour falls back gracefully.
        {
            using uxspace::tracking::ViewMode;
            g_camera.headPose = g_tracker.latestPose();
            g_camera.mode     = (g_viewMode == ViewMode::FREE && g_tracker.isConnected())
                                ? ViewMode::FREE : ViewMode::PINNED;
        }

        UpdateScene();

        // Render to glasses (if open). Stereo or mono depending on whether
        // the framebuffer reports an SBS-aspect mode; see GlassesOutput.h.
        if (g_glasses.opened()) {
            const bool stereo = g_glasses.isStereoMode();
            RenderStereoTo(g_glasses.rtv(), g_glasses.width(), g_glasses.height(), stereo);
            g_glasses.swap()->Present(1, 0);
        }

        // Render to the dev SBS preview (always — useful even without glasses).
        RenderStereoTo(g_devPreview.rtv.Get(), kDevPreviewWidth, kDevPreviewHeight, /*stereo*/true);

        DrawDevUI(hwnd);
    }

    UnregisterHotKey(hwnd, kHotkeyTogglePseudo3D);
    if (g_winEventHook) { UnhookWinEvent(g_winEventHook);      g_winEventHook = nullptr; }
    if (g_mouseHook)    { UnhookWindowsHookEx(g_mouseHook);    g_mouseHook    = nullptr; }
    if (g_keyboardHook) { UnhookWindowsHookEx(g_keyboardHook); g_keyboardHook = nullptr; }
    g_tracker.stop();
    g_glasses.close();

    ImGui_ImplDX11_Shutdown();
    ImGui_ImplWin32_Shutdown();
    ImGui::DestroyContext();
    uxspace::log::info("UxSpace exit.");
    uxspace::log::shutdown();
    return 0;
}
