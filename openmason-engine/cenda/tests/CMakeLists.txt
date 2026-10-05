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

    add_executable(lua_host lua_host_test.cpp)
    target_link_libraries(lua_host PRIVATE cenda_kernels cenda_warnings)
    add_test(NAME lua_host COMMAND lua_host)

    add_executable(flex_host flex_host_test.cpp)
    target_link_libraries(flex_host PRIVATE cenda_kernels cenda_warnings)
    add_test(NAME flex_host COMMAND flex_host)
endif()
