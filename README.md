# HyperSideBar
一个将 HyperOS系统原生侧边栏替换为双层半圆扇形摇杆菜单的模块，提供多种呼出方式，支持展示全部可打开应用、设置快捷方式。

> 目前仅在 **HyperOS 3.300** 上验证通过，其他机型与系统版本不保证可用。


## 效果展示
模块设置展示：

<img width="320" height="711" alt="模块功能展示" src="https://github.com/user-attachments/assets/0dd46308-1e08-4e7e-a5ef-2d4cac0244fa" />

竖屏侧滑展示：

<img width="320" height="711" alt="竖屏侧滑展示" src="https://github.com/user-attachments/assets/1a637633-4028-4952-95d5-b1fe9621daa5" />

竖屏底角斜滑展示：

<img width="320" height="711" alt="底角斜滑展示" src="https://github.com/user-attachments/assets/2dbfb2ed-c684-43d5-a1d2-ff0117275698" />

横屏展示：

<img width="320" height="144" alt="横屏展示" src="https://github.com/user-attachments/assets/6283f55b-0101-41cb-8ff4-639f16f4ed2d" />

## 功能介绍

### 呼出与选中、打开方式

#### 呼出方式
- 竖屏下：从屏幕中间位置的边缘向内滑动并停顿；
- 竖屏下：从屏幕底角位置向屏幕中间滑动；
- 横屏下：从屏幕左上角边缘向内滑动并停顿。
注意：
- 停顿时长与触发滑动距离均可在设置中调整；
- 底角斜滑的呼出方式默认关闭，且只能在小白条正常显示时触发；
- 横屏的位置与HyperOS系统触发侧边栏的位置保持一致；
- 共用系统原生返回手势但不冲突——不停顿则完全透传给返回手势，零干扰。

#### 打开方式
- 停留在应用上方即可选中；
- 选中应用并停留片刻后即可以小窗形式打开应用；
- 滑入中心死区或滑出外圈会清空预选；未选中任何应用时松手立即收起。

### 扇形应用

用于固定展示在扇形页面的应用。超过可展示总数时按合并顺序自动截断，固定应用数量不足时由系统推荐自动补充。

### 快捷应用

- 第一位会展示系统自带的活动面板，当前场景没有面板时自动消失、用户项前移；
- 用于固定展示在快捷栏的快捷应用，可启动 activity / service、qs 磁贴、shortcut 以及 intent 链接；
- 最多同时展示六个快捷方式（含系统面板占位）。

### 展示全部可打开应用

- 可展示所有可以以小窗形式打开的应用；
- 固定展示在内圈的最后一位。

### 统计与诊断

- 设置 → 关于 → 「诊断与统计」：使用统计（呼出 / 启动 / 成功率 / 误触率 / 平均响应，可导出 CSV）、应用内日志与自检报告；
- 调试区提供「重启 hook 宿主」。

## 快速开始

- 下载安装 APK（见 [Releases](https://github.com/mikudayoooooooo/HyperSideBar/releases)）；
- 在 LSPosed 中启用模块，勾选作用域 **手机管家（com.miui.securitycenter）** 、**系统界面（com.android.systemui）**与 **系统桌面（com.miui.home）**，然后重启这三个应用（或重启手机）即可正常使用；
- 如需调整扇形面板的展示效果，在 `布局与交互` 栏点击竖屏 / 横屏 / 底角斜滑的预览图案，可自行调节图标大小、内外圈半径、内外圈应用数量；
- 如需固定展示在扇形界面的应用，可在 `应用` 栏点击 `扇形应用` 自行选择并排序；
- 如需固定展示的快捷方式，可在 `应用` 栏点击 `快捷应用` 自行添加并排序（添加页面可直接测试是否能正常启用）；
- 如需调整扇形背景的观感，在 `交互` 栏调节板材质浓度、背景压暗开关与背景模糊来源（采样壁纸 / 后方屏幕全屏 / 透明 / 关闭，默认关闭）；
- 从 v1.x 升级：包名已迁移为 `io.github.mikudayoooooooo.hypersidebar`，需卸载重装并在 LSPosed 重新勾选作用域，如有 root 授权需重新授予；
- 如果出现扇形页面展示相关问题，可以尝试恢复默认值；
- 如果出现软件/软件崩溃等问题，请立即停止使用；
- 调试开关仅在 debug 构建中存在，release 版不含此功能。
  
## 注意事项

### 关于应用本身

- 横屏时触发范围在左上/右上角（与系统原生侧边栏的呼出位置一致）；
- 竖屏时触发范围在中间位置（约整个屏幕 1/3 ~ 2/3 的位置）；
- 启用快捷方式可能需要root；
- **游戏 / 视频工具箱开关必须保持开启**，否则横屏状态下无法唤出；
- 更新模块版本后，请再次重启上述三个应用（桌面 / 系统界面 / 手机管家），否则部分功能可能不生效；
- 本项目只在 `HyperOS 3.300` 上测试过，其他机型或系统不保证可用性（`com.miui.securitycenter` 版本为 12.2.8，`com.miui.home` 版本为 6.01.05，`com.android.systemui` 版本为16.03.251211）；
- 未来可能会云适配HyperOS2以及HyperOS4；
- LSPosed 框架必须支持 API 101 及以上；
- 扇形应用展示的应用源于系统的 `getFreeformSuggestionList`，因此并不会展示所有应用；
- 连续失败会触发熔断，hook 将不再主动生效（可在设置页查看通道状态并手动重试，或重启手机）；
- 本项目非官方模块、与小米无关，使用风险自负。

### 其他注意事项

本项目：

- 包含作者的奇思妙想和朝令夕改的各种绝妙构思和决策；
- 包括但不限于 GLM5.3、GLM5.3-flash、GLM5.2、DeepSeek-V4-flash、DeepSeek-V4-pro、MIMO V2.5 pro、Kimi K2.7 Code、Qwen3.8-Max、Qwen3.7-Plus 等各类或强或弱的模型精心熬制的一锅屎山代码，；
- 可能存在的各类 bug 或者不完善的功能、漏洞；
- 代码含人量较低。
  
## 基于

- [LSPosed](https://github.com/LSPosed/LSPosed)（libxposed API）
- [miuix](https://github.com/compose-miuix-ui/miuix)（HyperOS 风格 Compose UI 库）
- [EzXHelper](https://github.com/KyuubiRan/EzXHelper)
  
## 开源协议

[Apache License 2.0](LICENSE)



