cmake_minimum_required(VERSION 3.22.1)
project(webstream_vulkan_test LANGUAGES C CXX)

add_library(webstream_vulkan_test SHARED
        camera_transformation_evaluator.c
        native-camera.cpp
        native-preprocess-downsample.cpp
        native-preprocess-median-v2.cpp
        native-preprocess-sobel.cpp
        native-received-jpeg-renderer.cpp
        native-vulkan-renderer.cpp)

target_compile_features(webstream_vulkan_test PRIVATE cxx_std_17)
target_compile_options(webstream_vulkan_test PRIVATE -O3 -Wall -Wextra)
target_link_libraries(webstream_vulkan_test PRIVATE
        vulkan
        log
        android
        jnigraphics
        camera2ndk
        mediandk)
