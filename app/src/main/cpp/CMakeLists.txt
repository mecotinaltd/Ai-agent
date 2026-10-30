cmake_minimum_required(VERSION 3.22.1)
project(collab_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is downloaded while the app is built (GitHub Actions has internet).
# The JNI code in llama_jni.cpp is written for this release. Change it only together with that file.
set(LLAMA_CPP_TAG "b5602" CACHE STRING "llama.cpp release tag")

# On-device speed needs an optimised build even when Gradle builds the debug APK.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
# CPU features the phone must have. armv8.2-a+dotprod covers almost every phone since 2018.
# Build with -PllamaArch=armv8-a for very old phones (slower).
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" )

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LLAMA_CPP_TAG}
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama)

add_library(collab_llama SHARED llama_jni.cpp)
target_include_directories(collab_llama PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include)
target_link_libraries(collab_llama PRIVATE llama ggml android log)
