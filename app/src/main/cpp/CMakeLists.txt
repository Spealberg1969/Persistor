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

# Leptonica supplies the 1-bpp Pix primitives used by the lossless JBIG2 generic
# region encoder. Disable its optional image I/O codecs; TIFF is built separately.
set(BUILD_PROG OFF CACHE BOOL "" FORCE)
set(SW_BUILD OFF CACHE BOOL "" FORCE)
set(ENABLE_ZLIB OFF CACHE BOOL "" FORCE)
set(ENABLE_PNG OFF CACHE BOOL "" FORCE)
set(ENABLE_GIF OFF CACHE BOOL "" FORCE)
set(ENABLE_JPEG OFF CACHE BOOL "" FORCE)
set(ENABLE_TIFF OFF CACHE BOOL "" FORCE)
set(ENABLE_WEBP OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENJPEG OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/leptonica)

# libtiff provides the CCITT Group 4 TIFF encoder. No CLI, tests, docs, or
# optional image codecs are needed in the Android app.
set(tiff-tools OFF CACHE BOOL "" FORCE)
set(tiff-tests OFF CACHE BOOL "" FORCE)
set(tiff-contrib OFF CACHE BOOL "" FORCE)
set(tiff-docs OFF CACHE BOOL "" FORCE)
set(tiff-install OFF CACHE BOOL "" FORCE)
set(tiff-deprecated OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/libtiff)

# Use the upstream JBIG2 encoder API directly instead of its CLI/test targets.
add_library(persistor_jbig2 STATIC
    third_party/jbig2enc/src/jbig2arith.cc
    third_party/jbig2enc/src/jbig2comparator.cc
    third_party/jbig2enc/src/jbig2enc.cc
    third_party/jbig2enc/src/jbig2sym.cc
)
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/third_party/jbig2enc/VERSION" JBIG2ENC_VERSION)
string(STRIP "${JBIG2ENC_VERSION}" JBIG2ENC_VERSION)
target_compile_definitions(persistor_jbig2 PRIVATE VERSION="${JBIG2ENC_VERSION}")
target_include_directories(persistor_jbig2 PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/jbig2enc/src"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/leptonica/src"
    "${CMAKE_CURRENT_BINARY_DIR}/third_party/leptonica/src"
)
target_link_libraries(persistor_jbig2 PUBLIC leptonica)
target_compile_features(persistor_jbig2 PRIVATE cxx_std_17)

add_library(persistor_document_codecs SHARED document_codecs_jni.cpp)
target_include_directories(persistor_document_codecs PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/jbig2enc/src"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/leptonica/src"
    "${CMAKE_CURRENT_BINARY_DIR}/third_party/leptonica/src"
)
target_link_libraries(persistor_document_codecs PRIVATE tiff persistor_jbig2 leptonica)
if(ANDROID)
    target_link_libraries(persistor_document_codecs PRIVATE android jnigraphics)
endif()
target_compile_features(persistor_document_codecs PRIVATE cxx_std_17)
target_compile_options(persistor_document_codecs PRIVATE -Wall -Wextra -Wno-unused-parameter)
