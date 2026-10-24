# PLAUD Laurent v1.5 — bibliothèque native libplaudwhisper.so : whisper.cpp + pont JNI.
# Copié en app/src/main/cpp/ par .github/workflows/apk.yml ; les sources de whisper.cpp
# sont téléchargées par le workflow (version figée) et indiquées par WHISPER_DIR.
cmake_minimum_required(VERSION 3.22.1)
project(plaudwhisper C CXX)

if(NOT WHISPER_DIR)
    message(FATAL_ERROR "WHISPER_DIR : dossier des sources whisper.cpp non indiqué")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Instructions des téléphones récents (dont le Poco F8 Ultra) : calcul nettement plus rapide.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
if(APPLE)
    # Essai sur Mac uniquement : processeur seul, comme sur le téléphone.
    set(GGML_METAL OFF CACHE BOOL "" FORCE)
    set(GGML_BLAS OFF CACHE BOOL "" FORCE)
    set(WHISPER_COREML OFF CACHE BOOL "" FORCE)
endif()
add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

add_library(plaudwhisper SHARED whisper_jni.c)
target_link_libraries(plaudwhisper PRIVATE whisper)
if(ANDROID)
    target_link_libraries(plaudwhisper PRIVATE log)
    # Téléphones Android 15+ à pages mémoire de 16 Ko.
    target_link_options(plaudwhisper PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    target_include_directories(plaudwhisper PRIVATE ${JNI_INCLUDE_DIRS})
endif()
