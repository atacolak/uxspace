# :viture — Viture Windows SDK wrapper. Implements HeadTracker from
# :tracking and issues the 3D-mode toggle that unlocks SBS stereo on
# the glasses. The SDK itself is proprietary and never committed; the
# vendored copy lives at `viture/sdk/` (gitignored) or the SDK source
# tree at `..\..\Viture\SDK\Windows`. Architecture: docs/ARCHITECTURE.md §7.

# Locate the Viture SDK. Candidate paths tried in order:
#   1. -DUXSPACE_VITURE_SDK_DIR=...
#   2. viture/sdk/ inside the project (vendored)
#   3. ../Viture/SDK/Windows (sibling of Windows/ under UxSpace/)
set(_VITURE_SDK_CANDIDATES
    "${UXSPACE_VITURE_SDK_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/sdk"
    "${CMAKE_SOURCE_DIR}/../Viture/SDK/Windows"
)
set(_VITURE_SDK "")
foreach(_cand IN LISTS _VITURE_SDK_CANDIDATES)
    if(_cand AND EXISTS "${_cand}/include/viture_glasses_provider.h"
              AND EXISTS "${_cand}/x86_64/glasses.lib")
        get_filename_component(_VITURE_SDK "${_cand}" ABSOLUTE)
        break()
    endif()
endforeach()
if(_VITURE_SDK STREQUAL "")
    message(FATAL_ERROR
        "Viture SDK not found. Place it at viture/sdk/ (vendored) or "
        "..\\Viture\\SDK\\Windows (sibling of the Windows tree under "
        "the UxSpace umbrella), or set -DUXSPACE_VITURE_SDK_DIR=<path>. "
        "The SDK must contain include/viture_glasses_provider.h and "
        "x86_64/glasses.lib.")
endif()
message(STATUS "Viture SDK: ${_VITURE_SDK}")

set(_VITURE_LIB "${_VITURE_SDK}/x86_64/glasses.lib")
set(_VITURE_DLLS
    "${_VITURE_SDK}/x86_64/glasses.dll"
    "${_VITURE_SDK}/x86_64/carina_vio.dll"
    "${_VITURE_SDK}/x86_64/glew32.dll"
    "${_VITURE_SDK}/x86_64/libusb-1.0.dll"
    "${_VITURE_SDK}/x86_64/opencv_world4100.dll"
)

add_library(uxspace_viture STATIC
    src/VitureTracker.cpp
)
add_library(uxspace::viture ALIAS uxspace_viture)

target_include_directories(uxspace_viture
    PUBLIC  ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE ${_VITURE_SDK}/include
)

target_link_libraries(uxspace_viture
    PUBLIC  uxspace::tracking
    PRIVATE uxspace::shared
            ${_VITURE_LIB}
            setupapi
            hid
)

target_compile_features(uxspace_viture PUBLIC cxx_std_17)

# Surface the SDK's runtime DLLs so :app's POST_BUILD can copy them
# next to the exe (same recipe as the Android counterpart's jniLibs).
set(UXSPACE_VITURE_RUNTIME_DLLS ${_VITURE_DLLS}
    CACHE INTERNAL "Viture SDK runtime DLLs to ship next to the exe.")
