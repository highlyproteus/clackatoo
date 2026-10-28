# SPDX-License-Identifier: GPL-3.0-only
#
# Host build of the native dictionary engine plus a small harness that runs gesture (swipe)
# queries through it. Never shipped; see run_eval.py and the gesture-selftest CI job.
#
#   cmake -S tools/gesture-eval -B build/gesture-eval -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/gesture-eval -j
cmake_minimum_required(VERSION 3.16)
project(gesture_eval CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# Only the jni.h types are needed; no JVM is linked (the engine never calls into Java from here).
# find_package(JNI) without REQUIRED: a headless JDK has no AWT library, which is no problem here.
find_package(JNI)
if(NOT JAVA_INCLUDE_PATH)
  message(FATAL_ERROR "jni.h not found: install a JDK or set JAVA_HOME")
endif()
set(JNI_HEADER_DIRS ${JAVA_INCLUDE_PATH})
if(JAVA_INCLUDE_PATH2)
  list(APPEND JNI_HEADER_DIRS ${JAVA_INCLUDE_PATH2})
endif()

set(JNI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/jni/src)
file(GLOB_RECURSE ENGINE_SOURCES CONFIGURE_DEPENDS ${JNI_SRC}/*.cpp)

add_executable(gesture_eval gesture_eval.cpp ${ENGINE_SOURCES})
target_include_directories(gesture_eval PRIVATE ${JNI_SRC} ${JNI_HEADER_DIRS})
