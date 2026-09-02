# ===== Xposed 模块入口（必保）=====
# META-INF/xposed/java_init.list 点名 com.lsp.hypersidebar.XposedInit：
# 该资源文件不会被 R8 重写，类若被混淆改名 LSPosed 将找不到模块入口。
# 同时保留 public 成员——libxposed 以反射回调生命周期（onPackageLoaded 等）。
-keep public class com.lsp.hypersidebar.XposedInit { public *; }

# Manifest 组件（MainActivity/AllAppsActivity/ShortcutRelayReceiver）由 AGP
# 默认规则自动保留，无需手写。
# 跨进程/字符串寻址的类名核对清单（改动时过一遍）：
#   - ShortcutRelayReceiver：DirectLaunchStrategy.setClassName 字符串直达（AGP 规则已保原名）
#   - 宿主/框架类（com.miui.dock.*、MiuiMultiWindowUtils、ActivityThread）均为
#     字符串反射目标，不在本 APK 内，不受 R8 影响

# 崩溃栈可读性（映射文件在 outputs/mapping/release/）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
