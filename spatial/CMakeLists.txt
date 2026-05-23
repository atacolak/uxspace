# :spatial — 3D stage. Owns DDA capture (VirtualScreen), the textured-quad
# renderer, stereo camera, scene graph, and the glasses-display fullscreen
# swap chain. Architecture: docs/ARCHITECTURE.md §2.

add_library(uxspace_spatial STATIC
    src/VirtualScreen.cpp
    src/Renderer.cpp
    src/Camera.cpp
    src/Scene.cpp
    src/GlassesOutput.cpp
)
add_library(uxspace::spatial ALIAS uxspace_spatial)

target_include_directories(uxspace_spatial PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(uxspace_spatial PUBLIC
    uxspace::shared
    uxspace::tracking
    d3d11
    dxgi
    d3dcompiler
)

target_compile_features(uxspace_spatial PUBLIC cxx_std_17)
