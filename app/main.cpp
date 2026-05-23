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

extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND, UINT, WPARAM, LPARAM);

namespace {

using Microsoft::WRL::ComPtr;

struct D3D {
    ComPtr<ID3D11Device>           device;
    ComPtr<ID3D11DeviceContext>    context;
    ComPtr<IDXGISwapChain1>        swap;
    ComPtr<ID3D11RenderTargetView> rtv;
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

D3D g_d3d;

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

        ImGui_ImplDX11_NewFrame();
        ImGui_ImplWin32_NewFrame();
        ImGui::NewFrame();

        // W0 placeholder: settings overlay shell + protocol-version sanity readout.
        ImGui::Begin("UxSpace");
        ImGui::Text("W0 skeleton — Win32 + D3D11 + Dear ImGui");
        ImGui::Text("IPC protocol version: %u", uxspace::ipc::kProtocolVersion);
        ImGui::Text("Max monitors: %u", uxspace::ipc::kMaxMonitors);
        ImGui::Separator();
        ImGui::TextDisabled("Next: DDA-capture the UxSpace virtual monitor and");
        ImGui::TextDisabled("render its framebuffer as a textured quad below.");
        ImGui::End();

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
