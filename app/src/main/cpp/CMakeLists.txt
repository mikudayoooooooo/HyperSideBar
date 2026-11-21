cmake_minimum_required(VERSION 3.22)
project(hypersidebar_native C)

# spike/os4-native 专用总闸。C 侧默认 0（=hook 不可达，主线包的安全态）；
# **本分支这两行不许合回主线**——漏过去就等于把桌面 inline hook 发给 OS2/3 用户。
# 为什么用普通变量而不是 option()/CACHE：CMake 会把 cache 变量值粘在 `app/.cxx` 里，
# 改了默认值不重配就不生效（1008 实踩过两次：一次把 OFF 误判成"没生效"，一次装机包其实
# 没编进 hook）。普通变量不进缓存 ⇒ 双向都不受旧缓存影响，删掉这行必然回到 C 的默认 0，
# 这才是可信的 tripwire。
set(HSB_NATIVE_SPIKE ON)
if(HSB_NATIVE_SPIKE)
    add_compile_definitions(HSB_NATIVE_SPIKE=1)
endif()

# 源列表：主文件 + native 结构校验「底座」三件（迁移自参考工程
# MiuiBackGestureHook/miui-home-hyos-native/runtime_profile_resolver.cpp，
# Apache-2.0，见各文件头注释）。底座只提供 ELF 视图/导入 GOT/A64 解码，
# 不参与 hook 决策；谁用它由 hypersidebar_native.c 决定。
add_library(hypersidebar_native SHARED
    hypersidebar_native.c
    hsb_config.c
    hsb_elf_view.c
    hsb_import_got.c
    hsb_a64_decode.c
    hsb_fn_enum.c)

# 纯 C、不用 STL；日志走 liblog
target_link_libraries(hypersidebar_native PRIVATE log)

# 导出面只放行 native_init（官方 ABI 要求 + 参考实现 exports-lsposed.map 同款）；
# max-page-size=16384 是硬要求：marble OS3（Android 16）为 16KB 页内核，默认 4096
# 对齐的 .so 在其上 dlopen 必失败——且 LSPosed 对 native entry 失败的处理是整个模块
# 不加载（Java 入口一起死，20260929 实机实证：探针 0x1000 对齐=模块全哑，修对齐前
# 无任何日志）。libdexkit.so 实测即 0x4000。
target_link_options(hypersidebar_native PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports-lsposed.map"
    "-Wl,-z,max-page-size=16384")
target_compile_options(hypersidebar_native PRIVATE
    -fvisibility=hidden -Wl,-z,now)
