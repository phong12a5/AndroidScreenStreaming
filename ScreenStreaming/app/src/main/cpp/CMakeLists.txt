# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("screenstreaming")

set(OPENSSL_ROOT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/openssl/${CMAKE_ANDROID_ARCH_ABI})
message("OPENSSL_ROOT_DIR: ${OPENSSL_ROOT_DIR}")
list(APPEND CMAKE_MODULE_PATH ${CMAKE_CURRENT_SOURCE_DIR})
find_package(OpenSSL REQUIRED)


# Add libdatachannel subdirectory
add_subdirectory(libdatachannel)

add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        native-lib.cpp
        WebRTCStreamer.cpp) # Add WebRTCStreamer.cpp

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
        OpenSSL::Crypto
        OpenSSL::SSL
        datachannel-static)