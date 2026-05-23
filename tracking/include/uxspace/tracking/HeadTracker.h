// HeadTracker — vendor-neutral interface for head-pose providers.
//
// Implementations live in vendor modules (:viture today; :xreal /
// :rokid / :openxr in the future). Only `:app` instantiates a concrete
// tracker; the rest of UxSpace sees only this interface. See
// docs/ARCHITECTURE.md §10.

#pragma once

#include <string>

#include "HeadPose.h"

namespace uxspace::tracking {

class HeadTracker {
public:
    virtual ~HeadTracker() = default;

    // Begin producing poses. Returns false if the device isn't attached
    // or the SDK lifecycle failed. Idempotent.
    virtual bool start() = 0;

    // Stop the pose stream and release the device. Idempotent.
    virtual void stop() = 0;

    // Re-anchor: the wearer's current physical orientation becomes
    // "facing forward". For 6DOF devices, also re-anchors translation.
    virtual void recenter() = 0;

    // Returns the most recent fully-published pose. Lock-free; safe to
    // call from any thread.
    virtual HeadPose latestPose() const = 0;

    // True if the device produces translation (6DOF). 3DOF (rotation-only)
    // trackers return false; `:app` then hides translation-dependent
    // controls and Camera.FREE uses rotation only.
    virtual bool supportsTranslation() const = 0;

    // True after start() succeeded and the device hasn't gone away.
    virtual bool isConnected() const = 0;

    // Human-readable name, e.g. "Viture Pro".
    virtual std::string deviceName() const = 0;
};

} // namespace uxspace::tracking
