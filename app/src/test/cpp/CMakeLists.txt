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

add_executable(pocket_stream_allocation_tests pocket_stream_allocation_tests.cpp)
add_executable(narration_tail_tests narration_tail_tests.cpp)
target_link_libraries(narration_tail_tests PRIVATE Threads::Threads)
enable_testing()
add_test(NAME engine COMMAND engine_tests)
add_test(NAME pocket_allocation COMMAND pocket_stream_allocation_tests)
add_test(NAME narration_tail COMMAND narration_tail_tests)
