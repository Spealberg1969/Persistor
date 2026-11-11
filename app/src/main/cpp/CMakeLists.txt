cmake_minimum_required(VERSION 3.22.1)
project(persistor_native LANGUAGES C CXX)
include(GNUInstallDirs)

# Keep native dependencies static inside app-owned JNI libraries.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# JPEGli: pinned upstream source subset; build only the libjpeg-compatible encoder API.
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_DEVTOOLS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_JPEGLI_LIBJPEG OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_FUZZER_RUNNERS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_SJPEG OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_OPENEXR OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_SKCMS ON CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_COVERAGE OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_FUZZERS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_MANPAGES OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_JNI OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_LTO OFF CACHE BOOL "" FORCE)
set(JPEGLI_TEST_TOOLS OFF CACHE BOOL "" FORCE)
add_subdirectory(jpegli)
add_library(persistor_jpegli SHARED
    jpegli_jni.cpp
    jpegli/lib/jpegli/libjpeg_wrapper.cc
)
target_include_directories(persistor_jpegli PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}/jpegli/lib/include/jpegli"
    "${CMAKE_CURRENT_SOURCE_DIR}/jpegli"
)
target_link_libraries(persistor_jpegli PRIVATE jpegli-static jpegli_cms hwy)
if(ANDROID)
    target_link_libraries(persistor_jpegli PRIVATE android jnigraphics)
endif()
target_compile_features(persistor_jpegli PRIVATE cxx_std_17)
target_compile_options(persistor_jpegli PRIVATE -Wall -Wextra -Wno-unused-parameter)

# MozJPEG exports the same public jpeg_* symbols as JPEGli. Keep it in its own
# shared object so the two backends cannot interpose or collide at link time.
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(PNG_SUPPORTED OFF CACHE BOOL "" FORCE)
set(WITH_JAVA OFF CACHE BOOL "" FORCE)
set(WITH_SIMD OFF CACHE BOOL "" FORCE)
set(WITH_TURBOJPEG OFF CACHE BOOL "" FORCE)
set(WITH_ARITH_ENC OFF CACHE BOOL "" FORCE)
set(WITH_ARITH_DEC OFF CACHE BOOL "" FORCE)
set(WITH_FUZZ OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/mozjpeg)
add_library(persistor_mozjpeg SHARED mozjpeg_jni.cpp)
target_include_directories(persistor_mozjpeg PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/mozjpeg"
    "${CMAKE_CURRENT_BINARY_DIR}/third_party/mozjpeg"
)
target_link_libraries(persistor_mozjpeg PRIVATE jpeg-static)
target_link_options(persistor_mozjpeg PRIVATE -Wl,-Bsymbolic)
if(ANDROID)
    target_link_libraries(persistor_mozjpeg PRIVATE android jnigraphics)
endif()
target_compile_features(persistor_mozjpeg PRIVATE cxx_std_17)
