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
#include <dxgi1_2.h>
#include <wrl/client.h>

#include <imgui.h>
#include <backends/imgui_impl_win32.h>
#include <backends/imgui_impl_dx11.h>

#include <uxspace/ipc.h>
#include <uxspace/spatial/VirtualScreen.h>
#include <uxspace/spatial/Renderer.h>
#include <uxspace/spatial/Camera.h>
#include <uxspace/spatial/Surface3D.h>
#include <uxspace/spatial/Scene.h>
#include <uxspace/spatial/GlassesOutput.h>

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


std::vector<sp::GlassesOutput::DetectedMonitor> g_lastSeenMonitors;

// Set when the glasses connect; consumed on the first frame the UxSpace
// virtual monitor is also live so we can place the cursor on the surface
// the wearer is actually looking at. The two events are independent, so
// we can't just SetCursorPos right after open().
bool g_pendingCenterCursorOnUxSpace = false;

bool CursorInRect(POINT p, const RECT& r) {
    return p.x >= r.left && p.x < r.right && p.y >= r.top && p.y < r.bottom;
}

bool WinAndShiftHeld() {
    const bool winDown   = (GetAsyncKeyState(VK_LWIN)  & 0x8000)
                        || (GetAsyncKeyState(VK_RWIN)  & 0x8000);
    const bool shiftDown = (GetAsyncKeyState(VK_SHIFT) & 0x8000) != 0;
    return winDown && shiftDown;
}

// Forward decl: cycles the screen-band preset. Defined alongside the
// dev-UI code that owns g_screenBandIndex.
void CycleScreenBand();

// Global hotkey: Win+Shift+Z cycles the screen-band preset (0.80 / 0.85
// / 0.90) regardless of which app has focus. The keyboard hook also
// consumes the Z keypress so the focused app doesn't receive a stray
// character.
LRESULT CALLBACK LowLevelKeyboardProc(int nCode, WPARAM wParam, LPARAM lParam) {
    static bool zHeld = false;
    if (nCode == HC_ACTION) {
        const auto* info = reinterpret_cast<const KBDLLHOOKSTRUCT*>(lParam);
        const bool down = (wParam == WM_KEYDOWN || wParam == WM_SYSKEYDOWN);
        const bool up   = (wParam == WM_KEYUP   || wParam == WM_SYSKEYUP);
        if (info->vkCode == 'Z') {
            if (down && !zHeld) {
                zHeld = true;
                if (WinAndShiftHeld()) {
                    CycleScreenBand();
                    return 1; // consume so focused app doesn't see Z
                }
            } else if (up) {
                zHeld = false;
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

void TryOpenGlasses() {
    sp::GlassesOutput::DetectedMonitor m;
    if (!sp::GlassesOutput::find(kGlassesNameMatch, m, &g_lastSeenMonitors)) return;
    if (g_glasses.open(g_d3d.device.Get(), m)) {
        g_pendingCenterCursorOnUxSpace = true;
    }
}

// On display topology changes (hotplug, mode change, screen lock/unlock):
// 1. If we currently hold a swap chain on a now-gone output, close it.
//    Otherwise the orphaned WS_POPUP host window gets reassigned to
//    another monitor by the OS and ends up covering the desktop with no
//    chrome to dismiss it.
// 2. Re-attempt to open if not currently attached.
void RefreshGlassesState() {
    std::vector<sp::GlassesOutput::DetectedMonitor> active;
    sp::GlassesOutput::DetectedMonitor dummy;
    sp::GlassesOutput::find(kGlassesNameMatch, dummy, &active);

    if (g_glasses.opened()) {
        bool stillThere = false;
        for (const auto& m : active) {
            if (m.deviceName == g_glasses.deviceName()) { stillThere = true; break; }
        }
        if (!stillThere) g_glasses.close();
    }
    g_lastSeenMonitors = std::move(active);

    if (!g_glasses.opened()) TryOpenGlasses();
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
        RefreshGlassesState();
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

    // One surface for now: the captured virtual monitor, screen-locked
    // straight ahead at 2 m. W3 expands this to ScreenLayout strategies.
    sp::Surface3D screen;
    screen.position = { 0.0f, 0.0f, 2.0f };
    screen.size     = { 2.4f, 1.35f }; // 16:9, comfortably inside the FoV
    g_scene.surfaces.push_back(screen);
    g_scene.screenBand = sp::kScreenBandPresets[g_screenBandIndex];

    // Try the glasses up-front; user can rescan via the UI later.
    TryOpenGlasses();

    // Global low-level hooks for the zoom gestures:
    //   - Win+Shift+wheel over the UxSpace display: continuous zoom (mouse hook)
    //   - Win+Shift+Z:                              cycle 1 -> 2 -> 3 -> 4 -> 1 (keyboard hook)
    // Both live for the lifetime of the app.
    g_mouseHook    = SetWindowsHookExW(WH_MOUSE_LL,    &LowLevelMouseProc,
                                       GetModuleHandleW(nullptr), 0);
    g_keyboardHook = SetWindowsHookExW(WH_KEYBOARD_LL, &LowLevelKeyboardProc,
                                       GetModuleHandleW(nullptr), 0);

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

        // If the glasses connected before the UxSpace virtual monitor was
        // captured, drop the cursor onto the captured monitor as soon as
        // both are live. This is what the wearer sees through the glasses,
        // so it's the right surface to land on.
        if (g_pendingCenterCursorOnUxSpace && g_vscreen.present()) {
            const RECT r = g_vscreen.desktopRect();
            SetCursorPos((r.left + r.right) / 2, (r.top + r.bottom) / 2);
            g_pendingCenterCursorOnUxSpace = false;
        }

        // Re-bind the live texture each frame: VirtualScreen recycles its
        // SRV on ACCESS_LOST, so the pointer is only valid this frame.
        // Also refresh the zoom focus from the cursor's current position
        // (the hook only updates the focus on a wheel event; this keeps it
        // tracking when the user moves the cursor around at a fixed zoom).
        UpdateZoomFromCursor();
        g_scene.surfaces.front().texture = g_vscreen.srv();
        g_scene.surfaces.front().uvRect  = ComputeZoomUVRect();

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

    if (g_mouseHook)    { UnhookWindowsHookEx(g_mouseHook);    g_mouseHook    = nullptr; }
    if (g_keyboardHook) { UnhookWindowsHookEx(g_keyboardHook); g_keyboardHook = nullptr; }
    g_glasses.close();

    ImGui_ImplDX11_Shutdown();
    ImGui_ImplWin32_Shutdown();
    ImGui::DestroyContext();
    return 0;
}
