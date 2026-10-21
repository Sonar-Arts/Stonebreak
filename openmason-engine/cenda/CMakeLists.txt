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

# FastNoise2/zstd/Yoga are fetched as git clones. Anywhere inside this checkout, IDEs pick them up
# as nested VCS roots and list their branches/remotes as the project's, so refuse an in-tree deps
# dir (FetchContent's default is <build>/_deps). The presets use ~/.cache/cenda-deps*, CI runner.temp.
if(CENDA_BUILD_KERNELS)
    set(_cenda_deps_dir "${CMAKE_BINARY_DIR}/_deps")
    if(FETCHCONTENT_BASE_DIR)
        set(_cenda_deps_dir "${FETCHCONTENT_BASE_DIR}")
    endif()
    cmake_path(ABSOLUTE_PATH _cenda_deps_dir BASE_DIRECTORY "${CMAKE_BINARY_DIR}" NORMALIZE)
    set(_cenda_checkout "${CMAKE_SOURCE_DIR}")
    while(NOT EXISTS "${_cenda_checkout}/.git")
        cmake_path(GET _cenda_checkout PARENT_PATH _cenda_parent)
        if(_cenda_parent STREQUAL _cenda_checkout)
            set(_cenda_checkout "${CMAKE_SOURCE_DIR}")
            break()
        endif()
        set(_cenda_checkout "${_cenda_parent}")
    endwhile()
    cmake_path(IS_PREFIX _cenda_checkout "${_cenda_deps_dir}" NORMALIZE _cenda_deps_in_tree)
    if(_cenda_deps_in_tree)
        message(FATAL_ERROR
                "FETCHCONTENT_BASE_DIR (${_cenda_deps_dir}) is inside the git checkout "
                "(${_cenda_checkout}); the dependency clones would show up as nested repos in IDEs. "
                "Configure with a preset (cmake --preset release), or pass "
                "-DFETCHCONTENT_BASE_DIR=<dir outside the repo>. A stale CMakeCache.txt keeps the old "
                "value: add --fresh.")
    endif()
endif()

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
