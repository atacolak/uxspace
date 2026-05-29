# :app — Win32 / D3D11 / Dear ImGui host. Drives the driver via :shared
# and (later) consumes :spatial, :tracking, :viture.

include(FetchContent)

# --- Dear ImGui ------------------------------------------------------------
FetchContent_Declare(imgui
    GIT_REPOSITORY https://github.com/ocornut/imgui.git
    GIT_TAG        v1.91.5
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(imgui)

add_library(imgui STATIC
    ${imgui_SOURCE_DIR}/imgui.cpp
    ${imgui_SOURCE_DIR}/imgui_draw.cpp
    ${imgui_SOURCE_DIR}/imgui_tables.cpp
    ${imgui_SOURCE_DIR}/imgui_widgets.cpp
    ${imgui_SOURCE_DIR}/imgui_demo.cpp
    ${imgui_SOURCE_DIR}/backends/imgui_impl_win32.cpp
    ${imgui_SOURCE_DIR}/backends/imgui_impl_dx11.cpp
)
target_include_directories(imgui PUBLIC
    ${imgui_SOURCE_DIR}
    ${imgui_SOURCE_DIR}/backends
)
target_compile_definitions(imgui PUBLIC IMGUI_DISABLE_OBSOLETE_FUNCTIONS)

# --- :app executable -------------------------------------------------------
add_executable(uxspace_app WIN32
    main.cpp
)
set_target_properties(uxspace_app PROPERTIES
    OUTPUT_NAME UxSpace
    VS_DEBUGGER_WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
)

target_link_libraries(uxspace_app PRIVATE
    uxspace::shared
    uxspace::spatial
    uxspace::tracking
    uxspace::viture
    imgui
    d3d11
    dxgi
    d3dcompiler
    dwmapi
)

# Ship Viture SDK runtime DLLs next to UxSpace.exe. The SDK is dlopen'd
# implicitly via glasses.lib; without these the exe fails to load.
foreach(_dll IN LISTS UXSPACE_VITURE_RUNTIME_DLLS)
    add_custom_command(TARGET uxspace_app POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${_dll}" "$<TARGET_FILE_DIR:uxspace_app>"
        VERBATIM)
endforeach()
