// Single — one virtual monitor straight ahead, 2 m from the wearer.
// Codifies what `:app` was hardcoding as the back-plane constants
// through W2. The matching driver state is `SetMonitorCount(1)`.

#pragma once

#include "../ScreenLayout.h"

namespace uxspace::spatial::layouts {

class Single final : public ScreenLayout {
public:
    int monitorCount() const override { return 1; }

    ScreenPlacement placement(int /*slot*/) const override {
        return ScreenPlacement{
            { 0.0f, 0.0f, 2.0f },    // centre, 2 m straight ahead
            { 2.4f, 1.35f }          // 16:9, comfortably inside the FoV
        };
    }

    const char* name() const override { return "Single"; }
};

} // namespace uxspace::spatial::layouts
