cmake_minimum_required(VERSION 3.22)
project(seidr_milkdrop C CXX)

# projectM is vendored unmodified (projectm/, the libprojectM release tarball)
# and linked as its own shared library, as LGPL-2.1 intends.
#
# Debug builds still optimise it: at -O0 it can't hold frame rate on a TV,
# which would make every debug test misleading.
foreach(lang C CXX)
    set(CMAKE_${lang}_FLAGS_DEBUG "-O2")
endforeach()

set(ENABLE_PLAYLIST OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_GLM OFF CACHE BOOL "" FORCE)
set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory(projectm)

add_library(milkdrop SHARED milkdrop_jni.cpp)
target_link_libraries(milkdrop PRIVATE libprojectM::projectM log)

# The headset visualiser (Quest): OpenXR through the Khronos loader (its AAR,
# via prefab). A library of its own, loaded only on a headset.
find_package(OpenXR REQUIRED CONFIG)
add_library(seidrxr SHARED xr_jni.cpp)
target_link_libraries(seidrxr PRIVATE OpenXR::openxr_loader EGL GLESv3 log)
