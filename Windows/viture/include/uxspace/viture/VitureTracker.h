// VitureTracker — HeadTracker implementation backed by the Viture
// Windows SDK. This is the only file in UxSpace that knows about the
// Viture SDK API; the SDK headers are private to viture/src/*.cpp so
// nothing else in the build transitively depends on them.
//
// Lifecycle (per docs/ARCHITECTURE.md §11):
//   1. ctor probes USB for a supported Viture device and validates its
//      product id via the SDK; isConnected() reflects the result.
//   2. start() runs xr_device_provider_create / initialize / start, then
//      issues the 3D-mode toggle so the EDID flips to advertise SBS
//      stereo (3840xN). Spawns the IMU polling thread.
//   3. A dedicated thread double-buffers head poses into an atomic
//      latch; latestPose() returns the most recent published sample.
//   4. recenter() stores an inverse-pose origin so subsequent poses are
//      relative to "wearer facing forward".

#pragma once

#include <memory>
#include <string>

#include <uxspace/tracking/HeadTracker.h>

namespace uxspace::viture {

class VitureTracker final : public uxspace::tracking::HeadTracker {
public:
    VitureTracker();
    ~VitureTracker() override;

    VitureTracker(const VitureTracker&)            = delete;
    VitureTracker& operator=(const VitureTracker&) = delete;

    bool                          start()                 override;
    void                          stop()                  override;
    void                          recenter()              override;
    uxspace::tracking::HeadPose   latestPose()      const override;
    bool                          supportsTranslation() const override;
    bool                          isConnected()     const override;
    std::string                   deviceName()      const override;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// Lightweight USB probe — true if a Viture HID device is currently
// enumerated. Cheaper than constructing a VitureTracker; useful for
// the dev UI to show connection state without owning the device.
bool isVitureUsbAttached();

} // namespace uxspace::viture
