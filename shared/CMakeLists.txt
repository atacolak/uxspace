add_library(uxspace_shared INTERFACE)
add_library(uxspace::shared ALIAS uxspace_shared)

target_include_directories(uxspace_shared INTERFACE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_features(uxspace_shared INTERFACE cxx_std_17)
