# AVIF engine for Novelfa's native comic viewer: libavif (decode
# only) over libaom, built from source at configure time.
#
# Why FetchContent instead of vendored sources: the GitHub
# connector cannot push binaries, and libaom is ~1000 files —
# vendoring it would make every push enormous. The recipe below
# downloads the two pinned releases on the build machine (a
# normal PC build has network; the VM verified the same recipe
# against local copies via FETCHCONTENT_SOURCE_DIR_*).
#
# Versions are pinned, not floating: aom v3.14.1 is the version
# libavif's own ext/aom.cmd pins for libavif 1.4.2, and libavif
# 1.4.2 is the latest stable. A newer aom against this libavif
# is untested and must not be picked up silently.
#
# ABIs: Gradle's externalNativeBuild builds this per ABI in
# defaultConfig.ndk.abiFilters (arm64-v8a + armeabi-v7a). The
# JNI lib is shared; aom + libavif are static inside it (PIC
# on, so the static archives link into the .so cleanly).
#
# If the native lib is missing at runtime (old 32-bit-only
# device outside the ABI filter, or a build without NDK), the
# Kotlin side degrades gracefully: AvifDecoder.isAvailable()
# is false and AVIF pages take the normal failure placeholder
# instead of crashing. Every other format is unaffected.

cmake_minimum_required(VERSION 3.16)
project(novelfa_avif C)

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- AV1 decoder: libaom (CMake build, same options as
# libavif's own ext/aom.cmd) ---
FetchContent_Declare(
    aom
    GIT_REPOSITORY https://aomedia.googlesource.com/aom
    GIT_TAG v3.14.1
)
set(ENABLE_DOCS OFF CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTDATA OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(CONFIG_PIC ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(aom)

# aom's own CMakeLists only exports its include dirs for the
# INSTALL interface ($<INSTALL_INTERFACE:include>); a
# FetchContent consumer gets the `aom` target with NO build-time
# includes, so libavif's codec_aom.c cannot find
# aom/aom_decoder.h. Directory-level includes propagate to
# subdirectories added after this call, so libavif (added
# below) picks them up: the source tree (aom/*.h) and the build
# tree (generated config/aom_config.h and rtcd headers).
include_directories(
    ${aom_SOURCE_DIR}
    ${aom_BINARY_DIR}
    ${aom_BINARY_DIR}/config
)

# --- AVIF container: libavif, decode only ---
# AVIF_CODEC_AOM=SYSTEM means "use the aom target if it already
# exists" — which it does, from the block above — so no system
# aom install is required on the build machine.
FetchContent_Declare(
    libavif
    GIT_REPOSITORY https://github.com/AOMediaCodec/libavif.git
    GIT_TAG v1.4.2
)
set(AVIF_CODEC_AOM SYSTEM CACHE STRING "" FORCE)
set(AVIF_CODEC_AOM_DECODE ON CACHE BOOL "" FORCE)
set(AVIF_CODEC_AOM_ENCODE OFF CACHE BOOL "" FORCE)
set(AVIF_CODEC_DAV1D OFF CACHE BOOL "" FORCE)
set(AVIF_CODEC_LIBGAV1 OFF CACHE BOOL "" FORCE)
set(AVIF_CODEC_RAV1E OFF CACHE BOOL "" FORCE)
set(AVIF_CODEC_SVT OFF CACHE BOOL "" FORCE)
# No libyuv: libavif's internal YUV->RGB is fast enough for
# still pages, and it removes a third dependency.
set(AVIF_LIBYUV OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_APPS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(AVIF_ENABLE_WERROR OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(libavif)

add_library(novelfa_avif SHARED
    novelfa_avif_core.c
    novelfa_avif_jni.c
)
# avif: the libavif static target. jnigraphics: AndroidBitmap
# lock/unlock. log/android: linked for completeness with the
# NDK bitmap API (no logging calls are made today).
target_link_libraries(novelfa_avif PRIVATE avif jnigraphics log android)
