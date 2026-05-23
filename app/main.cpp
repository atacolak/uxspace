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
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND, UINT, WPARAM, LPARAM);

namespace {

using Microsoft::WRL::ComPtr;
namespace sp = uxspace::spatial;

constexpr UINT kDevPreviewWidth  = 1280;
constexpr UINT kDevPreviewHeight = 360;
constexpr wchar_t kGlassesNameMatch[] = L"VITURE";

// Hardcoded per release, mirrors the driver's kBuildStamp. Logged at
// boot and after the IPC Ping handshake so a single grep of the log
// file confirms which app + driver pair is actually loaded — handy
// after MSI iterations where pnputil silently kept the previous driver
// because Windows decided it was "the same version".
constexpr const char kAppBuildStamp[] = "v20260523-2300";

// Cached driver build stamp from the most-recent successful Pong.
// Populated by the boot Ping and refreshed by the dev-UI Ping button;
// surfaced in the dev-UI build line so the user can see what driver is
// loaded without opening the log file.
char g_lastDriverBuild[128] = {};

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

// PINNED-mode screen-band presets. In PINNED, Win+Shift+Z cycles
// through this fixed set; in FREE the slider stays continuous over
// kScreenBandMin..kScreenBandMax with step kScreenBandStep.
//
// Two pieces of remembered state make Pin / Unpin reversible:
//   g_pinnedPresetIndex — last PINNED preset the user landed on;
//                         restored when entering PINNED.
//   g_freeScreenBand    — band value active in FREE at the moment of
//                         Pin; restored when entering FREE.
// Persisted to HKCU via Save/LoadSettings so the choices survive an
// app restart.
constexpr float kPinnedPresets[]  = { 0.80f, 0.85f, 0.90f, 0.95f, 1.00f };
constexpr int   kPinnedPresetCount = static_cast<int>(std::size(kPinnedPresets));
int   g_pinnedPresetIndex = 2;     // 0.90 by default
float g_freeScreenBand    = 0.90f; // remembered FREE band

// User configuration persisted under HKCU\Software\UxSpace\App.
// REG_DWORD throughout — floats are stored as int(band * 100) so the
// .reg file (and regedit display) reads naturally as e.g. 90 -> 0.90.
// Definitions of LoadSettings / SaveSettings live further down so they
// can see g_pseudo3D + the IpcResult cache; the names are forward-
// declared near CycleScreenBand for early callers.
constexpr wchar_t kSettingsKey[] = L"Software\\UxSpace\\App";

// App-side anchor: stores the head pose at the moment of Win+Shift+C.
// All subsequent poses are reported as pose * inverse(anchor) so the
// wearer's current physical direction becomes "facing forward" without
// touching the SDK's tracking origin (unlike Win+Shift+R, which calls
// xr_device_provider_reset_origin_carina). Useful for "form up around
// me" semantics in multi-monitor layouts (W3 stage E).
uxspace::tracking::HeadPose g_anchorPose;  // identity by default

// Exponentially-smoothed pose. Carina pumps raw IMU samples at high
// frequency with visible high-frequency noise; piping them straight to
// the camera produces shimmer/jitter that's uncomfortable at any zoom.
// Per-frame: smoothed := lerp(smoothed, latest, kPoseSmoothAlpha) (slerp
// for the quaternion). Lower alpha = smoother but laggier; 0.30 is the
// sweet spot empirically — drops the jitter without feeling sluggish.
uxspace::tracking::HeadPose g_smoothedPose;       // tracks latest, valid==false until first sample
constexpr float             kPoseSmoothAlpha = 0.30f;

// Set when Win+Shift is held (no other modifiers required) — used to
// show the key-legend overlay so the wearer can discover bindings in
// situ. Recomputed each frame from GetAsyncKeyState; cheap enough.
bool g_winShiftHeld = false;

// GDI-rendered legend bitmap, uploaded once to a D3D11 texture. Drawn
// as a Surface3D in a separate PINNED render pass when Win+Shift is
// held, so the wearer sees the hotkey list anchored to the bottom-left
// of their view without it being affected by head tracking or zoom.
struct LegendOverlay {
    ComPtr<ID3D11Texture2D>          tex;
    ComPtr<ID3D11ShaderResourceView> srv;
};
LegendOverlay g_legend;

bool BuildLegendTexture(ID3D11Device* device) {
    constexpr int W = 480;
    constexpr int H = 220;

    HDC screenDC = GetDC(nullptr);
    HDC memDC    = CreateCompatibleDC(screenDC);

    BITMAPINFO bi{};
    bi.bmiHeader.biSize        = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth       = W;
    bi.bmiHeader.biHeight      = -H;  // top-down
    bi.bmiHeader.biPlanes      = 1;
    bi.bmiHeader.biBitCount    = 32;
    bi.bmiHeader.biCompression = BI_RGB;

    void* bits = nullptr;
    HBITMAP bmp = CreateDIBSection(memDC, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);
    if (!bmp) { DeleteDC(memDC); ReleaseDC(nullptr, screenDC); return false; }
    HGDIOBJ oldBmp = SelectObject(memDC, bmp);

    RECT rect{ 0, 0, W, H };
    HBRUSH bg = CreateSolidBrush(RGB(16, 18, 28));
    FillRect(memDC, &rect, bg);
    DeleteObject(bg);

    HFONT font = CreateFontW(18, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                             ANSI_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                             ANTIALIASED_QUALITY, FF_MODERN, L"Consolas");
    HGDIOBJ oldFont = SelectObject(memDC, font);
    SetBkMode(memDC, TRANSPARENT);

    SetTextColor(memDC, RGB(255, 220, 100));
    TextOutW(memDC, 14, 8, L"Win + Shift +", 13);
    SetTextColor(memDC, RGB(220, 220, 220));

    struct Row { const wchar_t* k; const wchar_t* v; };
    const Row rows[] = {
        { L"Wheel",  L"Zoom (PINNED only)" },
        { L"+ / -",  L"Zoom in / out (PINNED only)" },
        { L"Z",      L"Screen size +0.1 (wraps)" },
        { L"D",      L"Pseudo-3D layering" },
        { L"X",      L"Toggle PINNED / FREE" },
        { L"R",      L"SDK recenter (hard)" },
        { L"C",      L"Anchor at current pose" },
    };
    int y = 36;
    for (const auto& r : rows) {
        TextOutW(memDC, 14,  y, r.k, static_cast<int>(wcslen(r.k)));
        TextOutW(memDC, 100, y, r.v, static_cast<int>(wcslen(r.v)));
        y += 24;
    }

    SelectObject(memDC, oldFont);
    DeleteObject(font);

    // GDI writes BGR but leaves alpha = 0 (BI_RGB doesn't manage the
    // alpha channel). Set alpha = 255 across the whole bitmap so the
    // texture isn't fully transparent.
    auto* px = static_cast<std::uint32_t*>(bits);
    for (int i = 0; i < W * H; ++i) px[i] |= 0xFF000000u;

    D3D11_TEXTURE2D_DESC td{};
    td.Width      = W;
    td.Height     = H;
    td.MipLevels  = 1;
    td.ArraySize  = 1;
    td.Format     = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc = { 1, 0 };
    td.Usage      = D3D11_USAGE_DEFAULT;
    td.BindFlags  = D3D11_BIND_SHADER_RESOURCE;

    D3D11_SUBRESOURCE_DATA sd{};
    sd.pSysMem     = bits;
    sd.SysMemPitch = W * 4;

    const HRESULT hrT = device->CreateTexture2D(&td, &sd, &g_legend.tex);
    if (SUCCEEDED(hrT)) {
        device->CreateShaderResourceView(g_legend.tex.Get(), nullptr, &g_legend.srv);
    }

    SelectObject(memDC, oldBmp);
    DeleteObject(bmp);
    DeleteDC(memDC);
    ReleaseDC(nullptr, screenDC);
    return g_legend.srv != nullptr;
}
float                g_screenBandTarget = 0.90f;       // mirror of g_scene.screenBand for UI

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

// --- Settings persistence (HKCU\Software\UxSpace\App) ---------------------

DWORD BandToDword(float band) {
    return static_cast<DWORD>(std::lround(std::clamp(band,
                                                     sp::kScreenBandMin,
                                                     sp::kScreenBandMax) * 100.0f));
}
float BandFromDword(DWORD d) {
    return std::clamp(static_cast<float>(d) / 100.0f,
                      sp::kScreenBandMin, sp::kScreenBandMax);
}

bool RegReadDword(HKEY key, const wchar_t* name, DWORD* out) {
    DWORD type = 0, sz = sizeof(DWORD), v = 0;
    const LSTATUS s = RegQueryValueExW(key, name, nullptr, &type,
                                       reinterpret_cast<LPBYTE>(&v), &sz);
    if (s == ERROR_SUCCESS && type == REG_DWORD && sz == sizeof(DWORD)) {
        *out = v;
        return true;
    }
    return false;
}
void RegWriteDword(HKEY key, const wchar_t* name, DWORD v) {
    RegSetValueExW(key, name, 0, REG_DWORD,
                   reinterpret_cast<const BYTE*>(&v), sizeof(v));
}

void LoadSettings() {
    HKEY key = nullptr;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, kSettingsKey, 0, KEY_READ, &key) != ERROR_SUCCESS) {
        uxspace::log::info("settings: HKCU\\%ls not present yet (first run, defaults applied).",
                           kSettingsKey);
        return;
    }
    DWORD d;
    if (RegReadDword(key, L"PinnedPresetIndex", &d)) {
        g_pinnedPresetIndex = std::clamp(static_cast<int>(d), 0, kPinnedPresetCount - 1);
    }
    if (RegReadDword(key, L"FreeScreenBandX100", &d)) {
        g_freeScreenBand = BandFromDword(d);
    }
    if (RegReadDword(key, L"ViewMode", &d)) {
        g_viewMode = (d != 0) ? uxspace::tracking::ViewMode::FREE
                              : uxspace::tracking::ViewMode::PINNED;
    }
    if (RegReadDword(key, L"Pseudo3D", &d)) {
        g_pseudo3D = (d != 0);
    }
    // Seed the live screen band from whichever mode we're starting in
    // so the user sees their last choice immediately.
    g_scene.screenBand = (g_viewMode == uxspace::tracking::ViewMode::PINNED)
                       ? kPinnedPresets[g_pinnedPresetIndex]
                       : g_freeScreenBand;
    g_screenBandTarget = g_scene.screenBand;
    RegCloseKey(key);
    uxspace::log::info("settings: loaded pinIdx=%d (%.2f) freeBand=%.2f viewMode=%s pseudo3D=%d",
                       g_pinnedPresetIndex, kPinnedPresets[g_pinnedPresetIndex],
                       g_freeScreenBand,
                       g_viewMode == uxspace::tracking::ViewMode::FREE ? "FREE" : "PINNED",
                       g_pseudo3D ? 1 : 0);
}

