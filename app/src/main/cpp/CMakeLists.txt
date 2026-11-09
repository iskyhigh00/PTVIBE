cmake_minimum_required(VERSION 3.22.1)
project(glidelive LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(glidelive SHARED
    jni_bridge.cpp
    chord_synth.cpp
    sequencer.cpp
    audio_engine.cpp
    synth_kit.cpp)

target_compile_features(glidelive PRIVATE cxx_std_17)
target_compile_options(glidelive PRIVATE -O3 -ffast-math -Wall)
target_link_libraries(glidelive oboe::oboe android log)

# Separate shared library; reuses the same ONNX Runtime 1.20 AAR as the app.
add_subdirectory(pocket)
