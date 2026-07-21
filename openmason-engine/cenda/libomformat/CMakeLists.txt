add_library(omformat STATIC
        src/version.cpp)
add_library(cenda::omformat ALIAS omformat)

target_include_directories(omformat PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(omformat PRIVATE cenda_warnings)
