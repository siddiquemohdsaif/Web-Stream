cmake_minimum_required(VERSION 3.22.1)

project(webstream_jxl)

add_library(webstream_h264 SHARED
        webstream_h264/common.cpp
        webstream_h264/decoder_state.cpp
        webstream_h264/encoder_state.cpp
        webstream_h264/jni_bindings.cpp
        webstream_h264/yuv_image.cpp)
target_link_libraries(webstream_h264
        android
        log
        mediandk)

set(WEBSTREAM_LIBJXL_ROOT
        "${CMAKE_CURRENT_LIST_DIR}/third_party/libjxl"
        CACHE PATH "Path to a libjxl source checkout or compatible Android external libjxl tree.")

if (EXISTS "${WEBSTREAM_LIBJXL_ROOT}/CMakeLists.txt")
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_DEVTOOLS OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_DOXYGEN OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_FUZZERS OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_JNI OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_MANPAGES OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_OPENEXR OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_PLUGINS OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_SJPEG OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_TRANSCODE_JPEG OFF CACHE BOOL "" FORCE)
    set(JPEGXL_ENABLE_VIEWERS OFF CACHE BOOL "" FORCE)
    set(JPEGXL_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)

    add_subdirectory("${WEBSTREAM_LIBJXL_ROOT}" "${CMAKE_BINARY_DIR}/libjxl" EXCLUDE_FROM_ALL)

    add_library(webstream_jxl SHARED webstream_jxl.cpp)
    target_compile_definitions(webstream_jxl PRIVATE WEBSTREAM_HAS_LIBJXL=1)
    target_include_directories(webstream_jxl PRIVATE
            "${WEBSTREAM_LIBJXL_ROOT}"
            "${WEBSTREAM_LIBJXL_ROOT}/lib/include"
            "${WEBSTREAM_LIBJXL_ROOT}/gen/include")
    target_link_libraries(webstream_jxl
            android
            jnigraphics
            log
            jxl
            jxl_threads)
else()
    add_library(webstream_jxl SHARED webstream_jxl_stub.cpp)
    target_link_libraries(webstream_jxl log)
endif()
