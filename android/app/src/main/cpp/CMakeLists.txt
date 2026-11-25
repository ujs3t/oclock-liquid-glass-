cmake_minimum_required(VERSION 3.22.1)
project(oclock_glass LANGUAGES CXX)
add_library(oclock_glass SHARED glass_jni.cpp)
target_compile_features(oclock_glass PRIVATE cxx_std_17)
# Keep IEEE semantics: no fast-math, so edge lighting stays faithful.
target_compile_options(oclock_glass PRIVATE -O3 -ffp-contract=off)
