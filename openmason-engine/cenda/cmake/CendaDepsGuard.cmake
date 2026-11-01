# Refuses a FetchContent deps dir inside the git checkout.
#
# FastNoise2/zstd/Yoga are fetched as git clones. Anywhere inside the checkout, IDEs pick them up
# as nested VCS roots and list their branches/remotes as the project's, so configure fails instead.
# The presets use ~/.cache/<project>-deps*, CI runner.temp. Shared by the engine's Cenda project and
# game-owned native projects (stonebreak-game/native).
include_guard(GLOBAL)

function(cenda_require_out_of_tree_deps)
    set(_deps_dir "${CMAKE_BINARY_DIR}/_deps")
    if(FETCHCONTENT_BASE_DIR)
        set(_deps_dir "${FETCHCONTENT_BASE_DIR}")
    endif()
    cmake_path(ABSOLUTE_PATH _deps_dir BASE_DIRECTORY "${CMAKE_BINARY_DIR}" NORMALIZE)
    set(_checkout "${CMAKE_SOURCE_DIR}")
    while(NOT EXISTS "${_checkout}/.git")
        cmake_path(GET _checkout PARENT_PATH _parent)
        if(_parent STREQUAL _checkout)
            set(_checkout "${CMAKE_SOURCE_DIR}")
            break()
        endif()
        set(_checkout "${_parent}")
    endwhile()
    cmake_path(IS_PREFIX _checkout "${_deps_dir}" NORMALIZE _in_tree)
    if(_in_tree)
        message(FATAL_ERROR
                "FETCHCONTENT_BASE_DIR (${_deps_dir}) is inside the git checkout "
                "(${_checkout}); the dependency clones would show up as nested repos in IDEs. "
                "Configure with a preset (cmake --preset release), or pass "
                "-DFETCHCONTENT_BASE_DIR=<dir outside the repo>. A stale CMakeCache.txt keeps the old "
                "value: add --fresh.")
    endif()
endfunction()
