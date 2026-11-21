find_package(Threads REQUIRED)
add_executable(worldgen_cube_test cube_test.cpp)
target_link_libraries(worldgen_cube_test PRIVATE stonebreak_worldgen_obj stonebreak_warnings Threads::Threads)
target_compile_options(worldgen_cube_test PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>)
add_test(NAME worldgen COMMAND worldgen_cube_test)
set_tests_properties(worldgen PROPERTIES TIMEOUT 1800)
# Boulders (SBW_FLAG_BOULDERS): rock shapes + SBRB bank, determinism / order independence / cube-border wholeness /
# column surface with boulders, flag-off identity. `worldgen_boulder_test --bake <file>` writes the placeholder bank.
add_executable(worldgen_boulder_test boulder_test.cpp)
target_link_libraries(worldgen_boulder_test PRIVATE stonebreak_worldgen_obj stonebreak_warnings Threads::Threads)
target_compile_options(worldgen_boulder_test PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>)
add_test(NAME worldgen_boulders COMMAND worldgen_boulder_test)
set_tests_properties(worldgen_boulders PROPERTIES TIMEOUT 900)
# Land surface materials (SBW_FLAG_LAND_SURFACE, agent L): channel shares -> rock / talus / broken ground, no-data and
# flag-off identity, pointwise (seam-free) results.
add_executable(worldgen_land_surface_test land_surface_test.cpp)
target_link_libraries(worldgen_land_surface_test PRIVATE stonebreak_worldgen_obj stonebreak_warnings Threads::Threads)
target_compile_options(worldgen_land_surface_test PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>)
add_test(NAME worldgen_land_surface COMMAND worldgen_land_surface_test)
# Sea-floor materials (SBW_FLAG_SEA_FLOOR, FX1 / review-native F3): no 32-block edge bias (slope and shelter read across
# the cube edge), pointwise across build orders, only ocean columns change, a failed neighbour upcall fails the tile.
add_executable(worldgen_standard_shape_test standard_shape_test.cpp)
target_link_libraries(worldgen_standard_shape_test PRIVATE stonebreak_worldgen_obj stonebreak_warnings Threads::Threads)
target_compile_options(worldgen_standard_shape_test PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>)
add_test(NAME worldgen_standard_shape COMMAND worldgen_standard_shape_test)

add_executable(worldgen_sea_floor_test sea_floor_test.cpp)
target_link_libraries(worldgen_sea_floor_test PRIVATE stonebreak_worldgen_obj stonebreak_warnings Threads::Threads)
target_compile_options(worldgen_sea_floor_test PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>)
add_test(NAME worldgen_sea_floor COMMAND worldgen_sea_floor_test)
# Vegetation channels (surface slots 0..7, agent V): no-data identity, tree density / size / clearings, bushes, bare.
add_executable(worldgen_vegetation_test vegetation_test.cpp)
target_link_libraries(worldgen_vegetation_test PRIVATE stonebreak_worldgen_obj stonebreak_warnings Threads::Threads)
target_compile_options(worldgen_vegetation_test PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>)
add_test(NAME worldgen_vegetation COMMAND worldgen_vegetation_test)

add_executable(worldgen_blueberry_test blueberry_test.cpp)
target_link_libraries(worldgen_blueberry_test PRIVATE stonebreak_worldgen_obj stonebreak_warnings Threads::Threads)
target_compile_options(worldgen_blueberry_test PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>)
add_test(NAME worldgen_blueberry COMMAND worldgen_blueberry_test)

add_executable(worldgen_shade_test shade_test.cpp)
target_link_libraries(worldgen_shade_test PRIVATE stonebreak_worldgen_obj stonebreak_warnings Threads::Threads)
target_compile_options(worldgen_shade_test PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>)
add_test(NAME worldgen_shade COMMAND worldgen_shade_test)
# The shared library's exported surface (sbw_* only) - a plain C caller through the public header.
add_executable(worldgen_abi_test abi_test.c)
target_link_libraries(worldgen_abi_test PRIVATE stonebreak_worldgen)
target_include_directories(worldgen_abi_test PRIVATE ${STONEBREAK_VOXEL_WORLD_INCLUDE})
add_test(NAME worldgen_abi COMMAND worldgen_abi_test)
if(WIN32)
    set_tests_properties(worldgen_abi PROPERTIES
            ENVIRONMENT_MODIFICATION "PATH=path_list_prepend:$<TARGET_FILE_DIR:stonebreak_worldgen>")
endif()
# Generator lifetime (agent Q, the 2026-11-19 quit crash): destroy while sampler threads run - in-flight calls finish,
# later calls with the stale handle fail with -1 instead of reaching freed memory.
add_executable(worldgen_lifetime_test lifetime_test.cpp)
target_link_libraries(worldgen_lifetime_test PRIVATE stonebreak_worldgen Threads::Threads)
target_include_directories(worldgen_lifetime_test PRIVATE ${STONEBREAK_VOXEL_WORLD_INCLUDE})
add_test(NAME worldgen_lifetime COMMAND worldgen_lifetime_test)
set_tests_properties(worldgen_lifetime PROPERTIES TIMEOUT 300)
if(WIN32)
    set_tests_properties(worldgen_lifetime PROPERTIES
            ENVIRONMENT_MODIFICATION "PATH=path_list_prepend:$<TARGET_FILE_DIR:stonebreak_worldgen>")
endif()
# Differential golden digests (public ABI only): generate_cube, column_surface, populate and the samplers
# must stay byte-identical to tests/golden/worldgen_golden.txt (re-capture with --write on intended changes).
# The test skips (exit 77) where FastNoise2's SIMD path or libm rounds differently from the capture
# platform (fingerprint in the file): the generator's output is legitimately platform-specific there.
add_executable(worldgen_golden_test golden_test.cpp)
target_link_libraries(worldgen_golden_test PRIVATE stonebreak_worldgen FastNoise Threads::Threads)
target_include_directories(worldgen_golden_test PRIVATE ${STONEBREAK_VOXEL_WORLD_INCLUDE}
        ${STONEBREAK_ENGINE_CENDA_DIR}/native/kernels/include)
add_test(NAME worldgen_golden
         COMMAND worldgen_golden_test --check ${CMAKE_CURRENT_SOURCE_DIR}/golden/worldgen_golden.txt)
set_tests_properties(worldgen_golden PROPERTIES TIMEOUT 1800 SKIP_RETURN_CODE 77)
if(WIN32)
    set_tests_properties(worldgen_golden PROPERTIES
            ENVIRONMENT_MODIFICATION "PATH=path_list_prepend:$<TARGET_FILE_DIR:stonebreak_worldgen>")
endif()
# Streamer-like benchmark (by hand, Release build; options at the top of tests/bench.cpp).
add_executable(worldgen_bench bench.cpp)
target_link_libraries(worldgen_bench PRIVATE stonebreak_worldgen_obj Threads::Threads)
target_compile_options(worldgen_bench PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>)
