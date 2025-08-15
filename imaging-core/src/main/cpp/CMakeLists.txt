cmake_minimum_required(VERSION 3.22.1)
project(imagingcore)

add_library(imagingcore SHARED
    imaging_jni.cpp
)

target_compile_features(imagingcore PRIVATE cxx_std_17)

find_library(log-lib log)

target_link_libraries(imagingcore ${log-lib})
