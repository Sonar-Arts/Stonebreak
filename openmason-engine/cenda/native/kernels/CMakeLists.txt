include(FetchContent)

# FastNoise2 is compiled statically into our shared lib, so everything needs PIC.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(FASTNOISE2_NOISETOOL OFF CACHE BOOL "" FORCE)
set(FASTNOISE2_TESTS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(FastNoise2
        GIT_REPOSITORY https://github.com/Auburn/FastNoise2.git
        GIT_TAG v1.1.1
        GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(FastNoise2)

# Treat FastNoise2 headers as system includes so its warnings don't trip our -Werror.
get_target_property(_fn2_includes FastNoise INTERFACE_INCLUDE_DIRECTORIES)
set_target_properties(FastNoise PROPERTIES
        INTERFACE_SYSTEM_INCLUDE_DIRECTORIES "${_fn2_includes}")

add_library(cenda_kernels SHARED
        src/kernels.cpp)

target_include_directories(cenda_kernels PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(cenda_kernels
        PRIVATE FastNoise cenda_warnings)
