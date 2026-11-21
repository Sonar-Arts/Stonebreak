# libstonebreak_terrain: the DaedalusTGM terrain pipeline in C++ (phase 2 of the native inference engine), on Open
# Mason's engine runtime (ci_*), opened at run time. Only cenda/infer.h is needed to build (types); no CUDA toolkit.
set(_sbtg_gen ${CMAKE_CURRENT_BINARY_DIR}/generated)
# The DaedalusTGM kernel sources (single source of truth: the same files kernels/native.py compiles), embedded as
# <stem>.cu.inc; a missing tree (a stripped checkout) is an error only for stages that include them.
get_filename_component(SBTG_CSRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../Models/DaedalusTGM-Exp/terrain_slm/kernels/csrc"
        ABSOLUTE)
set(_sbtg_incs)
# Stage directories built into the library (default: all). Parallel development builds a subset in its own build dir:
# -DSBTG_STAGES="common;controls".
set(SBTG_STAGES "" CACHE STRING "Stage directories under stages/ to build (empty = all)")
if(SBTG_STAGES)
    set(_sbtg_stage_dirs)
    foreach(_s ${SBTG_STAGES})
        list(APPEND _sbtg_stage_dirs ${CMAKE_CURRENT_SOURCE_DIR}/stages/${_s})
    endforeach()
else()
    file(GLOB _sbtg_stage_dirs LIST_DIRECTORIES true ${CMAKE_CURRENT_SOURCE_DIR}/stages/*)
endif()
set(_sbtg_stage_cu)
set(_sbtg_stage_cpp)
foreach(_d ${_sbtg_stage_dirs})
    if(IS_DIRECTORY ${_d})
        file(GLOB _c CONFIGURE_DEPENDS ${_d}/cuda/*.cu)
        file(GLOB _p CONFIGURE_DEPENDS ${_d}/*.cpp)
        list(APPEND _sbtg_stage_cu ${_c})
        list(APPEND _sbtg_stage_cpp ${_p})
    endif()
endforeach()
file(GLOB _sbtg_cu CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_SOURCE_DIR}/cuda/*.cu
        ${CMAKE_CURRENT_SOURCE_DIR}/core/cuda/*.cu)
list(APPEND _sbtg_cu ${_sbtg_stage_cu})
set(SBTG_CSRC_FILES blend.cu controls2.cu river.cu conv_fp8.cu conv.cu hconv.cu pixnorm.cu controls.cu)
foreach(_f ${SBTG_CSRC_FILES})
    if(EXISTS "${SBTG_CSRC}/${_f}")
        list(APPEND _sbtg_cu "${SBTG_CSRC}/${_f}")
    endif()
endforeach()
foreach(_in ${_sbtg_cu})
    get_filename_component(_k ${_in} NAME_WE)
    get_filename_component(_d ${_in} DIRECTORY)
    set(_pfx "")
    if(_d STREQUAL SBTG_CSRC)
        set(_pfx "csrc_")
    endif()
    set(_out ${_sbtg_gen}/${_pfx}${_k}.cu.inc)
    file(GLOB _deps ${_d}/*.cuh ${_d}/*.h)
    add_custom_command(OUTPUT ${_out}
            COMMAND ${CMAKE_COMMAND} -DIN=${_in} -DOUT=${_out} -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedText.cmake
            DEPENDS ${_in} ${_deps} ${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedText.cmake
            COMMENT "Embedding ${_pfx}${_k}.cu")
    list(APPEND _sbtg_incs ${_out})
endforeach()
file(GLOB _sbtg_srcs CONFIGURE_DEPENDS
        ${CMAKE_CURRENT_SOURCE_DIR}/core/*.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/gen/*.cpp)
list(APPEND _sbtg_srcs ${_sbtg_stage_cpp})
# Host C++ shared with the Python side (compiled here from the same file): hydro.cpp (fill / D8 / accumulation).
if(EXISTS "${SBTG_CSRC}/hydro.cpp")
    list(APPEND _sbtg_srcs "${SBTG_CSRC}/hydro.cpp")
endif()

add_library(stonebreak_terrain SHARED terrain.cpp ${_sbtg_srcs} ${_sbtg_incs})
if(WIN32)
    set_target_properties(stonebreak_terrain PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
    # <windows.h> (ci.hpp, terrain.cpp) would otherwise define min/max macros (std::min(...) stops parsing) and
    # MSVC's <cmath> only offers M_PI under _USE_MATH_DEFINES.
    target_compile_definitions(stonebreak_terrain PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN _USE_MATH_DEFINES)
endif()
target_include_directories(stonebreak_terrain PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../include ${CMAKE_CURRENT_SOURCE_DIR} ${STONEBREAK_CENDA_INFER_INCLUDE} ${_sbtg_gen})
target_link_libraries(stonebreak_terrain PRIVATE ${CMAKE_DL_LIBS})
# The worldgen warning set, never as errors: a new compiler warning here must not break the game's launch build.
if(MSVC)
    target_compile_options(stonebreak_terrain PRIVATE /W3 /permissive- /utf-8)
else()
    target_compile_options(stonebreak_terrain PRIVATE -Wall -Wextra -Wpedantic -Wshadow -Wconversion -Wsign-conversion
            -ffp-contract=off)   # host float math = the Python reference's, op for op (no FMA contraction)
    if(EXISTS "${SBTG_CSRC}/hydro.cpp")
        set_source_files_properties("${SBTG_CSRC}/hydro.cpp" PROPERTIES COMPILE_OPTIONS "-w")
    endif()
endif()
set_target_properties(stonebreak_terrain PROPERTIES CXX_VISIBILITY_PRESET hidden)
if(UNIX AND NOT APPLE)
    target_link_options(stonebreak_terrain PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
endif()

if(BUILD_TESTING)
    add_executable(terrain_abi terrain_abi_test.c)
    target_include_directories(terrain_abi PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../include)
    target_link_libraries(terrain_abi PRIVATE stonebreak_terrain)
    add_test(NAME terrain_abi COMMAND terrain_abi)
endif()
