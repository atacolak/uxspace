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

#include <uxspace/log.h>

// The SDK ships as C headers but doesn't use extern "C" guards on every
// declaration consistently; wrap to be safe and silence narrow warnings.
#pragma warning(push)
#pragma warning(disable : 4505)  // unreferenced local function
extern "C" {
#include <viture_glasses_provider.h>
#include <viture_device_carina.h>
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

void mergeUnique(std::vector<UsbDeviceID>& dst, const UsbDeviceID& d) {
    for (const auto& e : dst) {
        if (e.vid == d.vid && e.pid == d.pid) return;
    }
    dst.push_back(d);
}

// Walk HID device interfaces. Catches Gen1/Pro/Luma/Luma Pro/Beast,
// which expose their control endpoint as HID.
void enumerateHidDevices(std::vector<UsbDeviceID>& out) {
    GUID hidGuid;
    HidD_GetHidGuid(&hidGuid);
    HDEVINFO devInfo = SetupDiGetClassDevsW(&hidGuid, nullptr, nullptr,
                                            DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (devInfo == INVALID_HANDLE_VALUE) return;

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
        mergeUnique(out, { vid, pid });
    }
    SetupDiDestroyDeviceInfoList(devInfo);
}

// Walk every PnP-enumerated device on the USB bus, parsing the
// VID/PID from each device's Hardware ID. Necessary for the Carina
// family (Luma Ultra): Carina is bulk-transfer, not HID, so the HID
// enum above misses it entirely — same fallback path the official
// SDK demo uses (see device_enum.cpp:69 in the demo source).
void enumerateUsbBusDevices(std::vector<UsbDeviceID>& out) {
    HDEVINFO devInfo = SetupDiGetClassDevsA(nullptr, "USB", nullptr,
                                            DIGCF_PRESENT | DIGCF_ALLCLASSES);
    if (devInfo == INVALID_HANDLE_VALUE) return;
    SP_DEVINFO_DATA devData{};
    devData.cbSize = sizeof(devData);
    for (DWORD i = 0; SetupDiEnumDeviceInfo(devInfo, i, &devData); ++i) {
        DWORD regType = 0;
        BYTE  buf[4096] = {};
        if (!SetupDiGetDeviceRegistryPropertyA(devInfo, &devData, SPDRP_HARDWAREID,
                                               &regType, buf, sizeof(buf), nullptr)) continue;
        // SPDRP_HARDWAREID is REG_MULTI_SZ: multiple NUL-terminated strings.
        for (const char* p = reinterpret_cast<const char*>(buf); *p; p += std::strlen(p) + 1) {
            const char* vidPos = std::strstr(p, "VID_");
            const char* pidPos = std::strstr(p, "PID_");
            if (!vidPos || !pidPos) continue;
            unsigned int vid = 0, pid = 0;
            if (std::sscanf(vidPos + 4, "%4x", &vid) == 1 &&
                std::sscanf(pidPos + 4, "%4x", &pid) == 1) {
                mergeUnique(out, { static_cast<USHORT>(vid), static_cast<USHORT>(pid) });
            }
        }
    }
    SetupDiDestroyDeviceInfoList(devInfo);
}

std::vector<UsbDeviceID> enumerateHidDevices() {
    std::vector<UsbDeviceID> result;
    enumerateHidDevices(result);
    enumerateUsbBusDevices(result);
    return result;
}

int findVitureProductId() {
    const auto devices = enumerateHidDevices();  // HID + USB-bus
    uxspace::log::info("viture: USB+HID enumeration found %zu unique VID/PID pairs.",
                       devices.size());
    int vitureVidCount = 0;
    int validPid = 0;
    for (const auto& d : devices) {
        if (d.vid != kVitureVID) {
            uxspace::log::info("viture:   HID VID=0x%04X PID=0x%04X (skip, not Viture VID).",
                               d.vid, d.pid);
            continue;
        }
        ++vitureVidCount;
        const int valid = xr_device_provider_is_product_id_valid(d.pid);
        uxspace::log::info("viture:   HID VID=0x%04X PID=0x%04X (Viture VID, is_product_id_valid=%d).",
                           d.vid, d.pid, valid);
        if (valid == 1 && validPid == 0) {
            validPid = d.pid;
        }
    }
    uxspace::log::info("viture: %d devices matched VID 0x35CA; chosen PID=0x%04X.",
                       vitureVidCount, validPid);
    return validPid;
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

// Forwards SDK-internal log lines into our file logger so the user gets
// a single combined log to share when reporting issues.
extern "C" void __cdecl VitureSdkLogHook(int level, const char* tag, const char* message) {
    const char* lvl = "INFO";
    if (level == 1)      lvl = "ERR";
    else if (level == 2) lvl = "INF";
    else if (level == 3) lvl = "DBG";
    char buf[1024];
    std::snprintf(buf, sizeof(buf), "[sdk %s/%s] %s",
                  tag ? tag : "?",
                  lvl,
                  message ? message : "");
    uxspace::log::info("%s", buf);
}

VitureTracker::VitureTracker() : impl_(std::make_unique<Impl>()) {
    // Hook the SDK's logger up-front so even early-init complaints land
    // in our log. 2 = Info; the SDK gates messages by this level.
    xr_device_provider_set_log_level(2);
    xr_device_provider_set_log_hook(&VitureSdkLogHook);

    uxspace::log::info("viture: ctor — probing for connected Viture USB device.");
    const int pid = findVitureProductId();
    if (pid == 0) {
        uxspace::log::warn("viture: no valid Viture product id found via USB HID. Tracker will not start.");
        return;
    }
    impl_->productId           = pid;
    impl_->supportsTranslation = xr_device_provider_is_product_support_native_dof(pid) != 0;
    impl_->deviceName          = "Viture " + getMarketName(pid);
    uxspace::log::info("viture: detected %s (PID=0x%04X, supports_translation=%d).",
                       impl_->deviceName.c_str(), pid,
                       impl_->supportsTranslation ? 1 : 0);
}

VitureTracker::~VitureTracker() {
    if (impl_ && impl_->running.load()) stop();
}

bool VitureTracker::start() {
    if (!impl_) return false;
    if (impl_->running.load()) {
        uxspace::log::info("viture: start() called but already running.");
        return true;
    }
    uxspace::log::info("viture: start() begin.");

    // The USB state may have changed since the ctor (hotplug). Re-probe.
    if (impl_->productId == 0) {
        uxspace::log::info("viture: start() re-probing USB (no product id from ctor).");
        impl_->productId = findVitureProductId();
        if (impl_->productId == 0) {
            uxspace::log::warn("viture: start() failed — still no Viture USB device.");
            return false;
        }
        impl_->supportsTranslation = xr_device_provider_is_product_support_native_dof(impl_->productId) != 0;
        impl_->deviceName          = "Viture " + getMarketName(impl_->productId);
    }

    impl_->handle = xr_device_provider_create(impl_->productId);
    if (!impl_->handle) {
        uxspace::log::error("viture: xr_device_provider_create(0x%04X) returned NULL.", impl_->productId);
        return false;
    }
    uxspace::log::info("viture: xr_device_provider_create OK.");

    const int initRc = xr_device_provider_initialize(impl_->handle, nullptr, nullptr);
    if (initRc != VITURE_GLASSES_SUCCESS) {
        uxspace::log::error("viture: xr_device_provider_initialize failed: rc=%d.", initRc);
        xr_device_provider_destroy(impl_->handle);
        impl_->handle = nullptr;
        return false;
    }
    uxspace::log::info("viture: xr_device_provider_initialize OK.");

    const int startRc = xr_device_provider_start(impl_->handle);
    if (startRc != VITURE_GLASSES_SUCCESS) {
        uxspace::log::error("viture: xr_device_provider_start failed: rc=%d.", startRc);
        xr_device_provider_shutdown(impl_->handle);
        xr_device_provider_destroy(impl_->handle);
        impl_->handle = nullptr;
        return false;
    }
    uxspace::log::info("viture: xr_device_provider_start OK.");

    impl_->deviceType = xr_device_provider_get_device_type(impl_->handle);
    const char* typeName = "?";
    switch (impl_->deviceType) {
        case XR_DEVICE_TYPE_VITURE_GEN1:   typeName = "GEN1";   break;
        case XR_DEVICE_TYPE_VITURE_GEN2:   typeName = "GEN2";   break;
        case XR_DEVICE_TYPE_VITURE_CARINA: typeName = "CARINA"; break;
        default: break;
    }
    uxspace::log::info("viture: device type = %s (%d).", typeName, impl_->deviceType);

    // Flip to 3D. The EDID re-publishes with the wider SBS modes
    // (3840xH); WM_DISPLAYCHANGE fires shortly after, the host app
    // re-scans, GlassesOutput::isStereoMode() turns true and
    // Scene::renderStereo() kicks in.
    setDimension(impl_->handle, impl_->deviceType, /*is3d=*/1);
    uxspace::log::info("viture: 3D-mode toggle issued.");

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
    if (!impl_ || !impl_->running.load() || !impl_->handle) {
        uxspace::log::info("viture: recenter() — tracker not running, no-op.");
        return;
    }
    if (impl_->deviceType == XR_DEVICE_TYPE_VITURE_CARINA) {
        // Snapshot the current pose, then pass it to reset_origin so
        // subsequent get_gl_pose calls return zero position + neutral
        // yaw relative to where the wearer is right now.
        float currentPose[7] = { 0, 0, 0, 1, 0, 0, 0 };
        int   status         = 0;
        if (xr_device_provider_get_gl_pose_carina(impl_->handle, currentPose, 0.0, &status)
                == VITURE_GLASSES_SUCCESS) {
            const int rc = xr_device_provider_reset_origin_carina(impl_->handle, currentPose);
            uxspace::log::info("viture: recenter() Carina reset_origin rc=%d "
                               "(pose pos=[%.3f %.3f %.3f] q=[%.3f %.3f %.3f %.3f]).",
                               rc,
                               currentPose[0], currentPose[1], currentPose[2],
                               currentPose[3], currentPose[4], currentPose[5], currentPose[6]);
        } else {
            uxspace::log::warn("viture: recenter() Carina — get_gl_pose returned failure.");
        }
    } else {
        // Gen1/Pro/Beast IMU path: SDK has no equivalent reset_origin.
        // Follow-up: cache an app-side inverse-pose origin and apply on
        // every published sample. Not needed for Luma Ultra dev work.
        uxspace::log::info("viture: recenter() — non-Carina device type %d, app-side recenter not yet wired.",
                           impl_->deviceType);
    }
}

uxspace::tracking::HeadPose VitureTracker::latestPose() const {
    using uxspace::tracking::HeadPose;
    if (!impl_ || !impl_->running.load() || !impl_->handle) return HeadPose{};

    if (impl_->deviceType == XR_DEVICE_TYPE_VITURE_CARINA) {
        // Pull-based: cheap SDK call returning cached latched pose.
        float pose[7] = {};
        int   status  = 1;  // 1 = unstable
        const int rc = xr_device_provider_get_gl_pose_carina(impl_->handle, pose, 0.0, &status);
        if (rc != VITURE_GLASSES_SUCCESS) return HeadPose{};

        // Carina returns OpenGL convention (right-handed: X right, Y
        // up, Z back). Our scene is D3D LH (X right, Y up, Z forward).
        // Apply the basis flip:
        //   position: negate Z
        //   quaternion (qw,qx,qy,qz) GL -> (qw,-qx,-qy,qz) D3D
        // Stored in DirectXMath's XMFLOAT4 ordering (x,y,z,w).
        HeadPose hp;
        hp.position    = { pose[0], pose[1], -pose[2] };
        hp.orientation = { -pose[4], -pose[5], pose[6], pose[3] };
        hp.valid       = (status == 0);  // 0 = stable
        return hp;
    }
    // Gen1/Pro/Beast IMU-callback path is a W2-C3 follow-up. Return an
    // invalid pose so :app falls back to PINNED for those devices.
    return HeadPose{};
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
