cmake_minimum_required(VERSION 3.22.1)
project(heimdall_native C CXX)

# ---------------------------------------------------------------------------------------------
# Heimdall native layer: a thin, thread-safe JNI wrapper over llama.cpp + mtmd (the multimodal
# front-end that supersedes the old clip/llava helpers).
#
# llama.cpp is NOT vendored. scripts/fetch-llama.sh checks out the commit in native/llama.cpp.pin
# into native/third_party/llama.cpp (gitignored), and -DLLAMA_DIR may point elsewhere. CI runs the
# same script, so the pin is the single source of truth.
# ---------------------------------------------------------------------------------------------

set(LLAMA_DIR "" CACHE PATH "Path to a llama.cpp checkout")
option(HEIMDALL_BUILD_VULKAN "Compile the ggml Vulkan backend" OFF)

if(NOT LLAMA_DIR)
    message(FATAL_ERROR "LLAMA_DIR is required. Run scripts/fetch-llama.sh and pass -DLLAMA_DIR=<path>")
endif()
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "No llama.cpp checkout at LLAMA_DIR='${LLAMA_DIR}'. Run scripts/fetch-llama.sh first.")
endif()

# The toolchain ships glslc, so Vulkan can be requested without an external Vulkan SDK.
if(HEIMDALL_BUILD_VULKAN)
    message(STATUS "Heimdall: Vulkan backend ENABLED")
else()
    message(STATUS "Heimdall: Vulkan backend DISABLED (CPU backend only)")
endif()

# ---- llama.cpp build configuration -----------------------------------------------------------
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
# The tools/ tree is only added when LLAMA_BUILD_COMMON is ON, so mtmd is built through the
# dedicated standalone hook instead (builds tools/mtmd only, not the CLI binaries).
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)      # never tune for the build host when cross-compiling
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # Android has no libgomp
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ${HEIMDALL_BUILD_VULKAN} CACHE BOOL "" FORCE)
# Android delivers the Vulkan loader from the platform; link the stub, not a bundled loader.
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "" CACHE STRING "" FORCE)

add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

# ---- Heimdall JNI library --------------------------------------------------------------------
add_library(heimdall SHARED
    heimdall_jni.cpp
)

target_include_directories(heimdall PRIVATE
    "${LLAMA_DIR}/include"
    "${LLAMA_DIR}/ggml/include"
    "${LLAMA_DIR}/tools/mtmd"
)

target_compile_features(heimdall PRIVATE cxx_std_17)
target_compile_options(heimdall PRIVATE
    -Wall -Wextra -Wno-unused-parameter -Wno-deprecated-declarations
)

target_link_libraries(heimdall PRIVATE llama mtmd ggml android log)

# -fno-exceptions is set by the AGP cppFlags for the module; llama.cpp itself needs exceptions, so
# the flag is stripped here for the libraries that require them.
if(CMAKE_CXX_FLAGS MATCHES "-fno-exceptions")
    target_compile_options(heimdall PRIVATE -fexceptions)
endif()
