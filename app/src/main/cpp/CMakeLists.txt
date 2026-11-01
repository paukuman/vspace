cmake_minimum_required(VERSION 3.22.1)
project(vspace_hooks LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# ---- xHook vendored (MIT, iqiyi/xHook v1.2.0) ----
# Dikopi ke app/src/main/cpp/xhook/ supaya build tidak bergantung jaringan CI.
# xh_jni.c TIDAK dipakai: berisi JNI_OnLoad sendiri yang tidak kita butuhkan.
set(XHOOK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/xhook)
add_library(xhook STATIC
    ${XHOOK_DIR}/xh_core.c
    ${XHOOK_DIR}/xh_elf.c
    ${XHOOK_DIR}/xh_log.c
    ${XHOOK_DIR}/xh_util.c
    ${XHOOK_DIR}/xh_version.c
    ${XHOOK_DIR}/xhook.c
)
target_include_directories(xhook PUBLIC ${XHOOK_DIR})
target_compile_options(xhook PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
find_library(log-lib log)
target_link_libraries(xhook ${log-lib})

# ---- vspace_hooks ----
add_library(vspace_hooks SHARED
    vspace_hooks.cpp
)
target_compile_options(vspace_hooks PRIVATE
    -O2
    -Wall
    -Wextra
    -fexceptions
    -Wno-unused-parameter
)
target_link_libraries(vspace_hooks xhook ${log-lib} dl)