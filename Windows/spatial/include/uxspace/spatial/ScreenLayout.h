// ScreenLayout — strategy that maps logical screen slots to world-space
// placements for the back planes. See docs/ARCHITECTURE.md §9 and W3 in
// docs/ROADMAP.md.
//
// The shipping strategies live in `layouts/` and will arrive as W3
// brings up multi-virtual-monitor support in the driver:
//   layouts/Single.h       — one slot, straight ahead (current W2 default)
//   layouts/ArcOfThree.h   — three slots on a cylindrical arc          (W3)
//   layouts/Stack.h        — three slots stacked vertically            (W3)
//
// Per-monitor capture, per-monitor surface composition, and the
// pseudo-3D window layering all keep working when a layout returns
// multiple slots — they just iterate.

#pragma once

#include <DirectXMath.h>

namespace uxspace::spatial {

// Where one virtual monitor's back plane sits in the 3D scene.
//   position : centre of the plane, in metres
//   size     : plane width × height, in metres
// The plane lies in the XY plane at Z = position.z and faces -Z (toward
// the camera at the origin under identity head pose).
struct ScreenPlacement {
    DirectX::XMFLOAT3 position;
    DirectX::XMFLOAT2 size;
};

class ScreenLayout {
public:
    virtual ~ScreenLayout() = default;

    // How many virtual monitors this layout manages. The driver gets
    // SetMonitorCount(monitorCount()) so the OS sees the right number
    // of displays.
    virtual int monitorCount() const = 0;

    // Slot 0..monitorCount()-1. Callers iterate.
    virtual ScreenPlacement placement(int slot) const = 0;

    // Human-readable name shown in the dev UI (later, in the settings
    // overlay's layout picker).
    virtual const char* name() const = 0;
};

} // namespace uxspace::spatial
