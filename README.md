# HyperSideBar
一个将 MIUI 原生侧边栏替换为双层半圆扇形摇杆菜单的模块。侧滑唤醒，松手即小窗。

> 需要 **Root + LSPosed**，目前仅在 **HyperOS 3.300** 上验证通过，其他机型与系统版本不保证可用。


## 效果展示
竖屏展示：

<img width="320" height="711" alt="竖屏展示" src="https://github.com/user-attachments/assets/0d42d508-845b-41e6-be7a-efee106b09f1" />

横屏展示：

<img width="320" height="144" alt="横屏展示" src="https://github.com/user-attachments/assets/adc93328-17fa-415d-8a87-568ddb255354" />

## 功能介绍

### 呼出与选中、打开方式

- 从屏幕边缘向内滑动并停顿；
- 共用系统原生返回手势但不冲突——不停顿则完全透传给返回手势，零干扰；
- 停留在应用上方即可选中；
- 选中应用并停留片刻后即可以小窗形式打开应用；
- 滑入中心死区或滑出外圈会清空预选；未选中任何应用时松手立即收起。

### 扇形应用

用于固定展示在扇形页面的应用。超过可展示总数时按合并顺序自动截断，固定应用数量不足时由系统推荐自动补充。

### 快捷应用

- 第一位会展示系统自带的活动面板，当前场景没有面板时自动消失、用户项前移；
- 用于固定展示在快捷栏的快捷应用，可启动 activity / service 以及 intent 链接；
- 最多同时展示六个快捷方式（含系统面板占位）。

### 展示全部可打开应用

- 可展示所有可以以小窗形式打开的应用；
- 固定展示在内圈的最后一位。

## 快速开始

- 下载安装 APK（见 [Releases](https://github.com/mikudayoooooooo/HyperSideBar/releases)）；
- 在 LSPosed 中启用模块，勾选作用域 **手机管家（com.miui.securitycenter）** 与 **系统桌面（com.miui.home）**，然后重启这两个应用（或重启手机）即可正常使用；
- 如需调整扇形面板的展示效果，在 `效果预览` 栏点击竖屏 / 横屏的预览图案，可自行调节图标大小、内外圈半径、内外圈应用数量；
- 如需固定展示在扇形界面的应用，可在 `应用` 栏点击 `扇形应用` 自行选择并排序；
- 如需固定展示的快捷方式，可在 `应用` 栏点击 `快捷应用` 自行添加并排序（添加页面可直接测试是否能正常启用）；
- 如果出现扇形页面展示相关问题，可以尝试恢复默认值；
- 如果出现软件/软件崩溃等问题，请立即停止使用。

## 注意事项

### 关于应用本身

- 横屏时触发范围在左上/右上角（与系统原生侧边栏的呼出位置一致）；
- 竖屏时触发范围在中间位置（约整个屏幕 1/3 ~ 2/3 的位置）；
- 拉起 activity / service 以及 intent 的功能可能需要授予 root 权限；
- **游戏 / 视频工具箱开关必须保持开启**，否则横屏状态下无法唤出；
- 本项目只在 `HyperOS 3.300` 上测试过，其他机型或系统不保证可用性（`com.miui.securitycenter` 版本为 12.2.8，`com.miui.home` 版本为 6.01.05）；
- LSPosed 框架必须支持 API 101 及以上；
- 扇形应用展示的应用源于系统的 `getFreeformSuggestionList`，因此并不会展示所有应用；
- 连续失败会触发熔断，hook 将不再主动生效（可在设置页查看通道状态并手动重试，或重启手机）；
- 本项目非官方模块、与小米无关，使用风险自负。

### 其他注意事项

本项目含有：

- 作者的奇思妙想和朝令夕改的各种绝妙构思和决策；
- 包括但不限于 GLM5.3、GLM5.3-flash、GLM5.2、DeepSeek-V4-flash、DeepSeek-V4-pro、MIMO V2.5 pro、Kimi K2.7 Code、Qwen3.8-Max、Qwen3.7-Plus 等各类或强或弱的模型精心熬制的一锅屎山代码，几乎不含多余的人工代码；
- 可能存在的各类 bug 或者不完善的功能、漏洞。

## 基于

- [LSPosed](https://github.com/LSPosed/LSPosed)（libxposed API）
- [miuix](https://github.com/compose-miuix-ui/miuix)（HyperOS 风格 Compose UI 库）
- [EzXHelper](https://github.com/KyuubiRan/EzXHelper)
  
## 开源协议

[Apache License 2.0](LICENSE)



