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
# ABIs: arm64-v8a + armeabi-v7a. Gradle does NOT build this
# file — the app packages prebuilt .so files from
# src/main/jniLibs/<abi>/, and this file is the manual rebuild
# recipe (NDK 28.2.13676358, android-29, Release; on the VM
# with FETCHCONTENT_SOURCE_DIR_* pointing at local copies).
# The JNI lib is shared; aom + libavif are static inside it
# (PIC on, so the static archives link into the .so cleanly).
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
# Decoder-only: force aom's encoder OFF. Novelfa only decodes
# AVIF (the JNI side calls avifDecoder* and nothing else), and
# libavif's AVIF_CODEC_AOM_ENCODE=OFF does NOT turn aom's
# encoder off: with AVIF_CODEC_AOM=SYSTEM, libavif never
# configures aom, so aom builds with its own default
# CONFIG_AV1_ENCODER=1 and the encoder lands in the static
# archive. On arm64 the linker happened to drop it, but on
# armeabi-v7a the rtcd setup references every C fallback, so
# --gc-sections keeps ~3.5MB of encoder/DSP code that decoding
# never calls (the 6.6MB vs 2.6MB gap, Nov 2026).
# aom only honors this from the cmake command line
# (set_aom_config_var overwrites a plain cache value with its
# default), so this sets the cache entry with the exact
# helpstring CMake assigns to command-line -D variables — the
# only form aom's macro preserves. Verified: with this line,
# aom_config.h reports CONFIG_AV1_ENCODER 0 and the encoder
# objects are absent from libaom.a.
set(CONFIG_AV1_ENCODER 0 CACHE STRING "No help, variable specified on the command line." FORCE)
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

# The project() line above enables C only (the AVIF engine is C);
# unrar is C++, so enable CXX here without touching that line.
enable_language(CXX)

# --- RAR/CBR reading: unrar (decode only) ---
# Vendored RARLAB unrar source (unrarsrc-7.3.2.tar.gz, see
# unrar/license.txt) built as its own shared library,
# libnovelfa_rar.so. Only the DLL build of unrar is compiled:
# the makefile's RARDLL set (OBJECTS + LIB_OBJ with -DRARDLL),
# i.e. the 48 .cpp files listed below; the remaining .cpp files
# in unrar/ are pulled in via #include by those translation
# units (unpack15/20/30/50, crypt1/2/3/5, ...). Windows-only
# (win32acl/win32lnk) and CLI-only (recvol, rs, isnt, motw,
# rarpch) files are NOT vendored: the two win32 files are
# #included only under _WIN_ALL, the rest only by the CLI.
#
# This target is independent of the AVIF recipe above: it needs
# no FetchContent and no third-party library. Gradle does NOT
# build this file — the app packages prebuilt .so files from
# src/main/jniLibs/<abi>/, and this file is the manual rebuild
# recipe (NDK 28.2.13676358, android-29, Release). Configure
# with -DFETCHCONTENT_SOURCE_DIR_AOM / _LIBAVIF pointing at
# local copies if the AVIF fetch is unwanted; the rar target
# itself does not need them.
#
# The JNI surface is exactly five functions in
# novelfa_rar_jni.cpp (RarNative): nListEntries,
# nExtractEntryBytes, nExtractEntryToFile, nGetRarVersion,
# nGetVersion. See that file for the contract notes (stateless
# API, sequential scan for solid archives, headerOffset unused
# because the unrar DLL API exposes no header offset).
#
# License: unrar is free for unarchiving but forbids building a
# RAR archiver from it — this bridge only reads archives.
add_library(novelfa_rar SHARED
    novelfa_rar_jni.cpp
    unrar/rar.cpp
    unrar/strlist.cpp
    unrar/strfn.cpp
    unrar/pathfn.cpp
    unrar/smallfn.cpp
    unrar/global.cpp
    unrar/file.cpp
    unrar/filefn.cpp
    unrar/filcreat.cpp
    unrar/archive.cpp
    unrar/arcread.cpp
    unrar/unicode.cpp
    unrar/system.cpp
    unrar/crypt.cpp
    unrar/crc.cpp
    unrar/rawread.cpp
    unrar/encname.cpp
    unrar/resource.cpp
    unrar/match.cpp
    unrar/timefn.cpp
    unrar/rdwrfn.cpp
    unrar/consio.cpp
    unrar/options.cpp
    unrar/errhnd.cpp
    unrar/rarvm.cpp
    unrar/secpassword.cpp
    unrar/rijndael.cpp
    unrar/getbits.cpp
    unrar/sha1.cpp
    unrar/sha256.cpp
    unrar/blake2s.cpp
    unrar/hash.cpp
    unrar/extinfo.cpp
    unrar/extract.cpp
    unrar/volume.cpp
    unrar/list.cpp
    unrar/find.cpp
    unrar/unpack.cpp
    unrar/headers.cpp
    unrar/threadpool.cpp
    unrar/rs16.cpp
    unrar/cmddata.cpp
    unrar/ui.cpp
    unrar/largepage.cpp
    unrar/filestr.cpp
    unrar/scantree.cpp
    unrar/dll.cpp
    unrar/qopen.cpp
)
# Same defines as the unrar makefile's lib target:
# -DRARDLL (DLL build: silent UI, dll.hpp API),
# -DRAR_SMP (multithreaded RAR5 unpack), large-file defines.
target_compile_definitions(novelfa_rar PRIVATE
    _FILE_OFFSET_BITS=64
    _LARGEFILE_SOURCE
    RAR_SMP
    RARDLL
)
# The makefile silences the same three warnings.
target_compile_options(novelfa_rar PRIVATE
    -Wno-logical-op-parentheses
    -Wno-switch
    -Wno-dangling-else
)
target_link_libraries(novelfa_rar PRIVATE log android)
