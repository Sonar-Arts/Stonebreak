cmake_minimum_required(VERSION 3.25)

project(cenda
        VERSION 0.1.0
        DESCRIPTION "Cenda — C++ Open Mason: format library, tool shell, and native chunk kernels"
        LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(CENDA_WARNINGS_AS_ERRORS "Treat compiler warnings as errors" ON)

# Shared strict-warnings interface target; link it PRIVATE from every Cenda target.
add_library(cenda_warnings INTERFACE)
if(MSVC)
    # MSVC's /W4 diagnoses differently from GCC/Clang's -Wconversion set; the GCC/Clang build
    # is the warning gate, so MSVC warns without failing the build.
    target_compile_options(cenda_warnings INTERFACE /W3 /permissive- /utf-8)
else()
    target_compile_options(cenda_warnings INTERFACE
            -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wsign-conversion)
    if(CENDA_WARNINGS_AS_ERRORS)
        target_compile_options(cenda_warnings INTERFACE -Werror)
    endif()
endif()

option(CENDA_BUILD_KERNELS "Build the native chunk-kernels shared lib (FastNoise2 via FetchContent)" ON)

include(CTest)

add_subdirectory(libomformat)
if(CENDA_BUILD_KERNELS)
    add_subdirectory(native/kernels)
endif()

if(BUILD_TESTING)
    add_subdirectory(tests)
endif()

# Future subdirectories, added as the conversion progresses:
#   apps/viewer      — GLFW + OpenGL + ImGui viewport shell (tool track)
#   native/kernels/  — grows more kernels over time (binary greedy mesher, io_uring streaming)
