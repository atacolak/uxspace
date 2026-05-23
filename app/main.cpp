// UxSpace :app — W0 skeleton.
//
// Goal: Win32 window + D3D11 device + Dear ImGui scaffolding, no DDA capture
// yet. The next pass adds DDA from the virtual monitor created by :driver
// and renders it as a textured quad. See docs/ROADMAP.md W0.

#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

#include <imgui.h>
#include <backends/imgui_impl_win32.h>
#include <backends/imgui_impl_dx11.h>

#include <uxspace/ipc.h>

#include <cstdio>
#include <cstdint>
#include <string>
#include <string_view>

extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND, UINT, WPARAM, LPARAM);

namespace {

using Microsoft::WRL::ComPtr;

struct D3D {
    ComPtr<ID3D11Device>           device;
    ComPtr<ID3D11DeviceContext>    context;
    ComPtr<IDXGISwapChain1>        swap;
    ComPtr<ID3D11RenderTargetView> rtv;
};

struct Capture {
    ComPtr<IDXGIOutputDuplication>   duplication;
    ComPtr<ID3D11Texture2D>          dst;
    ComPtr<ID3D11ShaderResourceView> srv;
    std::wstring                     monitorName;   // EnumDisplayDevices DeviceString
    UINT                             width    = 0;
    UINT                             height   = 0;
    std::uint64_t                    frameCount = 0;
    HRESULT                          lastError  = S_OK;
    bool                             present    = false;
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

// Walk EnumDisplayDevices for an adapter whose friendly name contains "UxSpace",
// then find the matching IDXGIOutput by its DeviceName ("\\.\DISPLAYn").
bool FindUxSpaceOutput(IDXGIOutput** out, std::wstring& friendlyName) {
    DISPLAY_DEVICEW dd{};
    dd.cb = sizeof(dd);
    std::wstring targetDeviceName;
    for (DWORD i = 0; EnumDisplayDevicesW(nullptr, i, &dd, 0); ++i) {
        if (std::wstring_view(dd.DeviceString).find(L"UxSpace") != std::wstring_view::npos
            && (dd.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP)) {
            targetDeviceName = dd.DeviceName;
            friendlyName     = dd.DeviceString;
            break;
        }
    }
    if (targetDeviceName.empty()) return false;

    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return false;
    for (UINT a = 0; ; ++a) {
        ComPtr<IDXGIAdapter1> adapter;
        if (factory->EnumAdapters1(a, &adapter) == DXGI_ERROR_NOT_FOUND) break;
        for (UINT o = 0; ; ++o) {
            ComPtr<IDXGIOutput> output;
            if (adapter->EnumOutputs(o, &output) == DXGI_ERROR_NOT_FOUND) break;
            DXGI_OUTPUT_DESC od{};
            output->GetDesc(&od);
            if (targetDeviceName == od.DeviceName) {
                *out = output.Detach();
                return true;
            }
        }
    }
    return false;
}

bool InitCapture(D3D& d, Capture& c) {
    ComPtr<IDXGIOutput> output;
    if (!FindUxSpaceOutput(&output, c.monitorName)) {
        c.lastError = E_FAIL;
        return false;
    }
    ComPtr<IDXGIOutput1> output1;
    if (FAILED(output.As(&output1))) { c.lastError = E_NOINTERFACE; return false; }

    HRESULT hr = output1->DuplicateOutput(d.device.Get(), &c.duplication);
    if (FAILED(hr)) { c.lastError = hr; return false; }

    DXGI_OUTDUPL_DESC od{};
    c.duplication->GetDesc(&od);
    c.width  = od.ModeDesc.Width;
    c.height = od.ModeDesc.Height;

    D3D11_TEXTURE2D_DESC td{};
    td.Width      = c.width;
    td.Height     = c.height;
    td.MipLevels  = 1;
    td.ArraySize  = 1;
    td.Format     = od.ModeDesc.Format; // typically DXGI_FORMAT_B8G8R8A8_UNORM
    td.SampleDesc = { 1, 0 };
    td.Usage      = D3D11_USAGE_DEFAULT;
    td.BindFlags  = D3D11_BIND_SHADER_RESOURCE;
    if (FAILED(d.device->CreateTexture2D(&td, nullptr, &c.dst))) {
        c.lastError = E_FAIL; return false;
    }
    if (FAILED(d.device->CreateShaderResourceView(c.dst.Get(), nullptr, &c.srv))) {
        c.lastError = E_FAIL; return false;
    }
    c.present = true;
    return true;
}

void TickCapture(D3D& d, Capture& c) {
    if (!c.duplication) return;
    DXGI_OUTDUPL_FRAME_INFO info{};
    ComPtr<IDXGIResource> res;
    HRESULT hr = c.duplication->AcquireNextFrame(0, &info, &res);
    if (hr == DXGI_ERROR_WAIT_TIMEOUT) return; // no new frame this tick
    if (FAILED(hr)) { c.lastError = hr; return; }

    ComPtr<ID3D11Texture2D> srcTex;
    if (SUCCEEDED(res.As(&srcTex)) && c.dst) {
        d.context->CopyResource(c.dst.Get(), srcTex.Get());
        ++c.frameCount;
    }
    c.duplication->ReleaseFrame();
}

D3D     g_d3d;
Capture g_capture;

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (ImGui_ImplWin32_WndProcHandler(hwnd, msg, wp, lp)) return true;

