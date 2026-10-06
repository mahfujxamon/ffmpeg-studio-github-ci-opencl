cmake_minimum_required(VERSION 3.22.1)
project(opencl_runtime_probe LANGUAGES C CXX)

add_library(opencl_runtime_probe SHARED opencl_runtime_probe.cpp)

find_library(log-lib log)
find_library(dl-lib dl)

target_compile_features(opencl_runtime_probe PRIVATE cxx_std_17)

target_link_libraries(opencl_runtime_probe PRIVATE ${log-lib} ${dl-lib})
