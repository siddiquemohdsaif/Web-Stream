cmake_minimum_required(VERSION 3.22.1)

project(native_camera_smoothening)

add_library(
        native_camera_smoothening
        SHARED
        native_camera_image_smoothening.cpp
)

target_compile_options(
        native_camera_smoothening
        PRIVATE
        $<$<COMPILE_LANGUAGE:CXX>:-O3>
)

find_library(log-lib log)
find_library(android-lib android)
find_library(camera2ndk-lib camera2ndk)
find_library(mediandk-lib mediandk)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
        native_camera_smoothening
        ${log-lib}
        ${android-lib}
        ${camera2ndk-lib}
        ${mediandk-lib}
        ${jnigraphics-lib}
)
