include(FetchContent)

# FastNoise2 + zstd are compiled statically into our shared lib: PIC required.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(FASTNOISE2_NOISETOOL OFF CACHE BOOL "" FORCE)
set(FASTNOISE2_TESTS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(FastNoise2
        GIT_REPOSITORY https://github.com/Auburn/FastNoise2.git
        GIT_TAG v1.1.1
        GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(FastNoise2)

set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(ZSTD_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(zstd
        GIT_REPOSITORY https://github.com/facebook/zstd.git
        GIT_TAG v1.5.7
        GIT_SHALLOW TRUE
        SOURCE_SUBDIR build/cmake)
FetchContent_MakeAvailable(zstd)

# Treat third-party headers as system includes so their warnings don't trip -Werror.
get_target_property(_fn2_includes FastNoise INTERFACE_INCLUDE_DIRECTORIES)
set_target_properties(FastNoise PROPERTIES
        INTERFACE_SYSTEM_INCLUDE_DIRECTORIES "${_fn2_includes}")
get_target_property(_zstd_includes libzstd_static INTERFACE_INCLUDE_DIRECTORIES)
set_target_properties(libzstd_static PROPERTIES
        INTERFACE_SYSTEM_INCLUDE_DIRECTORIES "${_zstd_includes}")

add_library(cenda_kernels SHARED
        src/kernels.cpp
        src/mesher.cpp
        src/carver.cpp
        src/generator.cpp
        src/zstd_codec.cpp)

target_include_directories(cenda_kernels PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(cenda_kernels
        PRIVATE FastNoise libzstd_static cenda_warnings)

# Float-op portability: the mesher's lighting math must match Java's IEEE
# single ops exactly (parity-tested), and the carver's double splines mirror
# Java doubles. FMA contraction (-ffp-contract=fast, GCC's -O default) would
# fuse a*b+c and break that — keep our kernel TUs contraction-free.
target_compile_options(cenda_kernels PRIVATE -ffp-contract=off)
