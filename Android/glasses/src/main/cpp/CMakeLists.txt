# Builds libglasses_bridge.so — the JNI bridge between UxSpace and the native VITURE SDK.
cmake_minimum_required(VERSION 3.22.1)
project(glasses_bridge)

set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")

if(NOT EXISTS "${JNILIBS_DIR}/libglasses.so")
    message(FATAL_ERROR
        "VITURE SDK libglasses.so not found at ${JNILIBS_DIR}. "
        "Download the official Glasses SDK from https://www.viture.com/developer "
        "and vendor arm64-v8a .so files into glasses/src/main/jniLibs/arm64-v8a/ "
        "plus headers into glasses/src/main/cpp/include/. Do not commit those files.")
endif()

# The proprietary VITURE SDK, consumed as a prebuilt shared library.
add_library(glasses SHARED IMPORTED)
set_target_properties(glasses PROPERTIES
    IMPORTED_LOCATION "${JNILIBS_DIR}/libglasses.so")

add_library(glasses_bridge SHARED glasses_bridge.cpp)
target_include_directories(glasses_bridge PRIVATE ${INCLUDE_DIR})
# Official VITURE demo links 16 KiB max-page-size; required on Android 15 16K devices.
target_link_options(glasses_bridge PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(glasses_bridge glasses android log)
