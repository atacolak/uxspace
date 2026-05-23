cmake_minimum_required(VERSION 3.20)
project(UxSpace
    VERSION 0.1.0
    DESCRIPTION "Spatial multi-monitor compositor for Viture AR glasses on Windows"
    LANGUAGES CXX
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT WIN32)
    message(FATAL_ERROR "UxSpace targets Windows only.")
endif()

if(MSVC)
    add_compile_options(/W4 /permissive- /Zc:__cplusplus /utf-8)
    add_compile_definitions(
        UNICODE
        _UNICODE
        NOMINMAX
        WIN32_LEAN_AND_MEAN
    )
endif()

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)

add_subdirectory(shared)
add_subdirectory(app)

# :driver is a hand-written WDK .vcxproj (UMDF2 + IddCx).
# CMake doesn't drive its build; we just surface it in the generated .sln so
# VS treats the whole repo as one solution.
include_external_msproject(
    UxSpaceDriver ${CMAKE_SOURCE_DIR}/driver/UxSpaceDriver.vcxproj
    TYPE 8BC9CEB8-8B4A-11D0-8D11-00A0C91BC942   # Visual C++ project type GUID
    GUID 6C4EF776-A8E2-49BA-83E4-C3B3503CF0E9
)

# Added as their milestones come up (see docs/ROADMAP.md):
#   add_subdirectory(tracking)   # W2
#   add_subdirectory(viture)     # W2
#   add_subdirectory(spatial)    # W0/W1
#   add_subdirectory(app)        # W0
