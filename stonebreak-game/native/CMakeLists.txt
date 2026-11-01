cmake_minimum_required(VERSION 3.25)

# Stonebreak's own native library (#386): the worldgen kernels (worm carver + fused chunk
# generator) that used to live in the engine's Cenda kernels. The engine keeps noise, the mesher,
# zstd and the UI hosts; this library only builds Stonebreak's generator on top of the engine's
# noise contract (the shared FastNoise2 pin + cenda/noise_nodes.hpp), so channel values stay
# bit-identical to the engine's ck_gen_grid_*.
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

set(STONEBREAK_ENGINE_CENDA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../openmason-engine/cenda"
        CACHE PATH "The engine's Cenda project (shared FastNoise2 pin + noise_nodes.hpp)")
cmake_path(ABSOLUTE_PATH STONEBREAK_ENGINE_CENDA_DIR NORMALIZE)
if(NOT EXISTS "${STONEBREAK_ENGINE_CENDA_DIR}/cmake/CendaFastNoise2.cmake")
    message(FATAL_ERROR "STONEBREAK_ENGINE_CENDA_DIR (${STONEBREAK_ENGINE_CENDA_DIR}) is not the engine's cenda dir")
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

add_library(stonebreak_worldgen SHARED
        src/worldgen.cpp
        src/carver.cpp
        src/generator.cpp)
target_include_directories(stonebreak_worldgen
        PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
        PRIVATE ${STONEBREAK_ENGINE_CENDA_DIR}/native/kernels/include)
target_link_libraries(stonebreak_worldgen PRIVATE FastNoise stonebreak_warnings)
target_compile_definitions(stonebreak_worldgen PRIVATE STONEBREAK_WORLDGEN_BUILD)

# Only the sbw_* ABI is exported (SBW_API): our own code is hidden by default, and the static
# FastNoise2 copy stays out of the export table on ELF so it can never interpose on the engine
# library's copy in the same process.
set_target_properties(stonebreak_worldgen PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_options(stonebreak_worldgen PRIVATE "LINKER:--exclude-libs,ALL")
endif()

# Float-op portability: the carver's double splines and the generator's exact ports mirror Java's
# IEEE ops (FusedChunkGenParityTest pins byte identity). FMA contraction would break that — keep
# the TUs contraction-free, exactly as the engine's kernels are built.
if(MSVC)
    target_compile_options(stonebreak_worldgen PRIVATE /fp:precise)
else()
    target_compile_options(stonebreak_worldgen PRIVATE -ffp-contract=off)
endif()

include(CTest)
if(BUILD_TESTING)
    add_subdirectory(tests)
endif()
