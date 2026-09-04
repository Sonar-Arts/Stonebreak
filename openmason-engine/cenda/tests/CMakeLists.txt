# Plain-assert smoke tests for now; switch to Catch2/doctest via FetchContent
# once the conformance suite grows real fixtures.
add_executable(omformat_smoke smoke_test.cpp)
target_link_libraries(omformat_smoke PRIVATE cenda::omformat cenda_warnings)
add_test(NAME omformat_smoke COMMAND omformat_smoke)

if(CENDA_BUILD_KERNELS)
    add_executable(kernels_smoke kernels_smoke_test.cpp)
    target_link_libraries(kernels_smoke PRIVATE cenda_kernels cenda_warnings)
    add_test(NAME kernels_smoke COMMAND kernels_smoke)

    add_executable(kernels_chunk kernels_chunk_test.cpp)
    target_link_libraries(kernels_chunk PRIVATE cenda_kernels cenda_warnings)
    add_test(NAME kernels_chunk COMMAND kernels_chunk)

    # basin_plan.hpp is header-only and depends on nothing but the standard
    # library, so its tests need neither the shared lib nor FastNoise.
    add_executable(kernels_basin kernels_basin_test.cpp)
    target_include_directories(kernels_basin PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/../native/kernels/src)
    target_link_libraries(kernels_basin PRIVATE cenda_warnings)
    add_test(NAME kernels_basin COMMAND kernels_basin)

    # river_plan.hpp is header-only too, and deliberately depends on no noise
    # backend — the meander is built from basin_plan.hpp's hash — so the whole
    # routing layer stays testable with nothing linked.
    add_executable(kernels_river kernels_river_test.cpp)
    target_include_directories(kernels_river PRIVATE
            ${CMAKE_CURRENT_SOURCE_DIR}/../native/kernels/src)
    target_link_libraries(kernels_river PRIVATE cenda_warnings)
    add_test(NAME kernels_river COMMAND kernels_river)

    add_executable(kernels_water kernels_water_test.cpp)
    target_link_libraries(kernels_water PRIVATE cenda_kernels cenda_warnings)
    add_test(NAME kernels_water COMMAND kernels_water)
endif()
