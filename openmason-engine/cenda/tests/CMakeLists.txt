# Plain-assert smoke tests for now; switch to Catch2/doctest via FetchContent
# once the conformance suite grows real fixtures.
add_executable(omformat_smoke smoke_test.cpp)
target_link_libraries(omformat_smoke PRIVATE cenda::omformat cenda_warnings)
add_test(NAME omformat_smoke COMMAND omformat_smoke)

if(CENDA_BUILD_KERNELS)
    add_executable(kernels_smoke kernels_smoke_test.cpp)
    target_link_libraries(kernels_smoke PRIVATE cenda_kernels cenda_warnings)
    add_test(NAME kernels_smoke COMMAND kernels_smoke)
endif()
