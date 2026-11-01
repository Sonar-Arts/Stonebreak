add_executable(worldgen_test worldgen_test.cpp)
target_link_libraries(worldgen_test PRIVATE stonebreak_worldgen stonebreak_warnings)
add_test(NAME worldgen COMMAND worldgen_test)
# Windows has no rpath: put the DLL's folder on the test PATH (see the engine's cenda/tests).
if(WIN32)
    set_tests_properties(worldgen PROPERTIES
            ENVIRONMENT_MODIFICATION "PATH=path_list_prepend:$<TARGET_FILE_DIR:stonebreak_worldgen>")
endif()
set_tests_properties(worldgen PROPERTIES TIMEOUT 120)
