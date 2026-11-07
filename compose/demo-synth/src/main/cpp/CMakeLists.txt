cmake_minimum_required(VERSION 3.22.1)
project(tuxfluidsynth LANGUAGES CXX)
if(NOT EXISTS "${FLUIDSYNTH_ROOT}/lib/${ANDROID_ABI}/libfluidsynth.so")
    message(FATAL_ERROR "Run the Gradle prepareSynth task before configuring JNI")
endif()
add_library(fluidsynth SHARED IMPORTED)
set_target_properties(fluidsynth PROPERTIES
    IMPORTED_LOCATION "${FLUIDSYNTH_ROOT}/lib/${ANDROID_ABI}/libfluidsynth.so"
    INTERFACE_INCLUDE_DIRECTORIES "${FLUIDSYNTH_ROOT}/include")
add_library(tuxfluidsynth SHARED fluid_jni.cpp)
target_compile_features(tuxfluidsynth PRIVATE cxx_std_17)
target_compile_options(tuxfluidsynth PRIVATE -Wall -Wextra -Werror)
target_link_options(tuxfluidsynth PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(tuxfluidsynth PRIVATE fluidsynth)