    switch (msg) {
    case WM_SIZE:
        if (g_d3d.device && wp != SIZE_MINIMIZED) {
            ResizeRTV(g_d3d, LOWORD(lp), HIWORD(lp));
        }
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
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
        WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 1280, 720,
        nullptr, nullptr, hInst, nullptr);
    if (!hwnd) return 1;

    if (!CreateD3D(hwnd, g_d3d)) {
        MessageBoxW(nullptr, L"Failed to create D3D11 device + swap chain.", L"UxSpace", MB_ICONERROR);
        return 1;
    }

    // Best-effort: capture initializes if a UxSpace virtual monitor exists right
    // now. If it doesn't (driver not installed yet), the app still runs and the
    // overlay shows the not-found state.
    InitCapture(g_d3d, g_capture);

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

        TickCapture(g_d3d, g_capture);

        ImGui_ImplDX11_NewFrame();
        ImGui_ImplWin32_NewFrame();
        ImGui::NewFrame();

        ImGui::Begin("UxSpace");
        ImGui::Text("W0 — Win32 + D3D11 + Dear ImGui + DDA");
        ImGui::Text("IPC protocol version: %u (max monitors: %u)",
                    uxspace::ipc::kProtocolVersion, uxspace::ipc::kMaxMonitors);
        ImGui::Separator();
        if (g_capture.present) {
            ImGui::Text("Monitor: %ls", g_capture.monitorName.c_str());
            ImGui::Text("Size:    %u x %u", g_capture.width, g_capture.height);
            ImGui::Text("Frames:  %llu", static_cast<unsigned long long>(g_capture.frameCount));
        } else {
            ImGui::TextColored(ImVec4(1.0f, 0.55f, 0.55f, 1.0f),
                "No UxSpace virtual monitor found.");
            ImGui::TextDisabled("Install the driver via driver/scripts/Install-Driver.ps1");
        }
        if (FAILED(g_capture.lastError) && g_capture.lastError != E_FAIL) {
            ImGui::TextColored(ImVec4(1.0f, 0.55f, 0.55f, 1.0f),
                "DDA error: 0x%08X", static_cast<unsigned>(g_capture.lastError));
        }
        ImGui::End();

        if (g_capture.present && g_capture.srv) {
            ImGui::Begin("Preview");
            ImVec2 avail = ImGui::GetContentRegionAvail();
            float srcAspect = static_cast<float>(g_capture.width) / static_cast<float>(g_capture.height);
            float w = avail.x;
            float h = w / srcAspect;
            if (h > avail.y) { h = avail.y; w = h * srcAspect; }
            ImGui::Image(reinterpret_cast<ImTextureID>(g_capture.srv.Get()), ImVec2(w, h));
            ImGui::End();
        }

        ImGui::Render();

        const float clear[4] = { 0.07f, 0.08f, 0.10f, 1.0f };
        g_d3d.context->OMSetRenderTargets(1, g_d3d.rtv.GetAddressOf(), nullptr);
        g_d3d.context->ClearRenderTargetView(g_d3d.rtv.Get(), clear);
        ImGui_ImplDX11_RenderDrawData(ImGui::GetDrawData());

        g_d3d.swap->Present(1, 0);
    }

    ImGui_ImplDX11_Shutdown();
    ImGui_ImplWin32_Shutdown();
    ImGui::DestroyContext();
    return 0;
}
