find_package(Threads REQUIRED)
add_executable(worldgen_cube_test cube_test.cpp)
target_link_libraries(worldgen_cube_test PRIVATE stonebreak_worldgen_obj stonebreak_warnings Threads::Threads)
target_compile_options(worldgen_cube_test PRIVATE $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-ffp-contract=off>)
add_test(NAME worldgen COMMAND worldgen_cube_test)
set_tests_properties(worldgen PROPERTIES TIMEOUT 1800)
# The shared library's exported surface (sbw_* only) - a plain C caller through the public header.
add_executable(worldgen_abi_test abi_test.c)
target_link_libraries(worldgen_abi_test PRIVATE stonebreak_worldgen)
target_include_directories(worldgen_abi_test PRIVATE ${STONEBREAK_VOXEL_WORLD_INCLUDE})
add_test(NAME worldgen_abi COMMAND worldgen_abi_test)
if(WIN32)
    set_tests_properties(worldgen_abi PROPERTIES
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
