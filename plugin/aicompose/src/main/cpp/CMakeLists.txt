cmake_minimum_required(VERSION 3.18)

project(aicompose-native)

# ─── llama.cpp path ────────────────────────────────────────────────────────────
set(LLAMA_CPP_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../llama.cpp)
set(LLAMA_BUILD_DIR ${CMAKE_BINARY_DIR}/llama-build)

# ─── llama.cpp build (static, header-only GGML backend) ───────────────────────
set(LLAMA_CMAKE_ARGS
    -DGGML_NATIVE=OFF
    -DGGML_OPENBLAS=OFF
    -DGGML_ACCELERATE=OFF
    -DGGML_NO_VULKAN=OFF
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DCMAKE_BUILD_TYPE=Release
    -DLLAMA_SHARED=OFF
    -DLLAMA_STATIC=ON
    -DLLAMA_NATIVE=OFF
)

add_subdirectory(${LLAMA_CPP_ROOT} ${LLAMA_BUILD_DIR} EXCLUDE_FROM_ALL)

# ─── Android log ───────────────────────────────────────────────────────────────
find_library(log-lib log)
find_library(android-lib android)

# ─── Shared library ───────────────────────────────────────────────────────────
add_library(aicompose SHARED LlamaEngine.cpp)

target_include_directories(aicompose PRIVATE
    ${LLAMA_CPP_ROOT}/ggml/include
    ${LLAMA_CPP_ROOT}/include
)

target_link_libraries(aicompose
    PRIVATE
    llama
    ${log-lib}
    ${android-lib}
)

# ─── Install ─────────────────────────────────────────────────────────────────
install(TARGETS aicompose
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    COMPONENT native
)