void SaveSettings() {
    HKEY key = nullptr;
    DWORD disp = 0;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, kSettingsKey, 0, nullptr, 0,
                        KEY_WRITE, nullptr, &key, &disp) != ERROR_SUCCESS) {
        uxspace::log::warn("settings: RegCreateKeyExW failed; settings not persisted.");
        return;
    }
    RegWriteDword(key, L"PinnedPresetIndex",  static_cast<DWORD>(g_pinnedPresetIndex));
    RegWriteDword(key, L"FreeScreenBandX100", BandToDword(g_freeScreenBand));
    RegWriteDword(key, L"ViewMode",
                  g_viewMode == uxspace::tracking::ViewMode::FREE ? 1u : 0u);
    RegWriteDword(key, L"Pseudo3D",           g_pseudo3D ? 1u : 0u);
    RegCloseKey(key);
}

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
    char                     pongPayload[128] = {};  // driver build stamp (Pong only)
};

const char* IpcMessageName(uxspace::ipc::MessageType t);
const char* IpcErrorName(uxspace::ipc::ErrorCode c);

IpcResult IpcRequest(uxspace::ipc::MessageType type,
                     const void* payload, std::uint32_t payloadBytes) {
    using namespace uxspace::ipc;
    static std::atomic<std::uint32_t> s_requestId{ 0 };

    IpcResult r;
    const std::uint32_t reqId = s_requestId.fetch_add(1) + 1;
    uxspace::log::info("ipc: req#%u type=%s payload=%u start",
                       reqId, IpcMessageName(type), payloadBytes);

    HANDLE pipe = CreateFileW(kPipeName, GENERIC_READ | GENERIC_WRITE,
                              0, nullptr, OPEN_EXISTING, 0, nullptr);
    if (pipe == INVALID_HANDLE_VALUE) {
        r.win32Error = GetLastError();
        // CRITICAL: IpcResult defaults replyType=Nack, nackCode=Internal,
        // nackMessage="" — so an early return looks identical to a
        // driver-sent Nack/Internal with empty message. Until v2100 we
        // had no way to distinguish "the driver actually NACK'd us" from
        // "the IPC pipe blew up before we even read a header". Log every
        // early-return stage explicitly so the next failure is
        // immediately attributable.
        uxspace::log::warn("ipc: req#%u type=%s CreateFile failed err=%lu (defaulting to Nack/Internal)",
                           reqId, IpcMessageName(type),
                           static_cast<unsigned long>(r.win32Error));
        return r;
    }
    DWORD pipeMode = PIPE_READMODE_MESSAGE;
    SetNamedPipeHandleState(pipe, &pipeMode, nullptr, nullptr);
    uxspace::log::info("ipc: req#%u pipe opened, mode set to MESSAGE", reqId);

    Header req{};
    req.protocol_version = kProtocolVersion;
    req.type             = type;
    req.payload_bytes    = payloadBytes;
    req.request_id       = reqId;

    DWORD wrote = 0;
    if (!WriteFile(pipe, &req, sizeof(req), &wrote, nullptr) ||
        wrote != sizeof(req)) {
        r.win32Error = GetLastError();
        uxspace::log::warn("ipc: req#%u type=%s WriteFile(header) failed wrote=%lu err=%lu",
                           reqId, IpcMessageName(type),
                           static_cast<unsigned long>(wrote),
                           static_cast<unsigned long>(r.win32Error));
        CloseHandle(pipe);
        return r;
    }
    uxspace::log::info("ipc: req#%u WriteFile(header) wrote=%lu OK", reqId, wrote);
    if (payloadBytes > 0) {
        if (!WriteFile(pipe, payload, payloadBytes, &wrote, nullptr) ||
            wrote != payloadBytes) {
            r.win32Error = GetLastError();
            uxspace::log::warn("ipc: req#%u type=%s WriteFile(payload %u) failed wrote=%lu err=%lu",
                               reqId, IpcMessageName(type), payloadBytes,
                               static_cast<unsigned long>(wrote),
                               static_cast<unsigned long>(r.win32Error));
            CloseHandle(pipe);
            return r;
        }
        uxspace::log::info("ipc: req#%u WriteFile(payload %u) wrote=%lu OK",
                           reqId, payloadBytes, wrote);
    }
    uxspace::log::info("ipc: req#%u waiting for response header...", reqId);

    Header rsp{};
    DWORD read = 0;
    if (!ReadFile(pipe, &rsp, sizeof(rsp), &read, nullptr) ||
        read != sizeof(rsp)) {
        r.win32Error = GetLastError();
        uxspace::log::warn("ipc: req#%u type=%s ReadFile(rsp header) failed read=%lu err=%lu",
                           reqId, IpcMessageName(type),
                           static_cast<unsigned long>(read),
                           static_cast<unsigned long>(r.win32Error));
        CloseHandle(pipe);
        return r;
    }
    r.replyType = rsp.type;
    uxspace::log::info("ipc: req#%u response header: type=%s payload_bytes=%u",
                       reqId, IpcMessageName(rsp.type), rsp.payload_bytes);

    if (rsp.type == MessageType::Nack) {
        // v1900's "ipc: NackPayload read=..." never fired even though
        // Nack/Internal kept showing up. That meant rsp.payload_bytes
        // didn't equal sizeof(NackPayload) and the original sized-match
        // condition skipped the read. Read whatever payload_bytes worth
        // of bytes are actually on the wire (clipped to our struct),
        // and unconditionally log enough to classify the failure.
        NackPayload np{};
        const DWORD wantedBytes = std::min<DWORD>(rsp.payload_bytes,
                                                  static_cast<DWORD>(sizeof(np)));
        BOOL  readOk = TRUE;
        DWORD readErr = 0;
        if (wantedBytes > 0) {
            readOk  = ReadFile(pipe, &np, wantedBytes, &read, nullptr);
            readErr = readOk ? 0 : GetLastError();
        } else {
            read = 0;
        }
        r.nackCode = np.code;
        std::memcpy(r.nackMessage, np.message,
                    std::min(sizeof(r.nackMessage) - 1, sizeof(np.message)));
        r.nackMessage[sizeof(r.nackMessage) - 1] = '\0';

        char hex[80] = {};
        std::size_t hp = 0;
        for (int i = 0; i < 16 && hp + 3 < sizeof(hex); ++i) {
            const unsigned char b = static_cast<unsigned char>(np.message[i]);
            const char* dig = "0123456789ABCDEF";
            hex[hp++] = dig[b >> 4];
            hex[hp++] = dig[b & 0xF];
            hex[hp++] = ' ';
        }
        hex[hp] = '\0';
        uxspace::log::info("ipc: Nack rsp.payload_bytes=%u sizeof(NackPayload)=%zu wanted=%lu read=%lu ok=%d err=%lu code=%u hex16=%s",
                           rsp.payload_bytes,
                           sizeof(NackPayload),
                           static_cast<unsigned long>(wantedBytes),
                           static_cast<unsigned long>(read),
                           readOk ? 1 : 0,
                           static_cast<unsigned long>(readErr),
                           static_cast<unsigned>(np.code),
                           hex);
    } else if (rsp.type == MessageType::Pong && rsp.payload_bytes > 0
               && rsp.payload_bytes < sizeof(r.pongPayload)) {
        // Pong payload is the driver's __DATE__ __TIME__ build stamp,
        // ASCII without a trailing NUL on the wire.
        ReadFile(pipe, r.pongPayload, rsp.payload_bytes, &read, nullptr);
        r.pongPayload[std::min<DWORD>(rsp.payload_bytes,
                                      sizeof(r.pongPayload) - 1)] = '\0';
    } else if (rsp.payload_bytes > 0) {
        std::vector<BYTE> scratch(rsp.payload_bytes);
        ReadFile(pipe, scratch.data(), rsp.payload_bytes, &read, nullptr);
    }

    CloseHandle(pipe);
    r.ok = (rsp.type != MessageType::Nack);
    uxspace::log::info("ipc: req#%u done ok=%d type=%s",
                       reqId, r.ok ? 1 : 0, IpcMessageName(r.replyType));
    return r;
}

