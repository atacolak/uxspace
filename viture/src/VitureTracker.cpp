// W2-C2: USB HID probe + Viture SDK lifecycle (create / initialize /
// start) + 3D-mode toggle so the glasses' EDID advertises the SBS
// stereo mode our existing GlassesOutput path is already looking for.
// IMU pose polling + recenter math arrive in W2-C3.
//
// Only this file knows the Viture SDK exists; nothing else in UxSpace
// transitively includes its headers.

#include <uxspace/viture/VitureTracker.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <windows.h>
#include <setupapi.h>
extern "C" {
#include <hidsdi.h>
}

// The SDK ships as C headers but doesn't use extern "C" guards on every
// declaration consistently; wrap to be safe and silence narrow warnings.
#pragma warning(push)
#pragma warning(disable : 4505)  // unreferenced local function
extern "C" {
#include <viture_glasses_provider.h>
#include <viture_protocol_public.h>
#include <viture_result.h>
}
#pragma warning(pop)

namespace uxspace::viture {

namespace {

constexpr USHORT kVitureVID = 0x35CA;

bool parseVidPid(const wchar_t* path, USHORT& vid, USHORT& pid) {
    const wchar_t* v = wcsstr(path, L"VID_");
    const wchar_t* p = wcsstr(path, L"PID_");
    if (!v || !p) return false;
    vid = static_cast<USHORT>(wcstoul(v + 4, nullptr, 16));
    pid = static_cast<USHORT>(wcstoul(p + 4, nullptr, 16));
    return true;
}

struct UsbDeviceID {
    USHORT vid;
    USHORT pid;
};

// Walk HID device interfaces, returning unique (VID, PID) tuples. A
// single physical device can expose several HID interfaces; we
// de-duplicate so we don't repeatedly invoke
// xr_device_provider_is_product_id_valid for the same PID.
std::vector<UsbDeviceID> enumerateHidDevices() {
    std::vector<UsbDeviceID> result;
    GUID hidGuid;
    HidD_GetHidGuid(&hidGuid);
    HDEVINFO devInfo = SetupDiGetClassDevsW(&hidGuid, nullptr, nullptr,
                                            DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (devInfo == INVALID_HANDLE_VALUE) return result;

    SP_DEVICE_INTERFACE_DATA ifaceData{};
    ifaceData.cbSize = sizeof(ifaceData);
    for (DWORD i = 0; SetupDiEnumDeviceInterfaces(devInfo, nullptr, &hidGuid, i, &ifaceData); ++i) {
        DWORD required = 0;
        SetupDiGetDeviceInterfaceDetailW(devInfo, &ifaceData, nullptr, 0, &required, nullptr);
        if (required == 0) continue;
        std::vector<BYTE> buf(required);
        auto* detail = reinterpret_cast<SP_DEVICE_INTERFACE_DETAIL_DATA_W*>(buf.data());
        detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);
        if (!SetupDiGetDeviceInterfaceDetailW(devInfo, &ifaceData, detail, required, nullptr, nullptr)) continue;

        USHORT vid = 0, pid = 0;
        if (!parseVidPid(detail->DevicePath, vid, pid)) continue;
        bool dup = false;
        for (const auto& d : result) {
            if (d.vid == vid && d.pid == pid) { dup = true; break; }
        }
        if (!dup) result.push_back({ vid, pid });
    }
    SetupDiDestroyDeviceInfoList(devInfo);
    return result;
}

int findVitureProductId() {
    const auto devices = enumerateHidDevices();
    for (const auto& d : devices) {
        if (d.vid != kVitureVID) continue;
        if (xr_device_provider_is_product_id_valid(d.pid) == 1) {
            return d.pid;
        }
    }
    return 0;
}

std::string getMarketName(int productId) {
    char  buf[64] = {};
    int   len     = sizeof(buf);
    if (xr_device_provider_get_market_name(productId, buf, &len) == VITURE_GLASSES_SUCCESS && len > 0) {
        return std::string(buf, static_cast<size_t>(len));
    }
    return "Viture";
}

// Issues the 2D <-> 3D dimension switch appropriate for the device
// type. Gen1/Pro use bypass-mode switch_dimension; Beast (Gen2) in
// native mode uses native_switch_dimension; Beast in bypass falls back
// to the bypass path. Carina has stereo cameras and no need to toggle.
void setDimension(XRDeviceProviderHandle h, int deviceType, int is3d) {
    if (!h) return;
    if (deviceType == XR_DEVICE_TYPE_VITURE_GEN1) {
        xr_device_provider_switch_dimension(h, is3d);
    } else if (deviceType == XR_DEVICE_TYPE_VITURE_GEN2) {
        const int r = xr_device_provider_native_switch_dimension(h, is3d);
        if (r == VITURE_GLASSES_ERROR_NOT_SUPPORTED) {
            xr_device_provider_switch_dimension(h, is3d);
        }
    }
}

} // namespace

struct VitureTracker::Impl {
    XRDeviceProviderHandle handle = nullptr;
    std::atomic<bool>      running{ false };
    std::string            deviceName;
    int                    productId            = 0;
    int                    deviceType           = -1;
    bool                   supportsTranslation  = false;
};

VitureTracker::VitureTracker() : impl_(std::make_unique<Impl>()) {
    const int pid = findVitureProductId();
    if (pid == 0) return;
    impl_->productId           = pid;
    impl_->supportsTranslation = xr_device_provider_is_product_support_native_dof(pid) != 0;
    impl_->deviceName          = "Viture " + getMarketName(pid);
}

VitureTracker::~VitureTracker() {
    if (impl_ && impl_->running.load()) stop();
}

bool VitureTracker::start() {
    if (!impl_) return false;
    if (impl_->running.load()) return true;

    // The USB state may have changed since the ctor (hotplug). Re-probe.
    if (impl_->productId == 0) {
        impl_->productId = findVitureProductId();
        if (impl_->productId == 0) return false;
        impl_->supportsTranslation = xr_device_provider_is_product_support_native_dof(impl_->productId) != 0;
        impl_->deviceName          = "Viture " + getMarketName(impl_->productId);
    }

    impl_->handle = xr_device_provider_create(impl_->productId);
    if (!impl_->handle) return false;

    if (xr_device_provider_initialize(impl_->handle, nullptr, nullptr) != VITURE_GLASSES_SUCCESS) {
        xr_device_provider_destroy(impl_->handle);
        impl_->handle = nullptr;
        return false;
    }
    if (xr_device_provider_start(impl_->handle) != VITURE_GLASSES_SUCCESS) {
        xr_device_provider_shutdown(impl_->handle);
        xr_device_provider_destroy(impl_->handle);
        impl_->handle = nullptr;
        return false;
    }

    impl_->deviceType = xr_device_provider_get_device_type(impl_->handle);

    // Flip to 3D. The EDID re-publishes with the wider SBS modes
    // (3840xH); WM_DISPLAYCHANGE fires shortly after, the host app
    // re-scans, GlassesOutput::isStereoMode() turns true and
    // Scene::renderStereo() kicks in.
    setDimension(impl_->handle, impl_->deviceType, /*is3d=*/1);

    impl_->running.store(true);
    return true;
}

void VitureTracker::stop() {
    if (!impl_ || !impl_->running.load()) return;
    if (impl_->handle) {
        // Return the device to 2D before tearing down so the next
        // launch (or another consumer) sees a sane EDID.
        setDimension(impl_->handle, impl_->deviceType, /*is3d=*/0);
        xr_device_provider_stop(impl_->handle);
        xr_device_provider_shutdown(impl_->handle);
        xr_device_provider_destroy(impl_->handle);
        impl_->handle = nullptr;
    }
    impl_->running.store(false);
}

void VitureTracker::recenter() {
    // W2-C3.
}

uxspace::tracking::HeadPose VitureTracker::latestPose() const {
    // W2-C3.
    return {};
}

bool VitureTracker::supportsTranslation() const {
    return impl_->supportsTranslation;
}

bool VitureTracker::isConnected() const {
    return impl_->running.load();
}

std::string VitureTracker::deviceName() const {
    return impl_->deviceName;
}

bool isVitureUsbAttached() {
    return findVitureProductId() != 0;
}

} // namespace uxspace::viture
