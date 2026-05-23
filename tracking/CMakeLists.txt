# :tracking — vendor-neutral head-tracking interface. Header-only. Holds
# the HeadPose value type, ViewMode enum, and the HeadTracker abstract
# base implemented by :viture (and future siblings like :xreal / :openxr).
# Architecture: docs/ARCHITECTURE.md §10.

add_library(uxspace_tracking INTERFACE)
add_library(uxspace::tracking ALIAS uxspace_tracking)

target_include_directories(uxspace_tracking INTERFACE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_features(uxspace_tracking INTERFACE cxx_std_17)
