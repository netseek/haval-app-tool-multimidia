cmake_minimum_required(VERSION 3.22.1)
project(carplay_video_bridge LANGUAGES CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "This target is only for authorized Android ARM64 builds")
endif()

add_library(carplay_video_bridge SHARED
    video_bridge.cpp
    packet_conversion.cpp
    avc_configuration.cpp
    android_secondary_decoder.cpp
    oem_ingress.cpp
    decoder_lease.cpp
    android_surface_transport.cpp
    surface_runtime_preparation.cpp
    live_surface_activation.cpp
    secondary_advertisement.cpp
    secondary_session.cpp
    secondary_runtime.cpp
    oem_runtime_adapter.cpp
    hook_installation.cpp
    coordinated_dispatch.cpp
    live_session_capture.cpp
    hook_permit.cpp
    oem_entry_bridge.cpp
    unix_hook_endpoint.cpp
    initial_entry_bootstrap.cpp
)
target_compile_features(carplay_video_bridge PUBLIC cxx_std_17)
target_compile_options(carplay_video_bridge PRIVATE -Wall -Wextra -Werror)
target_include_directories(carplay_video_bridge PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(carplay_video_bridge PUBLIC mediandk android dl)
