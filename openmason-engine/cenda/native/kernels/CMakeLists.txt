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

# Lua 5.5 for UI scripting (#283/#292). The tarball has no CMake build, so we
# compile the core + the curated libraries ourselves. io/os/debug/package and
# linit are deliberately NOT compiled: the sandbox cannot reach what is not in
# the binary. Pinned by SHA-256 (verified against lua.org 2026-10-03).
FetchContent_Declare(lua
        URL https://www.lua.org/ftp/lua-5.5.1.tar.gz
        URL_HASH SHA256=1c4b4068d67061f2a2231ad2b5422e77acea1487ea9890f6320af614f4373dce
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        # Deadline poll at loop back-jumps/calls — see lua/cenda_lua_interrupt.h.
        PATCH_COMMAND ${CMAKE_COMMAND} -DLUA_SRC_DIR=<SOURCE_DIR>
                -P ${CMAKE_CURRENT_SOURCE_DIR}/lua/patch_lvm.cmake)
FetchContent_MakeAvailable(lua)

set(CENDA_LUA_SOURCES)
foreach(_src lapi lcode lctype ldebug ldo ldump lfunc lgc llex lmem lobject
        lopcodes lparser lstate lstring ltable ltm lundump lvm lzio
        lauxlib lbaselib lcorolib lmathlib lstrlib ltablib lutf8lib)
    list(APPEND CENDA_LUA_SOURCES ${lua_SOURCE_DIR}/src/${_src}.c)
endforeach()
add_library(cenda_lua STATIC ${CENDA_LUA_SOURCES})
target_include_directories(cenda_lua SYSTEM PUBLIC ${lua_SOURCE_DIR}/src ${CMAKE_CURRENT_SOURCE_DIR}/lua)
# Keep lua_* symbols out of the shared lib's export table; only cl_* is ABI.
set_target_properties(cenda_lua PROPERTIES C_VISIBILITY_PRESET hidden C_STANDARD 99)

# Yoga flexbox for the UI layout spike (#283 decides Yoga-in-Cenda vs Java for #287).
FetchContent_Declare(yoga
        GIT_REPOSITORY https://github.com/facebook/yoga.git
        GIT_TAG v3.2.1
        GIT_SHALLOW TRUE
        SOURCE_SUBDIR yoga)
FetchContent_MakeAvailable(yoga)
get_target_property(_yoga_includes yogacore INTERFACE_INCLUDE_DIRECTORIES)
set_target_properties(yogacore PROPERTIES INTERFACE_SYSTEM_INCLUDE_DIRECTORIES "${_yoga_includes}")

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
        src/zstd_codec.cpp
        src/lua_host.cpp
        src/flex_host.cpp)

target_include_directories(cenda_kernels PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(cenda_kernels
        PRIVATE FastNoise libzstd_static cenda_lua yogacore cenda_warnings)

# Float-op portability: the mesher's lighting math must match Java's IEEE
# single ops exactly (parity-tested), and the carver's double splines mirror
# Java doubles. FMA contraction (-ffp-contract=fast, GCC's -O default) would
# fuse a*b+c and break that — keep our kernel TUs contraction-free.
if(MSVC)
    # /fp:precise never contracts a*b+c into an FMA (that needs /fp:contract).
    target_compile_options(cenda_kernels PRIVATE /fp:precise)
    # The C ABI (ck_*, cl_*, cf_*) is plain extern "C"; export it from the DLL without
    # per-function __declspec. Lua stays a static lib, so lua_* are not exported.
    set_target_properties(cenda_kernels PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
else()
    target_compile_options(cenda_kernels PRIVATE -ffp-contract=off)
endif()
