cmake_minimum_required(VERSION 3.25)

# Stonebreak's own native library (#386, cube generator #446): the WHOLE world generator behind the
# engine's cv_generator (cenda/voxel_world.h). The engine keeps noise, the voxel runtime, the mesher
# and the UI hosts; this library builds Stonebreak's generator on top of the engine's noise contract
# (the shared FastNoise2 pin + cenda/noise_nodes.hpp, from Open Mason's Cenda SDK).
#
#     cmake --preset release && cmake --build --preset release && ctest --preset release
#
# The game finds the result via WorldgenNatives (build/<preset>/, or -Dstonebreak.worldgen.path).
project(stonebreak_native
        VERSION 0.1.0
        DESCRIPTION "Stonebreak native worldgen kernels"
        LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# The engine's native build inputs come from Open Mason (Sonar-Arts/OpenMason, #388): by default the
# Cenda SDK of the engine version the game runs with, unpacked by Maven (pom.xml, initialize). For
# the cross-repo dev loop either install your OpenMason checkout with scripts/openmason-local.sh
# (the SDK follows it), or point this at the checkout's cenda dir directly
# (-DSTONEBREAK_ENGINE_CENDA_DIR=<OpenMason>/openmason-engine/cenda); both have the same layout.
set(_sbw_default_sdk "${CMAKE_CURRENT_SOURCE_DIR}/target/openmason-cenda-sdk")
set(STONEBREAK_ENGINE_CENDA_DIR "${_sbw_default_sdk}"
        CACHE PATH "Open Mason's Cenda SDK or cenda dir (shared FastNoise2 pin + noise_nodes.hpp)")
# Build dirs configured before #388 cache the in-repo engine path, which no longer exists: move them
# to the unpacked SDK instead of failing (a failed configure means a silent Java fallback at launch).
if(NOT EXISTS "${STONEBREAK_ENGINE_CENDA_DIR}/cmake/CendaFastNoise2.cmake"
        AND EXISTS "${_sbw_default_sdk}/cmake/CendaFastNoise2.cmake")
    message(STATUS "STONEBREAK_ENGINE_CENDA_DIR (${STONEBREAK_ENGINE_CENDA_DIR}) is gone; using ${_sbw_default_sdk}")
    set(STONEBREAK_ENGINE_CENDA_DIR "${_sbw_default_sdk}" CACHE PATH
            "Open Mason's Cenda SDK or cenda dir (shared FastNoise2 pin + noise_nodes.hpp)" FORCE)
endif()
cmake_path(ABSOLUTE_PATH STONEBREAK_ENGINE_CENDA_DIR NORMALIZE)
if(NOT EXISTS "${STONEBREAK_ENGINE_CENDA_DIR}/cmake/CendaFastNoise2.cmake")
    message(FATAL_ERROR "STONEBREAK_ENGINE_CENDA_DIR (${STONEBREAK_ENGINE_CENDA_DIR}) holds no Open Mason "
            "Cenda SDK. Unpack it with `mvn -f stonebreak-game/native/pom.xml initialize` (from the "
            "repository root), or pass -DSTONEBREAK_ENGINE_CENDA_DIR=<OpenMason>/openmason-engine/cenda.")
endif()
if(EXISTS "${STONEBREAK_ENGINE_CENDA_DIR}/cenda-sdk.properties")
    file(STRINGS "${STONEBREAK_ENGINE_CENDA_DIR}/cenda-sdk.properties" _sbw_sdk_version REGEX "^version=")
    message(STATUS "Open Mason Cenda SDK: ${_sbw_sdk_version}")
endif()
list(APPEND CMAKE_MODULE_PATH "${STONEBREAK_ENGINE_CENDA_DIR}/cmake")

include(CendaDepsGuard)
cenda_require_out_of_tree_deps()

option(STONEBREAK_WARNINGS_AS_ERRORS "Treat compiler warnings as errors" ON)
add_library(stonebreak_warnings INTERFACE)
if(MSVC)
    target_compile_options(stonebreak_warnings INTERFACE /W3 /permissive- /utf-8)
else()
    target_compile_options(stonebreak_warnings INTERFACE
            -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wsign-conversion)
    if(STONEBREAK_WARNINGS_AS_ERRORS)
        target_compile_options(stonebreak_warnings INTERFACE -Werror)
    endif()
endif()

# Same FastNoise2 as the engine — never pin a version here (see the module's header).
include(CendaFastNoise2)

# The cube generator implements Open Mason's cv_generator (cenda/voxel_world.h, types only - the
# library never links libcenda_kernels). Cenda SDKs older than the cubic voxel runtime (2026-11-17)
# do not carry voxel_world.h yet: then the header is taken from a sibling OpenMason checkout
# (STONEBREAK_VOXEL_WORLD_INCLUDE), while FastNoise2 still comes from STONEBREAK_ENGINE_CENDA_DIR.
set(_sbw_vw_default "${STONEBREAK_ENGINE_CENDA_DIR}/native/kernels/include")
if(NOT EXISTS "${_sbw_vw_default}/cenda/voxel_world.h")
    get_filename_component(_sbw_sibling
            "${CMAKE_CURRENT_SOURCE_DIR}/../../../OpenMason/openmason-engine/cenda/native/kernels/include" ABSOLUTE)
    set(_sbw_vw_default "${_sbw_sibling}")
endif()
set(STONEBREAK_VOXEL_WORLD_INCLUDE "${_sbw_vw_default}" CACHE PATH
        "Directory holding cenda/voxel_world.h (Open Mason cubic voxel runtime ABI)")
if(NOT EXISTS "${STONEBREAK_VOXEL_WORLD_INCLUDE}/cenda/voxel_world.h")
    message(FATAL_ERROR "cenda/voxel_world.h not found under STONEBREAK_VOXEL_WORLD_INCLUDE "
            "(${STONEBREAK_VOXEL_WORLD_INCLUDE}). Pass -DSTONEBREAK_ENGINE_CENDA_DIR=<OpenMason>/openmason-engine/cenda "
            "or -DSTONEBREAK_VOXEL_WORLD_INCLUDE=<dir>.")
endif()

set(STONEBREAK_WORLDGEN_SOURCES
        src/api.cpp
        src/boulders.cpp
        src/carvers.cpp
        src/column.cpp
        src/cube.cpp
        src/features.cpp
        src/rocks.cpp
        src/shape.cpp)

# One object library feeds the shared library and the tests (which call internals directly).
add_library(stonebreak_worldgen_obj OBJECT ${STONEBREAK_WORLDGEN_SOURCES})
target_include_directories(stonebreak_worldgen_obj
        PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include ${CMAKE_CURRENT_SOURCE_DIR}/src
        PUBLIC ${STONEBREAK_ENGINE_CENDA_DIR}/native/kernels/include
        PUBLIC ${STONEBREAK_VOXEL_WORLD_INCLUDE})
target_link_libraries(stonebreak_worldgen_obj PUBLIC FastNoise PRIVATE stonebreak_warnings)
target_compile_definitions(stonebreak_worldgen_obj PUBLIC STONEBREAK_WORLDGEN_BUILD)
set_target_properties(stonebreak_worldgen_obj PROPERTIES
        POSITION_INDEPENDENT_CODE ON
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)

add_library(stonebreak_worldgen SHARED $<TARGET_OBJECTS:stonebreak_worldgen_obj>)
target_include_directories(stonebreak_worldgen PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(stonebreak_worldgen PRIVATE FastNoise)

# Only the sbw_* ABI is exported (SBW_API): our own code is hidden by default, and the static
# FastNoise2 copy stays out of the export table on ELF so it can never interpose on the engine
# library's copy in the same process.
set_target_properties(stonebreak_worldgen PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_options(stonebreak_worldgen PRIVATE "LINKER:--exclude-libs,ALL"
            "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/src/exports.map")
    set_property(TARGET stonebreak_worldgen APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/exports.map")
endif()

# Float-op portability: the carver's double splines and the generator's exact ports mirror Java's
# IEEE ops (FusedChunkGenParityTest pins byte identity). FMA contraction would break that — keep
# the TUs contraction-free, exactly as the engine's kernels are built.
if(MSVC)
    target_compile_options(stonebreak_worldgen_obj PRIVATE /fp:precise)
else()
    target_compile_options(stonebreak_worldgen_obj PRIVATE -ffp-contract=off)
endif()

include(CTest)
if(BUILD_TESTING)
    add_subdirectory(tests)
endif()

# DaedalusTGM terrain pipeline (the native inference engine, phases 2-3; Dev Working/docs/DaedalusTGM-Exp-2-Native-
# Engine.md): libstonebreak_terrain, C ABI sbtg_* (include/stonebreak/terrain.h), on Open Mason's engine runtime (ci_*,
# cenda/infer.h, opened at run time). The header comes from the Cenda SDK the game builds against (unpacked into
# target/openmason-cenda-sdk) -- never from a sibling OpenMason working tree unless STONEBREAK_CENDA_INFER_INCLUDE names
# one explicitly (dev loop). An SDK without infer.h (older engine) skips the library. Its warnings are never errors, and
# build-worldgen.sh builds the worldgen lib first, so the terrain lib can never break the launch build.
option(STONEBREAK_BUILD_TERRAIN "Build libstonebreak_terrain (DaedalusTGM pipeline on the engine runtime)" ON)
if(STONEBREAK_BUILD_TERRAIN)
    set(STONEBREAK_CENDA_INFER_INCLUDE "${STONEBREAK_ENGINE_CENDA_DIR}/native/kernels/include"
            CACHE PATH "Directory holding cenda/infer.h (default: the Cenda SDK)")
    if(EXISTS "${STONEBREAK_CENDA_INFER_INCLUDE}/cenda/infer.h")
        add_subdirectory(src/terrain)
    else()
        message(STATUS "libstonebreak_terrain skipped: no cenda/infer.h under ${STONEBREAK_CENDA_INFER_INCLUDE}")
    endif()
endif()
