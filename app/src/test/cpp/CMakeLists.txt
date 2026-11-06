cmake_minimum_required(VERSION 3.16)
project(glidelive_host_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

add_executable(engine_tests
    engine_tests.cpp
    ${SRC}/sequencer.cpp
    ${SRC}/chord_synth.cpp
    ${SRC}/synth_kit.cpp)
target_include_directories(engine_tests PRIVATE ${SRC})
target_compile_options(engine_tests PRIVATE -Wall)
find_package(Threads REQUIRED)
target_link_libraries(engine_tests PRIVATE Threads::Threads)
