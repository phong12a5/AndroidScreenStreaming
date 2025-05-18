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

# set(OPENSSL_ROOT_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../prefix/${CMAKE_ANDROID_ARCH_ABI})
set(PRJ_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
set(OPENSSL_ROOT_DIR ${PRJ_ROOT}/android-openssl-cmake/prefix/${CMAKE_ANDROID_ARCH_ABI})

message("OPENSSL_ROOT_DIR: ${OPENSSL_ROOT_DIR}")
#
#set(OPENSSL_CRYPTO_LIBRARY ${OPENSSL_ROOT_DIR}/lib/libcrypto.so)
#message("OPENSSL_CRYPTO_LIBRARY: ${OPENSSL_CRYPTO_LIBRARY}")
#
#set(OPENSSL_INCLUDE_DIR ${OPENSSL_ROOT_DIR}/include)
#message("OPENSSL_INCLUDE_DIR: ${OPENSSL_INCLUDE_DIR}")

find_package(OpenSSL REQUIRED)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -Wall")

set(PRJ_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/..")

if (ANDROID)
    set(OPENSSL_ROOT_DIR ${PRJ_ROOT}/android-openssl-cmake/prefix/${CMAKE_ANDROID_ARCH_ABI})
    message("OPENSSL_ROOT_DIR: ${OPENSSL_ROOT_DIR}")
endif ()
#list(APPEND CMAKE_MODULE_PATH ${PRJ_ROOT}/src)
find_package(OpenSSL REQUIRED)


# Add libdatachannel subdirectory
add_subdirectory(libdatachannel)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        native-lib.cpp
        WebRTCStreamer.cpp) # Add WebRTCStreamer.cpp

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        android
        log
        OpenSSL::Crypto
        OpenSSL::SSL
        datachannel-static) # Add datachannel library