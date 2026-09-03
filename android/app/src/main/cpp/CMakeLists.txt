cmake_minimum_required(VERSION 3.22.1)
project(nreal_tracking LANGUAGES CXX)
add_library(nreal_tracking SHARED tracking_jni.cpp tracking_filter.cpp vqf/vqf.cpp)
target_compile_features(nreal_tracking PRIVATE cxx_std_17)
target_compile_options(nreal_tracking PRIVATE -Wall -Wextra)
# Android devices with 16 KB pages must be able to load this library.
target_link_options(nreal_tracking PRIVATE -Wl,-z,max-page-size=16384)
