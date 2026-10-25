cmake_minimum_required(VERSION 3.22.1)
project(wa2movie C)

# Prebuilt minimal LGPL FFmpeg produced by build_ffmpeg.sh (shared libs, packaged into the APK).
set(FFMPEG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/${ANDROID_ABI})
if(NOT EXISTS ${FFMPEG_DIR}/lib/libavcodec.so)
    message(FATAL_ERROR "FFmpeg for ${ANDROID_ABI} missing; run app/src/main/cpp/build_ffmpeg.sh")
endif()

set(FFMPEG_LIBS avformat avcodec swscale swresample avutil)
foreach(lib ${FFMPEG_LIBS})
    add_library(${lib} SHARED IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${FFMPEG_DIR}/lib/lib${lib}.so)
endforeach()

add_library(wa2movie SHARED wa2movie.c)
target_include_directories(wa2movie PRIVATE ${FFMPEG_DIR}/include)
target_compile_options(wa2movie PRIVATE -Wall -Wextra -Wno-unused-parameter -O2)
# 16 KB page-size compatible (Android 15+ devices).
target_link_options(wa2movie PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(wa2movie ${FFMPEG_LIBS} jnigraphics log)