IpcResult IpcPing() { return IpcRequest(uxspace::ipc::MessageType::Ping, nullptr, 0); }

IpcResult IpcSetMonitorCount(std::uint8_t count) {
    uxspace::log::info("ipc: IpcSetMonitorCount entry count=%u (payload=%u bytes)",
                       (unsigned) count,
                       (unsigned) sizeof(uxspace::ipc::SetMonitorCountPayload));
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

// True when 6DoF head tracking is actually driving the camera (tracker
// up AND user has chosen FREE). Zoom is disabled in this state — the
// combination of a magnified back-plane plus head-tracked rotation is
// disorienting and the user prefers to lean / look around instead.
bool IsDofActive() {
    return g_tracker.isConnected()
        && g_viewMode == uxspace::tracking::ViewMode::FREE;
}

// Forward decls — definitions live alongside the dev-UI / tracker code.
void CycleScreenBand();
void AdjustZoom(float delta);
void ToggleViewMode();
void RecenterTracker();
void AnchorAtCurrentPose();

// Global hotkeys, all gated on Win+Shift held + a key press transition:
//   Z       → cycle screen-band preset (0.80 / 0.85 / 0.90 / 1.00 / 1.20 / 1.30)
//   + / =   → zoom in by kZoomStep (covers both shifted and unshifted)
//   - / _   → zoom out by kZoomStep
//   X       → toggle view mode (PINNED ↔ FREE — "tracking on/off")
//   R       → recenter: SDK-level reset_origin (Carina) — full tracking reset
//   C       → centre displays: app-side anchor at current pose (no SDK call;
//            "form up around me" without touching the tracking origin)
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
                    uxspace::log::info("hotkey: Win+Shift+R (SDK recenter).");
                    RecenterTracker();
                    return 1;
                case 'C':
                    uxspace::log::info("hotkey: Win+Shift+C (anchor at current pose).");
                    AnchorAtCurrentPose();
                    return 1;
                case VK_OEM_PLUS:
                case VK_ADD:
                    if (IsDofActive()) {
                        uxspace::log::info("hotkey: Win+Shift++ ignored — DOF active (zoom disabled).");
                    } else {
                        uxspace::log::info("hotkey: Win+Shift++ (zoom in).");
                        AdjustZoom(+kZoomStep);
                    }
                    return 1;
                case VK_OEM_MINUS:
                case VK_SUBTRACT:
                    if (IsDofActive()) {
                        uxspace::log::info("hotkey: Win+Shift+- ignored — DOF active (zoom disabled).");
                    } else {
                        uxspace::log::info("hotkey: Win+Shift+- (zoom out).");
                        AdjustZoom(-kZoomStep);
                    }
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
            if (IsDofActive()) {
                // Don't consume — let the underlying app scroll. Zoom is
                // intentionally inert while 6DoF is driving the camera.
                return CallNextHookEx(nullptr, nCode, wParam, lParam);
            }
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

// Snap g_pinnedPresetIndex to the preset closest to a given band value.
// Used when the user transitions FREE -> PINNED at a band that isn't in
// the preset list, so the next Win+Shift+Z press feels predictable
// (it advances from a known starting point rather than wherever fmod
// arithmetic happened to leave us).
int NearestPinnedPresetIndex(float band) {
    int best = 0;
    float bestDiff = std::fabs(kPinnedPresets[0] - band);
    for (int i = 1; i < kPinnedPresetCount; ++i) {
        const float d = std::fabs(kPinnedPresets[i] - band);
        if (d < bestDiff) { bestDiff = d; best = i; }
    }
    return best;
}

void ApplyViewModeTransition(uxspace::tracking::ViewMode from,
                             uxspace::tracking::ViewMode to) {
    using uxspace::tracking::ViewMode;
    if (from == to) return;
    if (from == ViewMode::FREE && to == ViewMode::PINNED) {
        // Pin: remember the FREE band, restore the last PINNED preset.
        g_freeScreenBand   = g_scene.screenBand;
        g_pinnedPresetIndex = std::clamp(g_pinnedPresetIndex, 0, kPinnedPresetCount - 1);
        g_scene.screenBand = kPinnedPresets[g_pinnedPresetIndex];
    } else {
        // Unpin: stash whichever preset we were on (snap if drifted),
        // restore the FREE band the user had before Pin.
        g_pinnedPresetIndex = NearestPinnedPresetIndex(g_scene.screenBand);
        g_scene.screenBand  = std::clamp(g_freeScreenBand,
                                         sp::kScreenBandMin, sp::kScreenBandMax);
    }
    g_screenBandTarget = g_scene.screenBand;
}

void ToggleViewMode() {
    using uxspace::tracking::ViewMode;
    const ViewMode prev = g_viewMode;
    g_viewMode = (prev == ViewMode::FREE) ? ViewMode::PINNED : ViewMode::FREE;
    ApplyViewModeTransition(prev, g_viewMode);
    uxspace::log::info("view mode: %s%s  band=%.2f",
                       g_viewMode == ViewMode::FREE ? "FREE" : "PINNED",
                       g_viewMode == ViewMode::FREE && !g_tracker.isConnected()
                           ? " (no tracker — still rendering PINNED)" : "",
                       g_scene.screenBand);
    SaveSettings();
}

void RecenterTracker() {
    g_tracker.recenter();
}

void AnchorAtCurrentPose() {
    // Snapshot the tracker's current pose; subsequent latestPose() returns
    // are reported relative to it in the render loop. Cheap, reversible —
    // pressing again re-snapshots; resetting g_anchorPose to identity
    // undoes the anchor entirely (no UI for that yet).
    const uxspace::tracking::HeadPose now = g_tracker.latestPose();
    g_anchorPose = now;
    uxspace::log::info("anchor: pose pos=[%+.3f %+.3f %+.3f] q=[%+.3f %+.3f %+.3f %+.3f] valid=%d",
                       now.position.x, now.position.y, now.position.z,
                       now.orientation.x, now.orientation.y, now.orientation.z, now.orientation.w,
                       now.valid ? 1 : 0);
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

        // When the glasses appear, the Carina USB device is enumerable
        // too. If the tracker wasn't connected (typical first-boot case
        // when glasses were plugged in *after* :app started), retry the
        // SDK start so the user doesn't have to toggle Connect/Stop in
        // the dev UI to get tracking going.
        if (!g_tracker.isConnected()) {
            uxspace::log::info("glasses: tracker not connected — retrying tracker.start().");
            const bool ok = g_tracker.start();
            uxspace::log::info("glasses: deferred tracker.start() returned %s; connected=%d.",
                               ok ? "true" : "false",
                               g_tracker.isConnected() ? 1 : 0);
            if (ok && g_viewMode != uxspace::tracking::ViewMode::FREE) {
                const auto prev = g_viewMode;
                g_viewMode = uxspace::tracking::ViewMode::FREE;
                ApplyViewModeTransition(prev, g_viewMode);
                SaveSettings();
            }
        }
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
            SaveSettings();
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

// Overlays the GDI-rendered hotkey legend on top of an already-rendered
// framebuffer. Runs as a second pass with no clear so the main scene
// remains visible behind it. The legend uses its own clean camera
// (PINNED, IPD = 0) so it always appears at the same screen position in
// both eyes regardless of the active tracker pose / view mode / anchor.
void RenderLegendOverlay(ID3D11RenderTargetView* rtv,
                         UINT w,
                         UINT h,
                         bool stereo) {
    if (!g_legend.srv) return;

    g_d3d.context->OMSetRenderTargets(1, &rtv, nullptr);

    sp::Scene legendScene;
    legendScene.screenBand = g_scene.screenBand;

    sp::Surface3D s;
    // Centred in the wearer's view, twice the original size — bottom-left
    // placement was hard to spot inside the comfortable FoV band of the
    // Viture optics. Aspect is the source bitmap's 480:220 (~2.18:1).
    s.position = { 0.0f, 0.0f, 1.00f };
    s.size     = { 0.60f, 0.60f * 220.0f / 480.0f };
    s.uvRect   = { 0.0f, 0.0f, 1.0f, 1.0f };
    s.texture  = g_legend.srv.Get();
    legendScene.surfaces.push_back(s);

    sp::StereoCamera cam;
    cam.ipdMeters = 0.0f;
    cam.mode      = uxspace::tracking::ViewMode::PINNED;

    if (stereo) {
        legendScene.renderStereo(g_d3d.context.Get(), g_renderer, cam, w, h);
    } else {
        legendScene.renderMono(g_d3d.context.Get(), g_renderer, cam, w, h);
    }
}

void SaveSettings();

void CycleScreenBand() {
    using uxspace::tracking::ViewMode;
    if (g_viewMode == ViewMode::PINNED) {
        // PINNED: cycle through the discrete preset list with wrap.
        g_pinnedPresetIndex = (g_pinnedPresetIndex + 1) % kPinnedPresetCount;
        g_scene.screenBand  = kPinnedPresets[g_pinnedPresetIndex];
    } else {
        // FREE: continuous +kScreenBandStep with wrap to min on overflow.
        float v = g_scene.screenBand + sp::kScreenBandStep;
        if (v > sp::kScreenBandMax + 0.001f) v = sp::kScreenBandMin;
        v = std::round(v * 10.0f) / 10.0f;
        g_scene.screenBand = std::clamp(v, sp::kScreenBandMin, sp::kScreenBandMax);
        g_freeScreenBand   = g_scene.screenBand;  // track FREE-mode choice
    }
    g_screenBandTarget = g_scene.screenBand;
    SaveSettings();
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
        row("Win+Shift+Wheel", "global", "Zoom over the UxSpace virtual monitor (1.0x-4.0x). PINNED only.");
        row("Win+Shift++",     "global", "Zoom in by 0.25x (keyboard). PINNED only.");
        row("Win+Shift+-",     "global", "Zoom out by 0.25x. PINNED only.");
        row("Win+Shift+Z",     "global", "Screen size +0.10 (0.70..2.00, wraps).");
        row("Win+Shift+D",     "global", "Toggle pseudo-3D per-window layering.");
        row("Win+Shift+X",     "global", "Toggle view mode: PINNED (head-locked) <-> FREE (world-locked).");
        row("Win+Shift+R",     "global", "Recenter: SDK reset_origin (hard reset of tracking).");
        row("Win+Shift+C",     "global", "Centre displays: app-side anchor at current pose (no SDK call).");
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

    // Screen size — continuous slider. Win+Shift+Z steps by 0.10 with wrap.
    // Values <= 1.0 letterbox; > 1.0 zoom the displayed quad ("closer").
    g_screenBandTarget = g_scene.screenBand;
    if (ImGui::SliderFloat("Screen size (Win+Shift+Z = +0.1)",
                           &g_screenBandTarget,
                           sp::kScreenBandMin, sp::kScreenBandMax, "%.2f")) {
        g_scene.screenBand = g_screenBandTarget;
        // Mirror the current mode's persisted slot so the next mode
        // toggle / restart picks up the slider drag.
        if (g_viewMode == uxspace::tracking::ViewMode::FREE) {
            g_freeScreenBand = g_scene.screenBand;
        } else {
            g_pinnedPresetIndex = NearestPinnedPresetIndex(g_scene.screenBand);
        }
        SaveSettings();
    }
    ImGui::Separator();

    // Zoom (Win+Shift+wheel over the UxSpace virtual monitor).
    ImGui::Text("Zoom: %.2fx", g_zoomLevel);
    if (IsDofActive()) {
        ImGui::SameLine();
        ImGui::TextColored(ImVec4(1.0f, 0.85f, 0.4f, 1.0f),
                           "disabled (DOF active — toggle PINNED with Win+Shift+X to zoom)");
    } else if (g_zoomLevel > 1.0001f) {
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
    ImGui::Text("Build: app=%s driver=%s", kAppBuildStamp,
                g_lastDriverBuild[0] ? g_lastDriverBuild : "(no Pong yet)");
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
        if (r.ok) {
            uxspace::log::info("ipc: manual Ping -> Pong. Driver build = %s.",
                               r.pongPayload[0] ? r.pongPayload : "(unknown)");
            strncpy_s(g_lastDriverBuild, sizeof(g_lastDriverBuild),
                      r.pongPayload, _TRUNCATE);
        } else {
            uxspace::log::warn("ipc: manual Ping failed (win32=%lu, type=%s/%s: %s).",
                               r.win32Error,
                               IpcMessageName(r.replyType),
                               IpcErrorName(r.nackCode),
                               r.nackMessage);
        }
    }
    ImGui::SameLine();
    ImGui::TextDisabled("Monitor count:");
    for (std::uint8_t n = 1; n <= uxspace::ipc::kMaxMonitors; ++n) {
        ImGui::SameLine();
        char label[16]; snprintf(label, sizeof(label), "%u##mcount", n);
        if (ImGui::SmallButton(label)) {
            uxspace::log::info("ui: monitor-count button pressed n=%u (currently advertised max=%u)",
                               (unsigned) n, (unsigned) uxspace::ipc::kMaxMonitors);
            const IpcResult r = IpcSetMonitorCount(n);
            if (r.replyType == uxspace::ipc::MessageType::Nack) {
                uxspace::log::warn("ipc: SetMonitorCount(%u) -> Nack/%s: %s (win32err=%lu)", n,
                                   IpcErrorName(r.nackCode),
                                   r.nackMessage,
                                   static_cast<unsigned long>(r.win32Error));
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
        SaveSettings();
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

    // Key legend — visible while Win+Shift is held. MVP: dev-window
    // floating panel; the glasses-framebuffer version of the same
    // panel is the next iteration (needs GDI-text-to-texture or a
    // second ImGui context).
    if (g_winShiftHeld) {
        const ImGuiViewport* vp = ImGui::GetMainViewport();
        const ImVec2 pos { vp->WorkPos.x + 12,
                           vp->WorkPos.y + vp->WorkSize.y - 220 };
        ImGui::SetNextWindowPos(pos, ImGuiCond_Always);
        ImGui::SetNextWindowBgAlpha(0.90f);
        ImGui::Begin("##keylegend", nullptr,
                     ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_NoResize |
                     ImGuiWindowFlags_NoMove     | ImGuiWindowFlags_NoFocusOnAppearing |
                     ImGuiWindowFlags_NoNav      | ImGuiWindowFlags_AlwaysAutoResize);
        ImGui::TextColored(ImVec4(0.9f, 0.9f, 0.5f, 1.0f), "Win+Shift+...");
        ImGui::Separator();
        auto kv = [](const char* k, const char* v) {
            ImGui::TextUnformatted(k);
            ImGui::SameLine(180);
            ImGui::TextUnformatted(v);
        };
        kv("Wheel",         "Zoom in/out (cursor on UxSpace)");
        kv("+ / -",         "Zoom in/out (keyboard)");
        kv("Z",             "Cycle screen size");
        kv("D",             "Pseudo-3D layering");
        kv("X",             "Toggle PINNED / FREE");
        kv("R",             "SDK recenter");
        kv("C",             "Anchor at current pose");
        ImGui::End();
    }

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
        uxspace::log::info("UxSpace app build = %s.", kAppBuildStamp);
        uxspace::log::info("Log file: %ls", logPath);
        uxspace::log::info("Driver log (separate, written by WUDFHost): C:\\Windows\\Temp\\UxSpace-driver.log");
    }

    // Restore persisted settings (screen band per-mode, view mode,
    // pseudo-3D toggle). Must run before TryOpenGlasses / tracker.start
    // because both pre-set g_viewMode = FREE on success, and we want
    // the saved preference to win unless the auto-start actually
    // transitions us.
    LoadSettings();

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

    // GDI -> texture for the Win+Shift hotkey legend (drawn as a Surface3D
    // overlay on the glasses framebuffer while Win+Shift is held). One-shot
    // build; the texture is reused for every overlay pass.
    if (!BuildLegendTexture(g_d3d.device.Get())) {
        uxspace::log::warn("legend: BuildLegendTexture failed; glasses-side hotkey legend disabled.");
    } else {
        uxspace::log::info("legend: texture built (480x220 B8G8R8A8).");
    }

    // Surfaces are rebuilt every frame by UpdateScene(): back plane + (if
    // pseudo-3D is on and we're not zoomed) per-window quads. Screen-size
    // initial value comes from g_scene.screenBand's default (0.90); the
    // slider + Win+Shift+Z mutate it at runtime.

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
            uxspace::log::info("ipc: driver pipe Ping OK. Driver build = %s",
                               ping.pongPayload[0] ? ping.pongPayload : "(unknown - pre-v1112)");
            // Single-line banner so a log scan immediately confirms
            // which app + driver pair is actually loaded.
            uxspace::log::info("build: app=%s driver=%s",
                               kAppBuildStamp,
                               ping.pongPayload[0] ? ping.pongPayload : "(unknown)");
            strncpy_s(g_lastDriverBuild, sizeof(g_lastDriverBuild),
                      ping.pongPayload, _TRUNCATE);
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

    ShowWindow(hwnd, SW_SHOWMAXIMIZED);
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
            if (ok && g_viewMode != uxspace::tracking::ViewMode::FREE) {
                const auto prev = g_viewMode;
                g_viewMode = uxspace::tracking::ViewMode::FREE;
                ApplyViewModeTransition(prev, g_viewMode);
                SaveSettings();
            }
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
        g_winShiftHeld = WinAndShiftHeld();

        // Push the tracker's latest head pose into the camera. If the
        // user has requested FREE but the tracker isn't producing,
        // render PINNED anyway — the user's intent stays sticky, the
        // visible behaviour falls back gracefully.
        //
        // Low-pass smoothing is applied to the raw sample before the
        // anchor transform so the smoothing constant has the same meaning
        // regardless of anchor state. The pose is then reported relative
        // to g_anchorPose (set by Win+Shift+C). For an identity anchor
        // (default), relative == absolute. For a captured anchor:
        // relativePos = absPos - anchorPos and relativeRot = absRot *
        // conj(anchorRot).
        {
            using uxspace::tracking::ViewMode;
            const auto latest = g_tracker.latestPose();

            uxspace::tracking::HeadPose abs;
            if (latest.valid) {
                if (!g_smoothedPose.valid) {
                    g_smoothedPose = latest;     // seed on first sample
                } else {
                    const float a = kPoseSmoothAlpha;
                    g_smoothedPose.position.x = g_smoothedPose.position.x * (1.0f - a) + latest.position.x * a;
                    g_smoothedPose.position.y = g_smoothedPose.position.y * (1.0f - a) + latest.position.y * a;
                    g_smoothedPose.position.z = g_smoothedPose.position.z * (1.0f - a) + latest.position.z * a;
                    const DirectX::XMVECTOR qPrev = DirectX::XMLoadFloat4(&g_smoothedPose.orientation);
                    const DirectX::XMVECTOR qCur  = DirectX::XMLoadFloat4(&latest.orientation);
                    DirectX::XMStoreFloat4(&g_smoothedPose.orientation,
                                           DirectX::XMQuaternionSlerp(qPrev, qCur, a));
                }
                abs = g_smoothedPose;
            } else {
                abs = latest;  // invalid -> identity passthrough
            }

            uxspace::tracking::HeadPose rel = abs;
            if (g_anchorPose.valid) {
                const DirectX::XMVECTOR qAbs    = DirectX::XMLoadFloat4(&abs.orientation);
                const DirectX::XMVECTOR qAnchor = DirectX::XMLoadFloat4(&g_anchorPose.orientation);
                const DirectX::XMVECTOR qRel    = DirectX::XMQuaternionMultiply(
                                                       qAbs,
                                                       DirectX::XMQuaternionConjugate(qAnchor));
                DirectX::XMStoreFloat4(&rel.orientation, qRel);
                rel.position.x = abs.position.x - g_anchorPose.position.x;
                rel.position.y = abs.position.y - g_anchorPose.position.y;
                rel.position.z = abs.position.z - g_anchorPose.position.z;
            }
            g_camera.headPose = rel;
            g_camera.mode     = (g_viewMode == ViewMode::FREE && g_tracker.isConnected())
                                ? ViewMode::FREE : ViewMode::PINNED;
        }

        UpdateScene();

        // Render to glasses (if open). Stereo or mono depending on whether
        // the framebuffer reports an SBS-aspect mode; see GlassesOutput.h.
        // Overlay the hotkey legend on top when Win+Shift is held — the
        // wearer sees the bindings without taking the glasses off.
        if (g_glasses.opened()) {
            const bool stereo = g_glasses.isStereoMode();
            RenderStereoTo(g_glasses.rtv(), g_glasses.width(), g_glasses.height(), stereo);
            if (g_winShiftHeld) {
                RenderLegendOverlay(g_glasses.rtv(), g_glasses.width(), g_glasses.height(), stereo);
            }
            g_glasses.swap()->Present(1, 0);
        }

        // Render to the dev SBS preview (always — useful even without glasses).
        RenderStereoTo(g_devPreview.rtv.Get(), kDevPreviewWidth, kDevPreviewHeight, /*stereo*/true);
        if (g_winShiftHeld) {
            RenderLegendOverlay(g_devPreview.rtv.Get(),
                                kDevPreviewWidth, kDevPreviewHeight, /*stereo*/true);
        }

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
    SaveSettings();
    uxspace::log::info("UxSpace exit.");
    uxspace::log::shutdown();
    return 0;
}
