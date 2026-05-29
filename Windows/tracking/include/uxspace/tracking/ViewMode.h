// Camera view mode. See docs/ARCHITECTURE.md §8.
//
// PINNED: identity view. The camera is locked to the head — quads are
//         head-locked (a HUD-style anchor). Pose is ignored.
// FREE:   inverse head pose. Quads stay in world space; the user looks
//         around them. Default when a tracker is connected.

#pragma once

#include <cstdint>

namespace uxspace::tracking {

enum class ViewMode : std::uint8_t {
    PINNED = 0,
    FREE   = 1,
};

} // namespace uxspace::tracking
