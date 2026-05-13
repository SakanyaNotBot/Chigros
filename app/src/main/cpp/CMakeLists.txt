cmake_minimum_required(VERSION 3.22.1)
project(audio_native)

# We vendor Oboe source under app/src/main/cpp/oboe to avoid Prefab build issues on some Windows setups.
add_subdirectory(oboe)

add_library(audio_native SHARED
        audio_engine.cpp
        audio_decoder.cpp
        jni_bridge.cpp
)

target_include_directories(audio_native PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/oboe/include
)

target_compile_features(audio_native PRIVATE cxx_std_17)

target_link_libraries(audio_native
        oboe
        OpenSLES
        android
        log
        dl
)
