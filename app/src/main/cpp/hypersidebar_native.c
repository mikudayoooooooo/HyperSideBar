/*
 * LSPosed native entry 探针（OS4 适配前置验证，adapt/hyperos4）。
 *
 * 目的（在 OS3/OS2 上就能验，见 docs/adaptation/marble-os4-decompile-verification.md §6 与
 * F:\hyperos\work\docs\lsposed-native-entry-verification.md §4.2）：
 *   U1：本机的 LSPosed 构建是否真的解析 assets/native_init 并 dlopen 本 so
 *       调用 native_init —— 这决定 OS4 桌面 native hook 路线是否成立；
 *   优雅跳过：OS2/OS3 的 launcher 没有 libapp_launcher.so，本探针必须安静记录后不做任何事。
 *
 * ABI 按官方 wiki《Native Hook》：entries 内容不可改写；返回的回调在之后每次 dlopen 时
 * 被框架以 (name, handle) 调用。hookFunc/unhookFunc 任一为空即视为 API 不完整。
 *
 * 取证双通道（2026-10-02）：logcat tag=HSBNative（logcat 可用时用）+
 * 全局报告 g_report（Java 经 NativeProbe.report() 取，写进 HLog → 自检导出——
 * logcat 整体失灵时的兜底，OS4.0.0.14 实机首发日即命中）。报告在 native_init
 * 回调内填充完毕（该回调在 dlopen 内、JNI_OnLoad 之前），Java 侧 loadLibrary
 * 返回后即可读到完整快照。
 */
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>   /* dl_iterate_phdr / struct dl_phdr_info */
#include <elf.h>    /* ElfW(Phdr) / PT_LOAD / PF_X */
#include <stdio.h>
#include <stdlib.h>   /* strtoul —— loglimit 属性解析 */
#include <stdint.h>
#include <string.h>
#include <errno.h>   /* chanprobe：文件写失败要看 errno */
#include <jni.h>
#include <time.h>   /* clock_gettime(CLOCK_MONOTONIC) —— 心跳的"安装后秒数"基准 */
#include <pthread.h> /* 心跳线程（补丁完整性巡检，见 §5.2 G1） */
#include <sys/system_properties.h>
#include "launcher_profiles.h"
/* 结构性身份校验底座（2026-10-16，来自参考工程 MiuiBackGestureHook，Apache-2.0；
 * 见 hsb_elf_view.h 的头注释）。只被 hsb_locate 的锚点结构校验用。 */
#include "hsb_elf_view.h"
#include "hsb_import_got.h"
#include "hsb_a64_decode.h"
#include "hsb_config.h"   /* 配置读取：文件 /data/local/tmp/hsb.conf 优先 → 系统属性 → 缺省 */

/*
 * 编译期总闸（2026-10-08，替代做不了的"OS3 装机回归"，见测试清单 §7）：
 *   默认 0 ⇒ `hsb_prop_enabled()` 返回常量 0，扫描/装 hook/回调补拍/Java retry 四条路全部
 *   不可达，release 主线包里这段代码不是"运行时不执行"而是"二进制里没有"（实测产物中
 *   `debug.hsb.native.hook` 与锚点名字符串都不存在）；
 *   spike 分支由 CMakeLists 里 `set(HSB_NATIVE_SPIKE ON)` + `add_compile_definitions` 打开。
 * 合回主线时**不用改这个 C 文件**（默认就是关），只要别把 CMakeLists 那两行带过去。
 */
#ifndef HSB_NATIVE_SPIKE
#define HSB_NATIVE_SPIKE 0
#endif

#define LOG_TAG "HSBNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

typedef int  (*HookFunType)(void *func, void *replace, void **backup);
typedef int  (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);
typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

/* 感兴趣的宿主库：OS4 桌面的两个 Rust/Flutter so。OS2/OS3 上理应都 absent。
 * kTargetLib = native hook 的目标镜像（OS4 桌面 Rust 侧）。 */
#define kTargetLib "libapp_launcher.so"
static const char *const kReplayLibs[] = {
    kTargetLib,
    "libapp.so",
    NULL,
};

#define HSB_LOG_LIMIT 12u
/*
 * HIT 行的打印上限，运行期可调（2026-10-10 加）：`debug.hsb.native.loglimit <N>`。
 * 调大的用途：密集采样时不需要改代码重编——例如角区链路归因时要看每一次
 * `is_in_bottom_gesture_region` 的调用，12 条只够抽样。
 * 按 1 秒粒度缓存（这个判断在每个输入事件上都会跑）。
 * 上限 5 万：防手滑设个大值把 logcat 冲爆。 */
#define HSB_LOGLIMIT_PROP "debug.hsb.native.loglimit"
#define HSB_LOGLIMIT_MAX 50000u

/*
 * 总闸（发布档）属性名：模块 App 用同一条 su/root 属性通道推（与 `persist.hsb.band.*` /
 * `persist.hsb.strip.*` 同款）。launcher 进程没有模块 Java 半侧、读不到模块开关，
 * 所以"模块启用 + 本机是 OS4 native 桌面"这个结论必须由模块 App 写进属性。
 * `debug.hsb.native.hook` 仍可覆盖它（排障/压测用，重启即清）。
 * 真机上 property 的两条免-su 写路都被 SELinux 拒（见 plan §17），所以另有一条**免 root
 * 文件 marker** 关断通道与它**并联**（任一显式 "0" 即关）。
 */
#define HSB_ENABLE_PROP "persist.hsb.native.hook"

static uint64_t hsb_now_s(void);   /* 定义在下方（心率/缓存都用它），此处前向声明 */

static uint32_t g_log_limit = HSB_LOG_LIMIT;
static uint64_t g_limit_checked_s;

static uint32_t hsb_log_limit(void) {
    uint64_t now = hsb_now_s();
    if (g_limit_checked_s != now) {
        char v[16] = {0};
        g_limit_checked_s = now;
        if (__system_property_get(HSB_LOGLIMIT_PROP, v) > 0 && v[0]) {
            unsigned long n = strtoul(v, NULL, 10);
            if (n == 0ul) n = HSB_LOG_LIMIT;
            if (n > HSB_LOGLIMIT_MAX) n = HSB_LOGLIMIT_MAX;
            g_log_limit = (uint32_t) n;
        } else {
            g_log_limit = HSB_LOG_LIMIT;
        }
    }
    return g_log_limit;
}

/* 报告结构：写入集中在加载路径（单线程），读在 JNI，无竞争 */
static struct {
    uint32_t ctor_ran;        /* constructor 探针已执行（=框架真 dlopen 了我们） */
    uint32_t init_fired;      /* native_init 被框架调用（=U1 成立） */
    uint32_t api_version;
    uint32_t has_hook_func;
    uint32_t has_unhook_func;
    uint32_t api_incomplete;  /* 因 API 不完整返回 NULL，未回 dlopen 回调 */
    uint32_t callback_calls;  /* 框架调 on_module_loaded 的次数（回调链活性） */
    uint32_t replay_mask;     /* 回放命中的库位图（仅取证，不再当门控用，见 §门控复盘） */
    uint32_t launcher_cb_hits;/* dlopen 回调里名字命中目标库的次数 */
    char     process[128];
} g_report;

/* LSPosed 交付的 native hook API（探针阶段只暂存，装 hook 时才用） */
static const NativeAPIEntries *g_api;

static void note_process(void) {
    FILE *f = fopen("/proc/self/cmdline", "r");
    char buf[160] = {0};
    if (f) {
        size_t n = fread(buf, 1, sizeof(buf) - 1, f);
        (void)n;
        fclose(f);
    }
    memcpy(g_report.process, buf, sizeof(g_report.process) - 1);
    g_report.process[sizeof(g_report.process) - 1] = '\0';
}

static void hsb_try_install_hooks(void);
static int hsb_prop_enabled(void);
/* 段查询与坐标小工具（定义在扫描段，但附锚点的日志要用它算 `lib+0x…`） */
static int hsb_in_exec(uintptr_t addr, size_t len);

static int hsb_leaf_is_target(const char *name) {
    if (!name) return 0;
    const char *leaf = strrchr(name, '/');
    leaf = leaf ? leaf + 1 : name;
    /* 精确末段匹配：APK 内嵌时 dlpi/maps 名字形如 "...base.apk!/lib/arm64-v8a/libapp_launcher.so"，
     * 末段仍是裸 soname；用 strcmp 而非前缀，免得 "libapp_launcher_x.so" 误判成目标 */
    return strcmp(leaf, kTargetLib) == 0;
}

static void on_module_loaded(const char *name, void *handle) {
    g_report.callback_calls++;
    (void)handle;
    /* 总闸/prop 关=与冻结线完全一致：只计数，不打日志、不比对名字、不装 hook */
    if (!hsb_prop_enabled()) return;
    /* ⚠ 不逐库打日志：注入了模块的所有进程每次 dlopen 都会回调到这里（真机一轮几百行
     * `dlopen cb:` 把 logcat 冲爆）。框架有没有把桌面原生库的加载报给我们，看
     * `launcher_cb=` 计数与 `hook installed …` 行即可。 */
    if (hsb_leaf_is_target(name)) {
        g_report.launcher_cb_hits++;
        hsb_try_install_hooks();
    }
}

/*
 * 构造期探针（2026-09-30 复盘新增，os4-native-entry-postmortem §2.1）：
 * 没有它就无法区分"框架 dlopen 了我们但没调 native_init"与"根本没加载 so"——
 * 前者问题在清单/ABI 约定，后者是 native entry 未被处理。ctor 在 dlopen 收尾执行、
 * 早于框架的 native_init 回调，用 constructor 而非 JNI_OnLoad（框架走 dlopen+dlsym，不经 JVM）。
 */
__attribute__((constructor))
static void probe_ctor(void) {
    g_report.ctor_ran = 1;
    note_process();
    LOGI("probe so LOADED (ctor) — framework did dlopen us, native_init should follow");
}

__attribute__((destructor))
static void probe_dtor(void) {
    LOGI("probe so UNLOADING (dtor)");
}

/*
 * ===== native hook 骨架（spike/os4-native 第 1 步：能力验证，默认惰性）=====
 *
 * 开法：`adb shell su -c 'setprop debug.hsb.native.hook 1'` 后重启桌面。关=完全不做任何事
 * （连 maps/phdr 都不扫），所以冻结线/发版包带这段代码也无风险。
 *
 * 本步当时只证明四件事：地址解析对、inline hook 装得上且不掉、日志能出、桌面不崩；
 * 那时刻意**不做 madvise 守卫**，因为那正是本步要验的风险（参考工程认为缺它会被 HYOS 的
 * madvise 把 hook 页打回文件映射而"神秘失效"）。**2026-10-14 更新**：主锚点已固定为
 * `handle_back_gesture`（见下方"主锚点编译期默认值"一段），探路期只挂
 * try_delegate_back_to_sidebar 的做法已废弃；madvise 守卫仍未加，但真机连续跑满整轮手势
 * （每次停顿都命中 HIT/SUPPRESS/FIX）未见掉钩，故暂不补。
 *
 * 门控复盘（2026-10-08 实机首轮）：原来把安装条件写成 `replay_mask & 1`，日志给出
 * `replay: libapp_launcher.so absent` + `runs=0` ⇒ 安装代码从未跑过。裸 soname 的
 * `dlopen(RTLD_NOLOAD)` 在**linker namespace 隔离**（模块 so 与桌面原生库不在同一
 * namespace）和**加载时机**（我们的 so 在桌面 System.loadLibrary 之前就被 dlopen）下
 * 都会落空，参考工程自己也从不信它（用 lsplt 读 maps）。故 replay 降级为纯取证，
 * 地址改由两条独立来源交叉给出（phdr + maps），装不装由 prologue 校验决定。
 *
 * fail-closed 三道（升级为）：候选地址的目标落在该库 maps 记录的可执行段内（防 APK 内嵌
 * 时 start-pgoff 推出的偏移错基导致越界读）/ 目标处 32B prologue 与 profile 逐字节相等 /
 * hook_func 返回 0 且 backup 非空。任一不满足 ⇒ 换下一个候选或拒绝安装并记原因（桌面 OTA
 * remap 后表现为"不 hook"而不是崩桌面）。
 */
/*
 * 主锚点**编译期默认值**（2026-10-14 晚改，真机 logcat 实证的必要修正）：
 * 只能是侧滑停顿线的生产挂点 handle_back_gesture（side-only 物理流边界），不能是
 * 第 1 步的探路点 try_delegate_back_to_sidebar —— 后者只在"back 走到委托分叉"时才被调用，
 * 停顿手势根本走不到那里 ⇒ 主桩（计数 + 停顿抑制 + arrowfix）一次都不会被调用。
 * 为什么必须是**编译期默认**而不是属性：`debug.hsb.native.anchor` 是易失属性，
 * 重启即清（真机日志 `debug=-`），而写侧收敛后模块已不再推任何属性 ⇒ 若默认仍是探路点，
 * 每次重启功能都静默失效（用户要看的是"装上就能用"）。属性仍可覆盖（调试用）。
 *
 * 历史：本常量原名 HSB_ONLY_ANCHOR = 白名单里"唯一敢装"的那个点；现已扩容为白名单 + 默认点。
 */
#define HSB_ONLY_ANCHOR "handle_back_gesture"
#define HSB_MAX_SEGS 16u
#define HSB_MAX_CANDS 8u
#define HSB_MAX_ATTEMPTS 12u
#define HSB_HEARTBEAT_S 60ull
#define HSB_PROLOGUE_BYTES HSB_PROFILE_PROLOGUE_BYTES
#define HSB_ANCHOR_PROP "debug.hsb.native.anchor"
/*
 * 第二锚点（2026-10-11 角区双锚点方案）：动作锚点 pilfer_pointers（0x78d2d4，140B，
 * 唯一有效调用是 AOSP `InputMonitor.pilferPointers()`）**只吃 self（InputMonitor）、
 * 没有坐标**；坐标必须由主锚点 init_gesture_type（DOWN 拍，(x,y)∈s0/s1）锁存 ⇒ 同时装两个。
 * `debug.hsb.native.anchor2 pilfer_pointers` 装上动作桩；缺省空 = 不装第二锚点（回现状）。
 * 名单只认 pilfer_pointers（见 hsb_chosen_anchor2）——它没有返回值语义，桩只做拦截。
 */
#define HSB_ANCHOR2_PROP "debug.hsb.native.anchor2"

/* 第三锚点（2026-10-14 加，2026-10-15 扩到三个）：**只读观测**探针位。
 *   - `nav_hot_zone`  = GestureInputHomeHelper::is_in_navigation_bar_hot_zone(x,y)
 *   - `judge_probe`   = GestureInputHomeHelper::trigger_gesture_judge（Home 判据本体，
 *     w2 接过热区 verdict；**2026-10-15 实测 0 调用，已判不在我们的观测路径上**）
 *   - `pilfer_probe`  = GestureInputHomeHelper::do_pilfer（pilfer 动作本体：自旋锁后
 *     `bl input_InputMonitor_pilferPointers@plt`；调用点仅 78e90c/78ef30 两处，均在
 *     GestureInputMonitorImpl 内，入参 x0 = self+0xc8）
 * `debug.hsb.native.anchor3 <名字>` 装上只读桩；缺省空 = 不装第三锚点（回现状）。
 * 桩只透传 + 打参数，绝不改返回值：改返回值就等于改掉系统的 Home/pilfer 判据。
 * 已证数据（2026-10-15，36 个真人采样点）：系统热区只在横向中段 x∈[400,760] y≈2380，
 * 与我们角窗（x≤130 或 x≥950、y≥2328）**0 重叠** ⇒ 角区内压制热区是空操作。 */
#define HSB_ANCHOR3_PROP "debug.hsb.native.anchor3"
/* 底角"斜滑独占"开关（抑制）：由模块 App 设置项推（`persist.` ⇒ 重启后仍在；读法 1 秒缓存）。
 * 定义在这里是因为 [hsb_chosen_anchor3] 的缺省值要用它（抑制开 ⇒ 自动挂 action_probe）。 */
#define HSB_CORNER_SUPP_PROP "persist.hsb.corner.supp"

/*
 * 可换的挂点白名单（2026-10-08 加，2026-10-09 第 2 步扩容）：装机轮稀缺，
 * "installed=1 但 hits 恒 0"这种**选点**问题不该再烧一次 APK，
 * `setprop debug.hsb.native.anchor <名字>` + 重启桌面就能换。
 *
 * 2026-10-09 更正（离线反汇编实锤，旧注释是误读）：原来以"序言存 d8-d15 ⇒ 可能有浮点参数"
 * 把 on_swipe_start/on_swipe_process/handle_back_gesture 全排除。实际逐个看过序言：
 *  - on_swipe_start：只存 **d8 一个**（自己用的 callee-saved），**不吃 FP 参数** ⇒ 可挂；
 *  - on_swipe_process / handle_back_gesture：同样只存 x/d8 级，且 args 是 x0-x2 ⇒ 可挂；
 *  - init_gesture_type / match_region_check / is_in_bottom_gesture_region：
 *    **真的以 s0/s1 传坐标**（init_gesture_type 里 `fmov s8,s0`+`stp s0,s1,[x29,#-8]`）
 *    ⇒ 必须靠桩的 d0-d7 透传，见 HsbFn16。
 */
static const char *const kHsbAnchorWhitelist[] = {
    /* --- 第 1 步已验证（返回委托分叉）--- */
    "try_delegate_back_to_sidebar",   /* 188B，back 委托侧边栏的分叉 */
    "on_back_cancelled",              /* 456B，判定失败/取消 */
    "on_back_invoke",                 /* 1476B，commit 时刻 */
    /* --- 第 2 步：门控与底角的观测点 --- */
    "on_swipe_start",                 /* 1504B，系统认领边缘滑=门控主信号，无 FP 参数 */
    "init_gesture_type",              /* 1012B，DOWN 时 (x,y)∈s0/s1 + 定手势类型 */
    "is_in_bottom_gesture_region",    /* 168B，底部手势区判定（底角落点） */
    "match_region_check",             /* 344B，纯分类：4 矩形 → 类型码 0/1/2 */
    /* --- 第 3 步 3.1：动作函数签名验证（log-only，不调用）--- */
    "cancel_home_gesture_to_engine",  /* 532B，反汇编定签名 (x0=self+0x10, x1=rodata 串, x2, x3) */
    "pilfer_pointers",                /* 140B，1 参 (x0=self)，null 即返回 */
    /* --- 2026-10-12：侧滑停顿线（GestureInputBackHelper 家族）---
     * 之前只在 launcher_profiles.h（profile）里、白名单没有 ⇒ 选它会被静默忽略、
     * 回落默认锚点。侧滑线 S1/S2 需要这两个点：handle_back_gesture 是参考实现
     * （MiuiBackGestureHook/4371）实证的 **side-only 边界**（底部 Home 不经过它）；
     * on_swipe_process 用于后链路抑制。两者 prologue 均在 profile，fail-closed 兜底。 */
    "handle_back_gesture",            /* 8364B，side-only 完整物理流边界（参考实证） */
    "on_swipe_process",               /* 3380B，手势进度（后链路抑制用） */
    /* --- 2026-10-12：手势**终态路径入口**（"取消"的第二半）---
     * pilfer 的 CANCEL 到达 launcher 后真正干活的就是它：本函数 → GesturesBackCallback::
     * on_swipe_stop_direct → GestureBackArrowView::on_swipe_stop → cancel_arrow_animator（箭头退出），
     * 再接 try_delegate_back_to_sidebar（取消/提交分叉）。调 on_back_cancelled 只做了第一半。 */
    "on_swipe_stop_direct",           /* 276B，终态路径入口（观测/将来的"取消"落点） */
    NULL,
};

static const char *hsb_chosen_anchor(void) {
    char v[64] = {0};
    if (__system_property_get(HSB_ANCHOR_PROP, v) <= 0 || !v[0]) return HSB_ONLY_ANCHOR;
    for (int i = 0; kHsbAnchorWhitelist[i]; i++) {
        if (strcmp(v, kHsbAnchorWhitelist[i]) == 0) return kHsbAnchorWhitelist[i];
    }
    return HSB_ONLY_ANCHOR;   /* 名单外的值=忽略，仍用默认点（不猜） */
}

/* 第二锚点 = **只支持** `pilfer_pointers`（底角斜滑的角区动作位；2026-10-13 清理后
 * on_back_cancelled / try_delegate_back_to_sidebar / on_swipe_stop_direct 三个观测/门控位
 * 已全部删除——真机已判死，留着只会让人误以为还有开关）。
 * 空/其它值 = 不装第二锚点（不猜）。 */
static const char *hsb_chosen_anchor2(void) {
    char v[64] = {0};
    if (__system_property_get(HSB_ANCHOR2_PROP, v) <= 0 || !v[0]) return NULL;
    if (strcmp(v, "pilfer_pointers") == 0) return "pilfer_pointers";
    return NULL;
}

/* 第三锚点 = **只支持** 4 个只读观测件：`nav_hot_zone` / `judge_probe` / `pilfer_probe` /
 * `action_probe`（后者是系统"是否处理该事件"总闸，静勘确认是角区 pilfer 的放行闸）。
 * 空/其它值 = 不装第三锚点（不猜）。 */
static const char *hsb_chosen_anchor3(void) {
    char v[64] = {0};
    if (__system_property_get(HSB_ANCHOR3_PROP, v) > 0 && v[0]) {
        if (strcmp(v, "nav_hot_zone") == 0) return "nav_hot_zone";
        if (strcmp(v, "judge_probe") == 0) return "judge_probe";
        if (strcmp(v, "pilfer_probe") == 0) return "pilfer_probe";
        if (strcmp(v, "action_probe") == 0) return "action_probe";
        return NULL;
    }
    /* 缺省（2026-10-17）：抑制开关开 ⇒ 自动挂 `action_probe`（抑制的落点）。
     * 这样"底角斜滑独占"在设置里打开即生效，不再依赖 debug 属性（重启即失）。
     * 抑制开关本身也在 action 桩里按同一属性读（1 秒缓存）。 */
    char s[8] = {0};
    if (__system_property_get(HSB_CORNER_SUPP_PROP, s) > 0 && s[0] == '1') return "action_probe";
    return NULL;
}

typedef uintptr_t (*HsbFn16)(uintptr_t a0, uintptr_t a1, uintptr_t a2, uintptr_t a3,
                             uintptr_t a4, uintptr_t a5, uintptr_t a6, uintptr_t a7,
                             double d0, double d1, double d2, double d3,
                             double d4, double d5, double d6, double d7);
/*
 * 为什么桩要显式收 d0-d7（2026-10-09 第 2 步）：AAPCS64 的实参/形参分配是**两套独立队列**——
 * 前 8 个整型进 x0-x7，前 8 个浮点/SIMD 进 v0-v7（s0-s7/d0-d7）。Rust 侧
 * `init_gesture_type` / `match_region_check` / `is_in_bottom_gesture_region` 就是用
 * s0/s1 传 DOWN 坐标的。若桩只声明 8 个整型参数，编译器可自由拿 v0-v7 当临时寄存器，
 * 等我们把参数转给 backup 时坐标已经变成垃圾——对纯分类函数来说这等于把系统手势判废。
 *
 * 声明成"8 整型 + 8 double"后，编译器必须把这 16 个值分别放回 x0-x7 / v0-v7，
 * 于是**原样进、原样出**：对不吃 FP 参数的锚点，v0-v7 里是上一个函数留下的垃圾，
 * 我们不看也不碰，转给 backup 时又原样还回去，寄存器状态与入口逐位一致。
 * 用 double 而非 float/uint64：double 才会落进 d0-d7；位级上，调用方若只写了 s0-s7
 * 的低 32 位，转发时高 32 位是无意义填充，被调方只读低 32 位 ⇒ 两种情况都安全。
 */

static struct {
    uint32_t attempts;        /* 扫描+安装尝试次数（多来源每拍一次） */
    uint32_t matches_phdr;    /* dl_iterate_phdr 命中目标镜像的条数 */
    uint32_t matches_maps;    /* maps 里目标库可执行段条数 */
    uint32_t prologue_checked;
    uint32_t prologue_mismatch;
    uint32_t prologue_masked;     /* 靠「PC 相对字段掩码」才通过的锚点数（>0=跨版本漂移，属正常） */
    uint32_t sig_scans;           /* 走特征扫描兜底的次数（偏移路全崩才走） */
    uint32_t sig_hits;            /* 最近一次特征扫描的命中数（≠1 一律不用） */
    uint32_t hook_installed;
    uint32_t hook_failed;
    uint32_t hits;
    uint64_t installed_at_s;   /* 安装时刻（CLOCK_MONOTONIC 秒）——心跳的"存活时长"基准 */
    uint64_t last_log_s;       /* 最近一次出声时刻（心跳去抖） */
    uintptr_t target;          /* 被改写的地址（导出侧 root 读内存复核用，也进日志） */
    uintptr_t bias;           /* 最终采纳的 load bias（sigscan 命中时为 0） */
    char reason[160];
    char anchor[40];          /* 实际挂上的锚点名（桩日志与报告都用它） */
    char how[16];             /* offset | sigscan */
    char lib_path[192];       /* maps 里目标库首条映射路径（namespace 取证） */
    int has_xy;               /* 该锚点以 s0/s1 传坐标 ⇒ 日志打 x/y（见 HsbFn16） */
    int has_ret;              /* 返回值带门控信息 ⇒ 日志打 ret=（见 hsb_anchor_has_ret） */
    int has_args4;            /* 签名验证轮 ⇒ 日志打全 a0-a3（见 hsb_anchor_has_args4） */
    int has_dims;             /* 日志打 self+0x244/+0x260 两个 int（见 hsb_anchor_has_dims） */
    int has_gate;             /* 跑角区门控（见 hsb_anchor_has_gate） */
    int has_latch;            /* DOWN 锁存角区（见 hsb_anchor_has_latch） */
    int has_back_event;       /* 以 event(x1) 传 MotionEvent，读权威坐标（见 hsb_anchor_has_back_event） */
} g_hook;

static HsbFn16 g_orig;
static volatile uint32_t g_stub_logged;

/* ===== 补丁完整性巡检的全局（定义体在文件后段 hsb_heartbeat_thread；见 plan §5.2 G1）=====
 * 为什么需要（2026-10-14 夜真机 12:27）：每条手势都 `native 未曾握手`、通道里最后一条发布
 * 老了 42 分钟 ⇒ 桌面侧 native 静默，而旧日志**没有任何一行**能区分"没装上"与"被抹掉"
 * （原来那条 `hook ALIVE` 只是 HIT 行的限流版，只有事件到达才打印）。
 *   g_patch_snapshot = 安装成功后**立刻读回**的目标 32B（补丁还在的样子）
 *   g_patch_orig     = profile 里的原始序言（补丁被抹后应长成的样子） */
static uint8_t g_patch_snapshot[32];
static uint8_t g_patch_orig[32];
static volatile int g_patch_armed;
static volatile int g_patch_wiped;
static uint32_t g_hb_started;
/* 第二锚点（动作位 pilfer_pointers）：独立的 backup 与存活标志 */
static HsbFn16 g_orig_pilfer;
static uint32_t g_hook2_installed;
static char g_hook2_name[40];
/* 第三锚点（只读观测 nav_hot_zone）：g_orig_navprobe / g_navprobe_logged /
 * g_navprobe_last_rv 与桩一起定义在下方（hsb_stub_navprobe 之上），这里只留存活标志。
 * 2026-10-16 起附锚点也纳入 G3 自愈（原先只自愈主锚点 ⇒ 实测附锚点装上 235s 被抹后
 * 静默失效，action_probe 白装一轮）。target/snap/prol 三个字段就是为巡检准备的：
 *   g_hook3_target = 安装时的绝对地址（抹除后地址不变，ROM 是还原字节不是搬家）
 *   g_hook3_snap   = 安装后立刻读回的 32B（补丁在位的样子）
 *   g_hook3_prol   = profile 原始序言（补丁被抹后应长成的样子） */
static uint32_t g_hook3_installed;
static char g_hook3_name[40];
static uintptr_t g_hook3_target;
static uint8_t g_hook3_snap[32];
static uint8_t g_hook3_prol[32];
static uint32_t g_hook3_heals;
static uint32_t g_hook3_heal_fail;
/* rv != 1 采样行的限流计数（独立于 g_stub_logged/g_cs.eval：那两者在 rv 恒 0 时都不涨，
 * 用它做限流会变成"永远只打第 1 条"或"一条都不打"）。 */
static volatile uint32_t g_rv_logged;

/* 该锚点是否以 s0/s1 传坐标（决定日志要不要打 x/y；见 HsbFn16 的注释） */
static int hsb_anchor_has_xy(const char *name) {
    static const char *const kXy[] = {
        "init_gesture_type", "is_in_bottom_gesture_region", "match_region_check", NULL,
    };
    for (int i = 0; kXy[i]; i++) if (strcmp(name, kXy[i]) == 0) return 1;
    return 0;
}

/*
 * AArch64 读 FP 参数的正确姿势（2026-10-09 实踩）：**写 S 寄存器会把 V 寄存器的高 32 位
 * 清零**。Rust 侧传的是 float（`match_region_check` 里是 `fcvtzs w8, s0`，double 会写 d0），
 * 于是调用方留下的是 `v0 = [0x00000000][float_bits]`。桩按 double 收参（转发要位级不变），
 * 直接 `(float) d0` 会把整个 64 位当成一个次正规 double（指数域全 0）⇒ 转出来恒为 0.0——
 * 首轮装机就是这么错的：装对了、五个坐标全是 0。
 *  ⇒ 取**低 32 位**按 float 重新解释；double 调用方高位非 0，此时取低 32 位会得 0，
 *    靠同时打原始 hex 分辨（见 hsb_stub 的日志）。
 * 转发侧不用改：把 double 原样传回 backup，被调方只读低 32 位就对——所以这个 bug
 * 从头到尾只影响我们的日志，没有影响过系统行为。
 */
static float hsb_lo32_float(double d) {
    uint32_t bits;
    memcpy(&bits, &d, sizeof(bits));
    float f;
    memcpy(&f, &bits, sizeof(f));
    return f;
}

/* 原始位型，只为日志里能一眼分清"float 传参"还是"double 传参" */
static uint64_t hsb_bits64(double d) {
    uint64_t b;
    memcpy(&b, &d, sizeof(b));
    return b;
}

/*
 * 返回值是否带门控信息（2026-10-09 定，只给这三个打 ret=）：
 *  - is_in_bottom_gesture_region：168B，`cmp w0,#1; cset w0,eq` ⇒ **返回布尔"在底部手势区"**。
 *    它就是 match_region_check 的一层包装，且只吃 s0/s1、不暴露内部 region 结构，
 *    是底角门控的首选锚点（比直接挂 match_region_check 稳：不用解析 Rust 私有字段布局）。
 *  - match_region_check：344B 纯分类，返回区域类型码。反汇编出的完整映射：
 *        @0x30 矩形              → w0=2
 *        @0x10 矩形              → w0=0（w1=0）
 *        @0x20 矩形              → w0=0（w1=1）
 *        @0x04 矩形+#0x50 开关    → w0=1   ← **底部手势区**（is_in_bottom_* 比的就是它）
 *        @0x40/#0x48/#0x4c 复合条件满足 → w0=1（同上一条的另一分支）
 *        都不中                   → w0=4   ← 对应日志串 "Down event ... outside valid region, redirecting"
 *    我们只收 x0（w0=主判别值）；w1 在 x1、C 收不到，底角门控用 is_in_bottom_* 代替。
 *  - try_delegate_back_to_sidebar：返回 back 是否委托给侧边栏，第 1 步已验有意义。
 * 其余不打：init_gesture_type 的返回值被调用方**整段丢弃**（判定写进 self 字段），
 * on_swipe_start/on_swipe_process 等属过程信号，返回值不是门控判据。
 */
static int hsb_anchor_has_ret(const char *name) {
    static const char *const kRet[] = {
        "is_in_bottom_gesture_region", "match_region_check",
        "try_delegate_back_to_sidebar", NULL,
    };
    for (int i = 0; kRet[i]; i++) if (strcmp(name, kRet[i]) == 0) return 1;
    return 0;
}

/*
 * 第 3 步 3.1 签名验证：打全 4 个整型参数。
 * 背景——反汇编从调用点定出的签名（但仍未实机确认）：
 *   cancel_home_gesture_to_engine(x0=self+0x10, x1=rodata 常量串, x2=?, x3=?)
 *     调用点 dispatch_to_inner_monitor+0x324：`add x0,x21,#0x10` / `adrp+add x1` /
 *     `mov x2,x24` / `mov x3,x20`。x1 是 rodata 常量（日志/原因标签），真正要确认的是
 *     x2/x3——对着同手势的 (x,y) 看它们像不像屏幕坐标。
 *   pilfer_pointers(x0=self)：本体首条 `cbz x0, ...`，null 即返回 ⇒ 只 1 参。
 * 本阶段**只打日志不调用**：签名未验证前调用 = 崩桌面。x1 是常量串这件事本身也是
 * 证据——打出来若始终是同一个固定地址，就证实 x1 不是调用方数据。
 */
static int hsb_anchor_has_args4(const char *name) {
    return strcmp(name, "cancel_home_gesture_to_engine") == 0 ? 1 : 0;
}

/*
 * 第 3 步 3.1 结论：cancel_home_gesture_to_engine **不在正常手势路径上**。
 * 调用点 dispatch_to_inner_monitor+0x324 前有四重守卫，全中才调：
 *     cmp w23,#0x5 / ldr w8,[x21+0x108] cmp #0x1 / ldar w8,[0xf1908d0] cbnz /
 *     ldrb w8,[0xf1908d4+1] tbz #0
 * 装后实测零命中，与反汇编一致。它是"放弃手势"的兜底（无障碍/被抢占场景），
 * 不是"识别→pilfer"主链路 ⇒ 作为角区动作函数是错的，已弃用。
 *
 * 真正的主链路（日志串实锤）：`Home gesture recognized, delay pilfer` →
 * `Home pilfer_pointers`，pilfer 的调用方全在 on_input_event 里（0x7af870/0x7afd1c/0x7b133c，
 * 三处 x0 都是 `ldr x0,[x20,#0x1d8]` = impl 里另一个对象，不是 Impl 本体）。
 * 但直接调 pilfer_pointers 也没用——它让**系统自己的 monitor** 抢流，正是要阻止的事。
 * 所以动作只能落在"分类结果"上：match_region_check 对不命中任何矩形返回 4，系统对此
 * 有设计好路径 `Down event ... outside valid region, redirecting`（重定向给当前窗口、
 * 不认领、不 pilfer）。该串由 hyper_os_ui 的 window 层消费（0x759c44，非桌面 Rust 侧）。
 *
 * **2026-10-10 离线补验（把 3.2 的那个风险问号关掉）**：`is_in_bottom_gesture_region`
 * 的返回值**不是**观测用死值，它就是 on_input_event 的分类决策本身：
 *     0x7af678  bl is_in_bottom_gesture_region
 *     0x7af67c  mov  w23, w0              ← 返回值被接住（不是丢弃）
 *     0x7af6e8  strb w23, [x20,#0xf9]     ← 存进 impl 字段
 *     0x7afaa4  ldrb w8, [x20,#0xf9]
 *     0x7afaa8  cmp  w8, #0x1
 *     0x7afaac  b.ne <give-up 分支>
 * 而 give-up 分支打的是 `give up: first pointer not in bottom region`（rodata 0x1da0da），
 * 并把状态置 3。⇒ 把 rv 强改 0 不是"让系统回头去评估边缘区"，而是让它走自己那条
 * 现成的"不在底部区 ⇒ 放弃这一击"路径——这正是"让路"要的效果，且是本函数内部的分支，
 * 不牵动边缘/返回判定。19:15 那轮 FORCE 后同一手势在 spy 拿到完整流（k#6 SHOW）也吻合。
 *
 * 屏宽屏高从哪来（三个 lever 的差别）：
 *   is_in_bottom_gesture_region → x0=**Impl self** ⇒ 可从 self+0x244 / self+0x260
 *     读两个 int（is_point_out_of_screen 就是这么用的：fcmp x↔[+0x260]、y↔[+0x244]）
 *   match_region_check         → x0=region 结构，且不同调用点偏移不同
 *     （init_gesture_type 传 impl+0x138、is_in_bottom_* 传 impl+0x238）⇒ 推不出 Impl
 *   pilfer_pointers            → x0=[impl+0x1d8]，是另一个对象 ⇒ 拿不到屏宽屏高
 * ⇒ is_in_bottom_gesture_region 是唯一同时给足"分类决策 + 坐标 + 屏幕尺寸"的点。
 */

/* is_in_bottom_gesture_region 专用：把 self+0x244 / self+0x260 两个 int 打出来。
 * 期望 1080 / 2400（ marble 1080x2400）。顺序待实机确认：按
 * is_point_out_of_screen 的用法，+0x260 与 x(s0) 比 ⇒ 宽，+0x244 与 y(s1) 比 ⇒ 高。 */
static int hsb_anchor_has_dims(const char *name) {
    return strcmp(name, "is_in_bottom_gesture_region") == 0 ? 1 : 0;
}

/* ===== 第 3 步 3.2：角区门控（分档，见下方） =====
 *
 * 屏宽屏高已实机坐实（22:23 那轮）：self+0x260 = 宽 = 1080，self+0x244 = 高 = 2400，
 * 与 is_point_out_of_screen 的用法一致。所以三个输入（is_in_bottom_gesture_region 的
 * 返回值、坐标 s0/s1、屏幕尺寸）全在同一个 hook 里，不需要跨进程也不需要额外挂点。
 *
 * 门控判据（几何见下方 hsb_corner_geom，与 :ui 同源；下式为属性缺失时的兜底）：
 *     命中 = (rv == 1) && (x < cornerW || x > w - cornerW) && (y > h - hotH)
 *   - rv==1     系统判定"在底部手势区"（12 次角区实测全 1，中部 6 次全 0）
 *   - x 收窄    12 次角区 x 落在 39-131 与 1029-1061；中部 Home 的 x 是 446-755
 *   - y 收窄    实测角区 y 2355-2400 ⇒ 底部带；兜底取 h*0.90 = 2160，留 240px 余量
 *
 * **分档**（用户拍板：先观测再动作）：
 *   debug.hsb.native.corner = 0（默认）⇒ 只打 `corner gate` 判定日志，不改任何返回值。
 *       目的是拿真实命中分布 + 确认判定阈值，且不承担任何行为风险。
 *   debug.hsb.native.corner = 1 ⇒ 命中时把 rv 强改成 0（"不在底部区"）。
 *       ⚠ 2026-10-10 反汇编更正：原先记的风险"系统可能转而评估边缘区、把角滑误判成
 *       返回手势"**不成立**。rv 就是 on_input_event 的分类决策本身（存 impl+0xf9），
 *       强改 0 之后它走自己那条现成的 give-up 分支（`give up: first pointer not in
 *       bottom region`，状态置 3），是本函数内部的分支，不牵动边缘/返回判定。
 *       19:15 那轮实测也吻合：FORCE 后同一手势 spy 拿到完整流、k#6 SHOW。
 *       所以 =1 的风险等级从"可能误触返回"降到"系统放弃这一击（可能连它自己的
 *       Home 上滑也被放弃）"——后者正是本机制的目的，代价只是阈值必须准。
 *       =0 观测档仍按原计划先跑，用它把阈值钉死，再开 =1。
 *
 * 只对 is_in_bottom_gesture_region 生效：它是唯一 x0=Impl self 的锚点，也是唯一
 * 手里同时有"系统判定 + 坐标 + 屏幕尺寸"的点。 */
#define HSB_CORNER_PROP "debug.hsb.native.corner"
/* 双锚点方案开关（2026-10-11）：=1 启用"init_gesture_type 锁存 + pilfer_pointers 拦截"。
 * 与 HSB_CORNER_PROP（旧 FORCE 档）独立：旧档降级为纯观测，新档才是动作。 */
#define HSB_CORNER2_PROP "debug.hsb.native.corner2"

static int hsb_anchor_has_gate(const char *name) {
    return strcmp(name, "is_in_bottom_gesture_region") == 0 ? 1 : 0;
}

/* nav_hot_zone / judge_probe / pilfer_probe / action_probe：都是**只读观测件**（透传 + 打
 * 日志），不参与任何判定。坐标来源见各自锚点注释；action_probe 的入参是 launcher 自己的
 * UND getter 读出来的（hsb_back_event_read），不是我们 spy 侧口径。
 * 这个 flag 存在的意义只有一个：把"只读观测"从"有门控要改"里分出来，免得后人顺手把它并进
 * has_gate / has_ret 那两个会改系统判定的集合里。 */
static int hsb_anchor_has_navprobe(const char *name) {
    return strcmp(name, "nav_hot_zone") == 0 || strcmp(name, "judge_probe") == 0 ||
           strcmp(name, "pilfer_probe") == 0 || strcmp(name, "action_probe") == 0
               ? 1
               : 0;
}

/* init_gesture_type：DOWN 拍、(x,y)∈s0/s1（`fmov s8,s0`+`stp s0,s1`，2026-10-11 反汇编坐实）
 * ⇒ 角区锁存点（见 hsb_latch_down）。它同时是 match_region_check 的调用者，self 即 monitor impl，
 * +0x244/+0x260 就是屏高/宽。 */
static int hsb_anchor_has_latch(const char *name) {
    return strcmp(name, "init_gesture_type") == 0 ? 1 : 0;
}

/* handle_back_gesture(self=x0, event=x1)：x1 是可读 MotionEvent（对 x1 调
 * `input_MotionEvent_getActionMasked@plt`，2026-10-12 反汇编坐实）⇒ 用同族 getter 读**权威坐标**。
 * 侧滑线（GestureInputBackHelper 家族）的确认层要用它，见 hsb_back_event_log。 */
static int hsb_anchor_has_back_event(const char *name) {
    return strcmp(name, "handle_back_gesture") == 0 ? 1 : 0;
}

/* 属性读取按 1 秒粒度缓存：__system_property_get 不是免费的，
 * 而这个 hook 在每个输入事件上都会跑（on_input_event 的两处调用）。 */
static uint64_t hsb_now_s(void);   /* 定义在下方，前向声明供本处使用 */

static int g_corner_on;
static uint64_t g_corner_checked_s;

static int hsb_corner_enabled(void) {
    uint64_t now = hsb_now_s();
    if (g_corner_checked_s != now) {
        char v[8] = {0};
        g_corner_checked_s = now;
        g_corner_on = __system_property_get(HSB_CORNER_PROP, v) > 0 && v[0] == '1';
    }
    return g_corner_on;
}

/*
 * 角区几何**不再写死**（2026-10-11）：原先 native 用 `W/5`、:ui 的 CornerTrigger 用
 * `W×cornerWidthRatio`（默认 0.12），两边各自一份常量 ⇒ 左角 x∈(129.6, 216) 这段
 * native 判"是角区"并让系统放弃这一击、:ui 却因 `x > cornerWidth` 不 claim，
 * 手势被吞（扇形和 Home 都没有）。而且用户拖角区宽度滑条会让这段死带实时变宽。
 *
 * 现在由 Java 侧把**与 CornerTrigger 同一份值**写进属性（传比例，像素在这里算），
 * native 用它自己从 Impl self 读到的 w/h 换算：
 *   persist.hsb.corner.w_ratio  角区宽 / 屏宽（浮点）；判定用 `x <= w*ratio` 或其右镜像
 *   persist.hsb.corner.hot_pct  底部热区高 / 屏高的百分比（浮点）；判定用 `y >= h - h*pct/100`
 * 属性缺失/非法 ⇒ 回落兜底值。兜底取**合法配置里最窄的一侧**（宽 10% 屏宽、
 * 热区 3% 屏高 = :ui 两个滑条的下限）：属性没送达时 native 只会少覆盖一点
 * （少让路一次，扇形照旧），**绝不会**出现"native 吞了、:ui 不认"的死带；
 * 反过来若兜底取宽值，用户把 :ui 滑条拉到最窄时立刻产生死带。
 * 死带不可完全消除：:ui 改滑条到 native 缓存刷新之间有一拍差异（属性 1 秒粒度），
 * 但稳态下两侧同源。 */
/* 用 persist. 前缀而不是 debug.：debug.* 每次重启被清空，而 Java 侧只在"用户改设置"
 * 时推一次 ⇒ 用 debug. 的话重启后这里永远只能读到兜底值，用户设的角区宽度静默失效。
 * persist. 由 init 落盘并在开机回灌，写一次长期有效。
 *
 * ⚠ 传**比例**而不是像素（2026-10-11 实机踩坑）：先前 Java 侧自己按 WindowManager
 * 算像素，结果把 max/min 用反了（写出 2400×0.18=432 而非 1080×0.18=194），而屏宽
 * 在横竖屏/折叠/多窗下本来就不该由另一个进程代算。这里读 w/h 的来源是 Impl self
 * （+0x260/+0x244），与手势引擎同源，权威。 */
#define HSB_CORNER_W_PROP "persist.hsb.corner.w_ratio"    /* 浮点：角区宽 / 屏宽 */
#define HSB_CORNER_HOT_PROP "persist.hsb.corner.hot_pct"  /* 浮点：底部热区高 / 屏高的百分比 */

#define HSB_CORNER_W_FALLBACK_RATIO 0.10f   /* = CORNER_WIDTH_RATIO_MIN */
#define HSB_CORNER_HOT_FALLBACK_PCT 3.0f    /* = CORNER_HOT_PERCENT_MIN */

/* 属性读取按 1 秒粒度缓存：__system_property_get 不是免费的，
 * 而这个 hook 在每个输入事件上都会跑（on_input_event 的两处调用）。 */
static float g_corner_w_px;
static float g_corner_hot_px;
static uint64_t g_geom_checked_s;

/* 读一个 [lo, hi] 内的浮点配置；缺失/非法/越界/NaN 一律返回 dflt（不猜）。
 * 2026-10-17：读取源换成 [hsb_cfg_float]（文件 `/data/local/tmp/hsb.conf` 优先、系统属性次之、最后 dflt），
 * 原 `hsb_prop_float`（只读属性）已删除——同一语义，只是多了一路免 root 的来源。 */

/*
 * 刷新角区几何（属性驱动）并打一行。返回 0 = self 的 w/h 读不出来。
 *
 * ⚠ 必须**无条件调用**（每次 hook 命中都调，独立于 rv）：原来它只在 rv==1 分支里跑，
 * 而实测出现过整轮 rv 恒 0 ⇒ 连"属性到底写没写进去"都看不到，只能靠猜。
 * 内部按 1 秒粒度缓存属性读取，热路径成本只有两次 int 读。
 */
static int g_geom_w_off = -1;
static int g_geom_h_off = -1;

/* 屏宽/高在 self 上的偏移**随锚点结构而变**（2026-10-11 真机实锤）：
 *  - is_in_bottom_gesture_region 的 self = GestureInputMonitorImpl 本体 ⇒ w/h 在 +0x260/+0x244
 *    （22:23 那轮已坐实 2400/1080）；
 *  - init_gesture_type 的 self = GestureInputMonitor，它是 Impl 的**子对象**：
 *    dispatch_to_inner_monitor 里 `x2=0 ⇒ x21 = Impl+0x100`、`x0 = x21+0x10 = Impl+0x110`
 *    ⇒ Monitor = Impl + 0x110 ⇒ w/h 在 +0x150/+0x134。
 * 首轮真机就是这么挂的：latch 用 Impl 偏移读 Monitor ⇒ w/h 非法 ⇒ hsb_corner_geom 早期返回
 * （连 `corner geom` 都不打）⇒ corner 恒 0（日志里 x=82.6 明明是左角却 corner=0）。
 * 所以偏移必须由调用方给，不能写死。 */
#define HSB_DIMS_W_IMPL 0x260
#define HSB_DIMS_H_IMPL 0x244
#define HSB_DIMS_W_MON  0x150   /* = Impl+0x260 - 0x110 */
#define HSB_DIMS_H_MON  0x134   /* = Impl+0x244 - 0x110 */

/* 角区比例的单一来源（1 秒缓存）。抽出来给两处用：`hsb_corner_geom`（Impl self 口径）
 * 和 action 桩（手里只有 MotionEvent、屏尺寸走 band 属性口径）。
 * 2026-10-11 的死带事故就是"native 与 :ui 各一份常量"造成的，这里不要再造第三份。 */
static float g_crn_ratio, g_crn_hot_pct;
static uint64_t g_crn_ratio_s;

static void hsb_corner_ratio(float *wr, float *hp) {
    uint64_t now = hsb_now_s();
    if (g_crn_ratio_s != now) {
        g_crn_ratio_s = now;
        /* 比例取 [0.02, 0.50] / [1, 50]：越界或非数字一律回落兜底（不猜）。
         * 上限同时挡住"属性写坏把整个屏幕变成角区"。 */
        g_crn_ratio = hsb_cfg_float(HSB_CORNER_W_PROP, 0.02f, 0.50f, HSB_CORNER_W_FALLBACK_RATIO);
        g_crn_hot_pct = hsb_cfg_float(HSB_CORNER_HOT_PROP, 1.0f, 50.0f, HSB_CORNER_HOT_FALLBACK_PCT);
    }
    *wr = g_crn_ratio;
    *hp = g_crn_hot_pct;
}

/* 角区判定（纯几何，无 I/O、无指针解引用）——`hsb_corner_gate` 与 action 桩共用。
 * why_out：0=命中，1=坐标不在角区，2=横屏，3=尺寸非法。传 NULL 表示调用方不关心。
 *
 * 与 :ui `SystemUiInputSpy.inCornerTriangle` 逐行对齐（w>h 直接 false / 底部热区 /
 * 左右镜像），这是当年那份注释里"两侧口径必须一致"的落地。 */
static int hsb_corner_hit_xy(float x, float y, float w, float h, float ratio, float hot_pct,
                             int *why_out) {
    if (!(w > 0.0f) || !(h > 0.0f)) { if (why_out) *why_out = 3; return 0; }
    if (w >= h) { if (why_out) *why_out = 2; return 0; }   /* 横屏不判：对齐 :ui 横屏走 B 路线 */
    float edge = w * ratio;
    float yband = h - (h * hot_pct / 100.0f);
    int hit = (x <= edge || x >= w - edge) && (y >= yband);
    if (why_out) *why_out = hit ? 0 : 1;
    return hit;
}

static int hsb_corner_geom(uintptr_t self, int w_off, int h_off) {
    const volatile int *b = (const volatile int *) self;
    int w = b[w_off / 4];
    int h = b[h_off / 4];
    if (w <= 0 || w >= 100000 || h <= 0 || h >= 100000) return 0;
    uint64_t now = hsb_now_s();
    if (g_geom_checked_s == now && g_geom_w_off == w_off && g_geom_h_off == h_off) return 1;
    g_geom_checked_s = now;
    g_geom_w_off = w_off;
    g_geom_h_off = h_off;
    float wr, hp;
    hsb_corner_ratio(&wr, &hp);
    g_corner_w_px = (float) w * wr;
    g_corner_hot_px = (float) h * hp / 100.0f;
    /* 几何每次刷新打一行（1 秒粒度，低频）：装错属性/回落兜底都当场可见，
     * 免得"native 判命中、:ui 不认"这种两侧口径不一致要靠猜。**offsets 也打**——
     * 首轮真机就是"读不到 w/h 却连一行都没有"，偏移错了完全不可见。 */
    LOGI("corner geom w=%d h=%d cornerW=%.1f hot=%.1f (off w=+0x%x h=+0x%x prop w_ratio=%.4f hot_pct=%.2f)",
         w, h, g_corner_w_px, g_corner_hot_px, w_off, h_off, wr, hp);
    return 1;
}
/*
 * 角区链路累计计数（2026-10-10 加）。
 *
 * 为什么必须加：19:15 那轮 15 次角滑只出 1 条 `corner gate FORCE`。命中行本身是**无条件
 * 打印**的（不受 HSB_LOG_LIMIT 约束，另外第 1 次 eval 与每 25 次 eval 各补一条采样行，
 * 见下方 gate 日志）。但"没命中"有好几种完全不同的病，旧日志里长得一模一样，无法归因：
 *   fires 不涨        ⇒ hook 根本没被调用（系统不再走这条路，或让路已生效）
 *   fires 涨 eval=0   ⇒ hook 在跑，但系统不再判底部区
 *   eval 涨 hit=0     ⇒ 判定在跑，但坐标不在角区（阈值问题）；若同时 bad 涨，那是 self 的
 *                        w/h 读不到（偏移/野指针），与阈值无关
 *   hit 涨 force 不涨 ⇒ 只有 corner=1 档才是"属性没读到"；corner=0 档本来就该如此
 * 六个数分开，一眼定位，不用再烧一轮装机试错。
 *
 * ⚠ 计数是**桌面进程生命周期内累计**，全程不复位（含 corner 0→1 切换）。所以 A/B 两档
 * 必须各自重启桌面后再读；同一进程里换档读到的数是两档的叠加，会把"命中但没 force"
 * 误读成"属性没读到"。
 *
 * fires 在 hsb_stub 里累加（仅 has_gate 的锚点），其余在 hsb_corner_gate 里累加；
 * 返回给调用方拼进日志。 */
typedef struct {
    uint32_t fires;   /* hook 被调用总次数（含没打日志的）。⚠ 门控锚点下它与 g_hook.hits
                       * 恒等（同一个桩、同一个 has_gate 条件）——报告里的 hits= 和这里的
                       * fires= 是同一个数，别当两个独立读数去对账。分开留着是为了将来
                       * 门控扩到多锚点时 fires 仍只算门控那部分。 */
    uint32_t eval;    /* 其中 rv==1（系统判成底部区）的次数 */
    uint32_t hit;     /* 其中命中角区的次数 */
    uint32_t miss;    /* eval 了但没命中角区的次数 */
    uint32_t force;   /* 其中真的强改了返回值的次数 */
    uint32_t bad;     /* eval 了但 self 的 w/h 读不出来（偏移错/野指针）——与"不在角区"分开计 */
    uint32_t skip;    /* eval 了但横屏 ⇒ 不判（对齐 :ui 只做竖屏）。单独一档，否则横屏的
                       * eval 落不进 hit/miss/bad 任何一格，六数分区不自洽 */
    uint32_t latch;          /* init_gesture_type 锁存 DOWN 的次数（双锚点方案，见 hsb_latch_down） */
    uint32_t pilfer_blocked; /* pilfer_pointers 被拦（锁存=角区）的次数 */
    uint32_t pilfer_passed;  /* pilfer_pointers 透传的次数 */
} HsbCornerStats;

static HsbCornerStats g_cs;

/*
 * 角区判定。force=1 时命中即把 *io_rv 改成 0。
 * x/y 走 hsb_lo32_float（低 32 位按 float，见该函数注释——AArch64 写 S 寄存器
 * 会清零 V 高位，按 double 读整个 64 位会得次正规数）。
 * w/h 读自 self（已实测 +0x260=宽 / +0x244=高），并做值域校验——野指针会让
 * 这个跑在每个输入事件上的 hook 直接崩桌面，宁可不判也不能崩。
 */
/* why_out 回填未命中的原因（1=坐标不在角区 2=横屏跳过 3=dims 读不出），仅用于日志标签；
 * 不参与判定。传 NULL 表示调用方不关心。 */
static int hsb_corner_gate(uintptr_t self, double dx, double dy, uintptr_t rv,
                           int force, uintptr_t *io_rv, int *why_out) {
    if (rv != 1u) return 0;                     /* 系统都没判成底部区，无需收窄 */
    __atomic_add_fetch(&g_cs.eval, 1u, __ATOMIC_RELAXED);
    /* 几何已由调用方（hsb_stub）无条件刷新过；这里只为拿 w/h 做横屏与判定。
     * 再调一次是幂等的（1 秒缓存），保证不依赖调用顺序。 */
    if (!hsb_corner_geom(self, HSB_DIMS_W_IMPL, HSB_DIMS_H_IMPL)) {
        __atomic_add_fetch(&g_cs.bad, 1u, __ATOMIC_RELAXED);
        if (why_out) *why_out = 3;
        return 0;
    }
    const volatile int *b = (const volatile int *) self;
    float w = (float) b[HSB_DIMS_W_IMPL / 4];
    float h = (float) b[HSB_DIMS_H_IMPL / 4];
    float ratio, hot_pct;
    hsb_corner_ratio(&ratio, &hot_pct);
    int why = 0;
    int hit = hsb_corner_hit_xy(hsb_lo32_float(dx), hsb_lo32_float(dy), w, h, ratio, hot_pct,
                                &why);
    if (why_out) *why_out = why;
    if (why == 2) __atomic_add_fetch(&g_cs.skip, 1u, __ATOMIC_RELAXED);
    if (why == 3) __atomic_add_fetch(&g_cs.bad, 1u, __ATOMIC_RELAXED);
    if (hit) {
        __atomic_add_fetch(&g_cs.hit, 1u, __ATOMIC_RELAXED);
        if (force) {
            *io_rv = 0u;
            __atomic_add_fetch(&g_cs.force, 1u, __ATOMIC_RELAXED);
        }
    } else if (why == 1) {
        /* 只在"坐标不在角区"时记 miss：横屏/尺寸非法各有自己的计数器（六数分区自洽） */
        __atomic_add_fetch(&g_cs.miss, 1u, __ATOMIC_RELAXED);
    }
    return hit;
}

/* 把累计数拼成日志后缀，挂到 FORCE / WOULD-HIT / MISS 与心跳行上 */
static void hsb_cs_suffix(char *out, size_t cap) {
    snprintf(out, cap,
             " [fires=%u eval=%u hit=%u miss=%u bad=%u skip=%u force=%u latch=%u pblk=%u ppass=%u]",
             g_cs.fires, g_cs.eval, g_cs.hit, g_cs.miss, g_cs.bad, g_cs.skip, g_cs.force,
             g_cs.latch, g_cs.pilfer_blocked, g_cs.pilfer_passed);
}

/* 心跳用的单调时钟与上次出声时刻（秒） */
static uint64_t hsb_now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec;
}

/* 同源毫秒时钟（侧滑线 S3 判"事件间停顿"要用毫秒粒度；秒粒度分辨不出 250ms 停顿） */
static uint64_t hsb_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec * 1000ull + (uint64_t) (ts.tv_nsec / 1000000L);
}

/* ===== 角区 DOWN 锁存 + pilfer 拦截（2026-10-11 双锚点方案）=====
 *
 * 为什么这么拆（离线反汇编 + 前几轮实机结论）：
 *  - 旧方案把动作落在 is_in_bottom_gesture_region（monitor 路的下游分类），
 *    它是**被别处消费的区域查询**（DOWN 存 self+0xf9，又被 retarget_bottom_region 写同一字段），
 *    且必须 rv==1（DOWN 落进系统那个底部矩形）才动 ⇒ "多数不触发"；
 *    而且它每个事件都调、坐标是 live 点 ⇒ "左中右 xy 差不多"。
 *  - 真正"让系统放手"的执行点是 pilfer_pointers（=AOSP InputMonitor.pilferPointers，
 *    3 个调用点全走它），但它只吃 self、没坐标 ⇒ 坐标由 init_gesture_type 锁存。
 *  ⇒ 观测（坐标+角区）与动作（拦抢流）解耦：各锚点改动面最小、互不污染。
 */
static int hsb_corner2_enabled(void) {
    static uint64_t checked_s;
    static int on;
    uint64_t now = hsb_now_s();
    if (checked_s != now) {
        char v[8] = {0};
        checked_s = now;
        on = __system_property_get(HSB_CORNER2_PROP, v) > 0 && v[0] == '1';
    }
    return on;
}

typedef struct {
    uint64_t at_s;    /* 锁存时刻（CLOCK_MONOTONIC 秒）；过期即失效（防 init_gesture_type 漏调时留脏值） */
    float x;
    float y;
    int corner;       /* 锁存的 DOWN 是否落在角区 */
    int valid;
} HsbLatch;
static HsbLatch g_latch;
#define HSB_LATCH_TTL_S 2ull

/* 角区判定（与 hsb_corner_gate 同源，只是 self 是 GestureInputMonitor ⇒ 用 Monitor 偏移） */
static int hsb_latch_corner_at(uintptr_t self, float x, float y) {
    if (!hsb_corner_geom(self, HSB_DIMS_W_MON, HSB_DIMS_H_MON)) {
        /* 读不到 w/h（偏移错 / self 结构不符）：**必须出声**。否则 corner 恒 0 且日志里连一行
         * 都没有——首轮真机就是这么静默失败的（x=82.6 明明是左角却 corner=0，无任何线索）。 */
        static uint32_t logged;
        uint32_t n = __atomic_add_fetch(&logged, 1u, __ATOMIC_RELAXED);
        if (n == 1u || n % 20u == 0u) {
            LOGI("corner latch NO-DIMS (self+0x%x/+0x%x unreadable) x=%.1f y=%.1f",
                 HSB_DIMS_W_MON, HSB_DIMS_H_MON, x, y);
        }
        return 0;
    }
    const volatile int *b = (const volatile int *) self;
    int w = b[HSB_DIMS_W_MON / 4];
    int h = b[HSB_DIMS_H_MON / 4];
    if (w >= h) return 0;                       /* 横屏不接管，与 :ui CornerTrigger 对齐 */
    float edge = g_corner_w_px;
    float yband = (float) h - g_corner_hot_px;
    return (x <= edge || x >= (float) w - edge) && (y >= yband);
}

/* init_gesture_type 桩调用：DOWN 拍锁存 (x,y) + 角区判定。
 * type = 系统给这个 DOWN 分类出的手势类型码（x1，2026-10-11 加）：打出来就知道
 * 系统"认没认"这个角手势——第四轮的关键谜题（角手势为何从不 pilfer）靠它定性。 */
static void hsb_latch_down(uintptr_t self, uintptr_t type, double d0, double d1) {
    float x = hsb_lo32_float(d0);
    float y = hsb_lo32_float(d1);
    g_latch.x = x;
    g_latch.y = y;
    g_latch.corner = hsb_latch_corner_at(self, x, y);
    g_latch.at_s = hsb_now_s();
    g_latch.valid = 1;
    uint32_t n = __atomic_add_fetch(&g_cs.latch, 1u, __ATOMIC_RELAXED);
    /* 角区锁存**必打**（每个角手势一行）：否则 corner=1 只在 1/20 采样里出现，
     * 真机日志里根本看不出"角到底判没判中"（22:33 那轮就是这么漏的：7 条 hook HIT
     * 里 5 条 x 明显在角上，却没有一条 corner latch 行）。非角区仍采样，防中部滑动刷屏。 */
    if (g_latch.corner || n == 1u || n % 20u == 0u) {
        char sfx[176];
        hsb_cs_suffix(sfx, sizeof(sfx));
        LOGI("corner latch x=%.1f y=%.1f corner=%d type=0x%lx%s",
             x, y, g_latch.corner, (unsigned long) type, sfx);
    }
}

/* pilfer 桩调用：本手势的锁存 DOWN 是否角区（过期视为否） */
static int hsb_latch_is_corner(void) {
    if (!g_latch.valid) return 0;
    if (hsb_now_s() - g_latch.at_s > HSB_LATCH_TTL_S) { g_latch.valid = 0; return 0; }
    return g_latch.corner;
}

/* ===== 侧滑线确认层①：handle_back_gesture 读权威坐标（2026-10-12，观察档） =====
 *
 * 反汇编坐实：`handle_back_gesture(self=x0, event=x1)` 对 x1 调
 * `input_MotionEvent_getActionMasked@plt`；这些 getter 是 launcher 的 **UND 动态符号**
 * （`input_MotionEvent_getRawX/getRawY/getDownTime/getDeviceId`）。用它读到的坐标是
 * **launcher 权威坐标**（不受 spy 侧 `Resources.getSystem().displayMetrics` 口径影响）。
 *
 * ⚠ 首轮真机（2026-10-12 18:22）：`dlsym(RTLD_DEFAULT, …)` **返回 0**（resolve FAILED）——
 * 定义库不在本 .so 的默认作用域。改为两级解析：先 RTLD_DEFAULT，失败再遍历已加载库、
 * 逐个 `dlopen(RTLD_NOLOAD|RTLD_NOW)` + `dlsym`（见 hsb_dlsym_all）。
 *
 * 本档**只打点、不改行为**：解析失败即 fail-closed（打一行 WARN，之后不再尝试、不调用）。 */
static int   (*g_me_getActionMasked)(void *);
static float (*g_me_getRawX)(void *, int);
static float (*g_me_getRawY)(void *, int);
static long long (*g_me_getDownTime)(void *);
static int   (*g_me_getDeviceId)(void *);
static int g_me_state;   /* 0=未试 1=成功 -1=失败 */

static int g_dlsym_probed;   /* 诊断：遍历时实际 dlopen 到的库数 */

typedef struct { const char *name; void *found; } HsbSymReq;

static int hsb_sym_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    HsbSymReq *r = (HsbSymReq *) data;
    if (r->found) return 1;
    if (!info->dlpi_name || !info->dlpi_name[0]) return 0;
    void *h = dlopen(info->dlpi_name, RTLD_NOLOAD | RTLD_NOW);
    if (!h) return 0;
    g_dlsym_probed++;
    void *p = dlsym(h, r->name);
    dlclose(h);   /* NOLOAD 只借引用；库由 launcher 持有，不会卸载 */
    if (p) { r->found = p; return 1; }
    return 0;
}

/* RTLD_DEFAULT 找不到时遍历已加载库逐个 dlsym。 */
static void *hsb_dlsym_all(const char *name) {
    void *p = dlsym(RTLD_DEFAULT, name);
    if (p) return p;
    HsbSymReq r = { name, NULL };
    dl_iterate_phdr(hsb_sym_cb, &r);
    return r.found;
}

static int hsb_me_resolve(void) {
    if (g_me_state) return g_me_state > 0;
    void *am = hsb_dlsym_all("input_MotionEvent_getActionMasked");
    void *rx = hsb_dlsym_all("input_MotionEvent_getRawX");
    void *ry = hsb_dlsym_all("input_MotionEvent_getRawY");
    void *dt = hsb_dlsym_all("input_MotionEvent_getDownTime");
    void *dv = hsb_dlsym_all("input_MotionEvent_getDeviceId");
    g_me_getActionMasked = (int (*)(void *))       am;
    g_me_getRawX         = (float (*)(void *, int)) rx;
    g_me_getRawY         = (float (*)(void *, int)) ry;
    g_me_getDownTime     = (long long (*)(void *)) dt;
    g_me_getDeviceId     = (int (*)(void *))       dv;
    int ok = am && rx && ry && dt && dv;
    g_me_state = ok ? 1 : -1;
    char amhex[17] = {0}, rxhex[17] = {0}, ryhex[17] = {0}, dthex[17] = {0}, dvhex[17] = {0};
    snprintf(amhex, sizeof(amhex), "%lx", (unsigned long) am);
    snprintf(rxhex, sizeof(rxhex), "%lx", (unsigned long) rx);
    snprintf(ryhex, sizeof(ryhex), "%lx", (unsigned long) ry);
    snprintf(dthex, sizeof(dthex), "%lx", (unsigned long) dt);
    snprintf(dvhex, sizeof(dvhex), "%lx", (unsigned long) dv);
    LOGI("back_event getters resolve: %s (am=0x%s rawx=0x%s rawy=0x%s dt=0x%s dev=0x%s probe=%d)",
         ok ? "ok" : "FAILED", amhex, rxhex, ryhex, dthex, dvhex, g_dlsym_probed);
    return ok;
}

/* ---- 事件的权威坐标快照（一次读全，供"观测"与"抑制"共用） ----
 * MotionEvent actionMasked 取值（原生定义，不引 android/input.h 只为本文件自洽）。 */
#define HSB_ACT_DOWN   0
#define HSB_ACT_UP     1
#define HSB_ACT_MOVE   2
#define HSB_ACT_CANCEL 3

typedef struct {
    int act;          /* actionMasked：0=DOWN 1=UP 2=MOVE 3=CANCEL */
    float x;          /* 权威 rawX */
    float y;          /* 权威 rawY */
    long long dt;     /* downTime —— 与 deviceId 一起当"手势身份"，判是否同一条 */
    int dev;          /* deviceId */
} HsbBackEv;

static int hsb_back_event_read(uintptr_t ev, HsbBackEv *o) {
    if (!ev || !hsb_me_resolve()) return 0;
    o->act = g_me_getActionMasked((void *) ev);
    o->x   = g_me_getRawX((void *) ev, 0);
    o->y   = g_me_getRawY((void *) ev, 0);
    o->dt  = g_me_getDownTime((void *) ev);
    o->dev = g_me_getDeviceId((void *) ev);
    return 1;
}

/* 观察档：限流打点（第 1 次 + 每 20 次） */
static volatile uint32_t g_back_logged;
static void hsb_back_event_log(const HsbBackEv *e) {
    uint32_t n = __atomic_add_fetch(&g_back_logged, 1u, __ATOMIC_RELAXED);
    if (n == 1u || n % 200u == 0u) {
        LOGI("back_event #%u act=%d raw=(%.1f,%.1f) dt=%lld dev=%d",
             n, e->act, e->x, e->y, e->dt, e->dev);
    }
}

/* ===== 侧滑线 S3「抑制（让路）」实验档（2026-10-12）=====
 *
 * B 路线（spy→pilfer）的天花板是**抢跑**：launcher 的 Rust 在 DOWN 后 10-30ms 就认领了这条
 * 侧滑并起 back，而 Java 回程（spy→广播→:ui→dwell→pilfer）慢一拍，于是最脆的一环变成
 * "pilfer 抢流竞速 + 五要素匹配"（S0 里角滑的 `cur dt=0` 即此）。native 是**唯一能在
 * "系统认领之前"动手**的位置（同进程、同调用链、零延迟）。
 *
 * 本档只做"抑制"、不做判定：命中手势的事件**整个不转发**给原函数（不调 g_orig）
 * ⇒ Rust 侧看不到 DOWN ⇒ 不认领、不起 back。spy 的 observe 副本走 InputDispatcher 另一条
 * 道，不受"我们没转发"影响；:ui 照旧判定/呼出（这正是 N3 的假设，由真机 H1/H2/H3 验）。
 *
 * ⚠ 这是**实验档、不是终态**：DOWN 处无从得知用户后续是"停顿呼出"还是"快速返回"，所以
 *   "带内起手的快速 back"本档会一起被吞（计划 §5.5 的已知代价）。是否要为它加"延迟/回放"
 *   通道，等三绿读数出来再定。
 *
 * 分档（对齐角区门控的"先观测再动作"）：
 *   debug.hsb.native.suppress = 0（默认）⇒ 只观测：带内起手打 `back WOULD-SUPPRESS`，
 *      **零行为变化**，先用它标定"带内"判定本身对不对。
 *   debug.hsb.native.suppress = 1 ⇒ 带内起手**立即**吞（整条手势，含 MOVE/UP）。
 *      ⚠ 已知代价（2026-10-12 真机）：连"带内快滑想返回"一起吞 —— DOWN 那一刻无从得知
 *      用户会不会停顿，所以"只吃 dwell 意图"这一档做不到。
 *   debug.hsb.native.suppress = 2 ⇒ **停顿后才吞**（推荐）。带内起手先只是候选、事件照常
 *      转发（back 预览照走）；只有这条手势真的停顿（≥ dwell 无"在动"）才转为吞 ⇒ 快滑的
 *      返回不受影响，而"滑出+停顿呼出"的那条不会提交 back。判据与 :ui 的 STALL 定时器同构。
 *
 * fail-closed：getter 未解析 / 触发带几何属性缺失或非法 / 非竖屏 ⇒ **不抑制**（走原生）。
 * 只有确信"这条起手落在触发带内"时才改变行为，任何不确定一律退回原生。 */
#define HSB_SUPPRESS_PROP "debug.hsb.native.suppress"
/* 停顿阈值与"还在动"的速度门：**native 里不再抄一份常量**，由 Java 侧按 :ui 同一份配置
 * 推过来（见 CornerGeomBridge.pushBand）——三处（:ui / spy / native）永远是同一个数，
 * 且测试期可 setprop 直接调，不必重编重装。
 *   dwell = TRIGGER_DWELL_MS（用户可配，默认 250ms）
 *   speed = GestureThresholds.STALL_MAX_SPEED_PX_S（默认 500px/s） */
#define HSB_STRIP_DWELL_PROP "persist.hsb.strip.dwell_ms"
#define HSB_STRIP_SPEED_PROP "persist.hsb.strip.stall_speed"
/* 确认门（**距离 + 角度**，与 :ui/OS2·OS3 的 `swipe confirmed` 同一门）与滑回滞回：
 *   confirm_px = TRIGGER_MIN_DISTANCE(dp) × density
 *   reset_px   = confirm_px × GestureThresholds.SWIPE_RESET_RATIO
 *   max_tan    = tan(GestureThresholds.MAX_SWIPE_ANGLE_DEG)
 * 送 tan 而不是角度：native 侧据此用一次乘法比完夹角，省掉 math.h/atan2。
 * 为什么要 mirro 这个门（2026-10-12 真机对账教训）：`:ui` 的 250ms 定时器是**在确认那一刻
 * 挂上**的，native 若从 DOWN 起算速度窗，两边相位差 150ms+，判"停顿"就总对不齐
 * （6 次 `:ui` 判定 native 只跟上 1 次）。确认门 + 同相位窗口之后两边才可对账。 */
#define HSB_STRIP_CONFIRM_PROP "persist.hsb.strip.confirm_px"
#define HSB_STRIP_RESET_PROP   "persist.hsb.strip.reset_px"
#define HSB_STRIP_MAXTAN_PROP  "persist.hsb.strip.max_tan"
/* 「还在动」的速度**窗长**（2026-10-14 夜，用户 11:20 日志逼出来的可调项）。
 *
 * 为什么必须能调、而且默认不能动：`:ui` 的速度窗用事件自带的 eventTime 算，native 只能用
 * "事件**到达**时刻" —— 而桌面拿到的是**积压回放**（真机实测 12 个事件挤在 57ms 内到达），
 * 同样一段位移在 native 眼里被压进了更短的 dt_win ⇒ 窗内平均速度被系统性**高估** ⇒
 * "还在动"被多刷新几次 ⇒ native 的 dwell 比 :ui 晚（11:20:49 的 `s#1`：native 确认在
 * 49.004，授权却拖到 50.750 = +1746ms，而 :ui 的截止点是 49.638 ⇒ 白丢一次单源）。
 * 窗长越长，暴涨的瞬时速度被摊平得越多（把窗从 150ms 拉到 ~300ms，慢速内滑的估值就能落回
 * 500px/s 以下），但太长会把"快滑"也误判成慢。
 * ⇒ 默认沿用已验证的 150ms（零行为变化），只把旋钮交出来：
 *     `setprop persist.hsb.strip.window_ms 300` 就能在不重编的情况下 A/B 这一条假设。 */
#define HSB_STRIP_WINDOW_PROP "persist.hsb.strip.window_ms"
/* 缺属性时的窗长（ms）：与 :ui 的 GestureThresholds.STALL_SPEED_WINDOW_MS 同值。 */
#define HSB_STRIP_SPEED_WINDOW_MS 150ull
#define HSB_STRIP_DWELL_FALLBACK_MS 250.0f
#define HSB_STRIP_SPEED_FALLBACK 500.0f
#define HSB_STRIP_CONFIRM_FALLBACK 41.0f    /* 15dp × 2.75（仅属性缺失时兜底） */
#define HSB_STRIP_RESET_FALLBACK 20.5f
#define HSB_STRIP_MAXTAN_FALLBACK 1.7321f   /* tan 60° */

/* 「抬手收箭头」（2026-10-14）：把 launcher 的手势状态字节从 started 打成 0，让它的终态支
 * 走"未启动收尾"（收箭头 + 收面板，**不可能提交 back**）。定义在委托门控区。 */
static int hsb_arrow_state_release(void);
static int hsb_arrowfix_value(void);
static void hsb_publish_evoke(const char *payload);   /* 中间态：native → :ui 授权发布 */
static void hsb_heartbeat_start(void);               /* 60s 心跳：补丁完整性巡检（定义体在文件后段） */
/* 本事件是"我们这条流的终态（UP/CANCEL）且已强制清状态" ⇒ 吞掉之后需要在 stub 里补一次
 * 原函数调用，让 launcher 自己的收尾支真的跑起来。 */
static int g_arrow_due;

/* 抑制档：0=只观测；1=带内起手立即吞（会连"带内快滑想返回"一起吞，不推荐）；
 * 2=**停顿成立后才吞**（生产档：快滑不受影响）。
 *
 * 2026-10-14 晚：属性缺失时的默认从 0 改成 **2**。理由同 HSB_ONLY_ANCHOR ——
 * `debug.hsb.native.suppress` 易失，重启即清；默认 0 会让"装上就能用"变成
 * "装上只能看日志"（真机上表现为：菜单能呼出、但停顿手势没被 native 接管）。
 * 显式 `setprop debug.hsb.native.suppress 0` 仍然是可靠的总退路。 */
static int g_suppress_value;
static uint64_t g_suppress_checked_s;

static int hsb_suppress_value(void) {
    uint64_t now = hsb_now_s();
    if (g_suppress_checked_s != now) {
        char v[8] = {0};
        g_suppress_checked_s = now;
        if (__system_property_get(HSB_SUPPRESS_PROP, v) > 0 && v[0]) {
            int n = atoi(v);
            g_suppress_value = (n == 1 || n == 2) ? n : 0;
        } else {
            g_suppress_value = 2;   /* 缺省=停顿吞（生产档），见上方注释 */
        }
    }
    return g_suppress_value;
}

/* 停顿阈值 / 速度门（无缓存：每条事件最多读一次，1µs 级；换来运行时可调） */
static float hsb_strip_dwell_ms(void) {
    return hsb_cfg_float(HSB_STRIP_DWELL_PROP, 50.0f, 2000.0f, HSB_STRIP_DWELL_FALLBACK_MS);
}
static float hsb_strip_stall_speed(void) {
    return hsb_cfg_float(HSB_STRIP_SPEED_PROP, 0.0f, 100000.0f, HSB_STRIP_SPEED_FALLBACK);
}
/* min 下限取到 0.5px 级：用户把"滑动距离"拉到最小时确认门就该跟着变窄 */
static float hsb_strip_confirm_px(void) {
    return hsb_cfg_float(HSB_STRIP_CONFIRM_PROP, 0.5f, 2000.0f, HSB_STRIP_CONFIRM_FALLBACK);
}
static float hsb_strip_reset_px(void) {
    return hsb_cfg_float(HSB_STRIP_RESET_PROP, 0.0f, 2000.0f, HSB_STRIP_RESET_FALLBACK);
}
static float hsb_strip_max_tan(void) {
    return hsb_cfg_float(HSB_STRIP_MAXTAN_PROP, 0.01f, 100.0f, HSB_STRIP_MAXTAN_FALLBACK);
}
/* 速度窗长（ms）：默认 = 已验证的 150ms（与 :ui 的 STALL_SPEED_WINDOW_MS 同值）；
 * 调大只影响 native 这一侧（:ui 仍按 eventTime 走自己的窗），用于验证"到达时刻压缩 ⇒ 速度高估"
 * 这条假设，见 HSB_STRIP_WINDOW_PROP 的注释。 */
static uint64_t hsb_strip_window_ms(void) {
    float v = hsb_cfg_float(HSB_STRIP_WINDOW_PROP, 50.0f, 2000.0f, (float) HSB_STRIP_SPEED_WINDOW_MS);
    return (uint64_t) v;
}


/* 侧滑触发带（与 :ui `SystemUiInputSpy.inTriggerBand` 同源、竖屏）：
 *   nearSide = x <= bandW || x >= screenW - bandW
 *   bandH    = screenH * bandHeightPercent / 100
 *   top      = (screenH - bandH) * bandPositionPercent / 100
 *   inBand   = nearSide && y ∈ [top, top + bandH]
 *
 * ⚠ 抑制点 `handle_back_gesture` 的 self **不是** GestureInputMonitorImpl（那是角区锚点的
 *   布局，w/h 在 +0x260/+0x244），读不到权威屏宽高 ⇒ 几何只能由 Java 侧算了写属性
 *   （`CornerGeomBridge.pushBand`，与 spy 同一份 prefs + 同一份 getSystem().displayMetrics）。
 *   也正因如此这里送的是 **px** 而不是比例：native 手里没有 w/h，比例换算无从谈起。
 *   竖屏判定同样交给属性（screenW < screenH），对齐 spy 横屏不转发。 */
/* ── 当前旋转下的屏尺寸：直接问 ROM（2026-10-18）──────────────────────────────
 * 为什么需要：侧滑触发带的 `handle_back_gesture` 其 self **不是** GestureInputMonitorImpl
 * ⇒ 拿不到权威屏宽高（那在 +0x260/+0x244，只有角区锚点的 self 有）；而 Java 侧推的方向位
 * 属性**只有模块 App 进程活着时才会重推**（转屏时它在才推，不在就没人推）⇒ 横屏左缘底部
 * 停顿那一类会漏判成竖屏、把竖屏带套到横屏坐标系上。⇒ 这里直接调 ROM 自己的
 * `display_api::get_current_rotate_screen_size`，拿到的就是当前旋转下的尺寸。
 *
 * 出参布局（设备 .so 反汇编实证，2026-10-18，见 gen_launcher_profiles.py 的 WANT 注释）：
 *   成功路径 `stp w8, w9, [x19]` + `str w21, [x19,#8]` ⇒ **三个 int32**；
 *   提前返回 `str xzr,[x19]` + `str wzr,[x19,#8]`     ⇒ 清零前 12B；
 *   ROM 自己的调用方（is_in_navigation_bar_hot_zone @0x6b8184）读的正是前两个当尺寸
 *   （`ldp w9,w8,[sp,#0x18]` → 两个 `scvtf`）⇒ 照抄：缓冲区 16B 全零，读 out[0]/out[1]。
 *   （10-17 曾据"提前返回写 8B+4B"判为布局未知而放弃——那条读法不完整。）
 *
 * 1 秒粒度缓存：与 hsb_band_geom 的刷新同频，这个判定跑在每个输入事件上，不能每次回调。 */
static void (*g_rotate_size_fn)(int *out);
static int g_rotate_size_giveup;     /* 解析失败过 ⇒ 不再每轮巡检重试（日志不刷屏） */
static int g_rot_w, g_rot_h;         /* 最近一次成功读到的旋转后尺寸 */
static uint64_t g_rot_at_s;          /* 读取时刻 */
static int g_rot_seen;               /* 是否至少成功过一次 */

static int hsb_live_screen_size(int *w, int *h) {
    if (!g_rotate_size_fn) return 0;
    uint64_t now = hsb_now_s();
    if (!g_rot_seen || now != g_rot_at_s) {
        int out[4] = {0, 0, 0, 0};
        g_rotate_size_fn(out);
        if (out[0] > 0 && out[1] > 0) { g_rot_w = out[0]; g_rot_h = out[1]; g_rot_seen = 1; }
        g_rot_at_s = now;
    }
    if (!g_rot_seen) return 0;
    if (w) *w = g_rot_w;
    if (h) *h = g_rot_h;
    return 1;
}

#define HSB_BAND_W_PX_PROP   "persist.hsb.band.w_px"
#define HSB_BAND_H_PX_PROP   "persist.hsb.band.h_px"
#define HSB_BAND_TOP_PX_PROP "persist.hsb.band.top_px"
#define HSB_BAND_SW_PX_PROP  "persist.hsb.band.screen_w_px"
#define HSB_BAND_SH_PX_PROP  "persist.hsb.band.screen_h_px"
/* 活体朝向位（0=竖屏 / 1=横屏，2026-10-13 新增）：上面两个屏尺寸字段按**竖屏归一**
 * （CornerGeomBridge.bandPx 取 min/max），所以下面那行 sw>=sh 的横屏判据**恒假**——
 * 横屏时本函数会照样返回 ok，竖屏带被套到横屏坐标系上（左/右边缘底部那段被误吞 + 误发
 * MAGIC）。朝向只有 Java 侧知道 ⇒ 由属性给真值；属性没送到或停在旧值时，靠
 * hsb_band_in() 的"坐标越界即拒"兜底（横屏下 x/y 会超出竖屏尺寸，只在横屏成立）。 */
#define HSB_BAND_LAND_PROP   "persist.hsb.band.landscape"

static float g_band_w_px, g_band_h_px, g_band_top_px, g_band_scr_w, g_band_scr_h;
static int g_band_ok;
static uint64_t g_band_checked_s;
/* 上一次**打点**的取值（只用于"变了才打"，见 hsb_band_geom） */
static float g_band_last_w, g_band_last_h, g_band_last_t, g_band_last_sw, g_band_last_sh;
static float g_band_last_land;

/* 刷新触发带几何（属性驱动，1 秒粒度缓存——这个判断在每个输入事件上都会跑）。
 * 返回 0 = 属性缺失/非法/横屏 ⇒ 抑制一律不做（fail-closed）。 */
static int hsb_band_geom(void) {
    uint64_t now = hsb_now_s();
    if (g_band_checked_s != now) {
        g_band_checked_s = now;
        float w  = hsb_cfg_float(HSB_BAND_W_PX_PROP,   1.0f,   2000.0f, 0.0f);
        float h  = hsb_cfg_float(HSB_BAND_H_PX_PROP,   1.0f, 100000.0f, 0.0f);
        /* top 的合法值含 0 ⇒ 用 dflt=-1 区分"属性缺失"与"top=0" */
        float t  = hsb_cfg_float(HSB_BAND_TOP_PX_PROP, 0.0f, 100000.0f, -1.0f);
        /* 当前旋转下的屏尺寸：**先问 ROM**（2026-10-18）。拿到就不再读属性——属性里的屏尺寸是
         * 竖屏归一值（CornerGeomBridge.bandPx 取 min/max），朝向只有 Java 侧知道且**它不在就
         * 没人重推**；ROM 这条路与模块 App 死活无关，横屏判据因此不再有陈旧窗口。
         * ROM 取不到（锚点没解析上 / 返回值非法）才回落到属性（既有行为，fail-closed 到属性）。 */
        int live_w = 0, live_h = 0;
        int have_live = hsb_live_screen_size(&live_w, &live_h);
        float sw = have_live ? (float) live_w
                             : hsb_cfg_float(HSB_BAND_SW_PX_PROP, 1.0f, 100000.0f, 0.0f);
        float sh = have_live ? (float) live_h
                             : hsb_cfg_float(HSB_BAND_SH_PX_PROP, 1.0f, 100000.0f, 0.0f);
        /* 方向位：有 ROM 实时尺寸 ⇒ 直接由它比出来（活值，永不过期）；否则按属性
         * （dflt=-1 区分"属性缺失"与"竖屏=0"），并保留那条恒假的几何比作最后兜底。 */
        float land = have_live ? (live_w >= live_h ? 1.0f : 0.0f)
                               : hsb_cfg_float(HSB_BAND_LAND_PROP, 0.0f, 1.0f, -1.0f);
        int landscape_geo = (!have_live && sw > 0.0f && sh > 0.0f && sw >= sh);
        int landscape_prop = (land >= 0.5f);
        int landscape = landscape_geo || landscape_prop;
        int ok = (w > 0.0f && h > 0.0f && t >= 0.0f && sw > w && sh > 0.0f && !landscape);
        g_band_w_px = w;
        g_band_h_px = h;
        g_band_top_px = t;
        g_band_scr_w = sw;
        g_band_scr_h = sh;
        /* 只在**取值或有效性发生变化**时打一行（原来是每次 1 秒刷新都打 ⇒ 密集手势下 1 行/秒刷屏）。
         * 首次必然打（初值全 0）⇒ "属性送到没送到、native 实读多少"这两件事始终可判读，
         * 这也是 pushBand 那条链的唯一验收依据。 */
        int changed = (ok != g_band_ok) || (w != g_band_last_w) || (h != g_band_last_h) ||
                      (t != g_band_last_t) || (sw != g_band_last_sw) || (sh != g_band_last_sh) ||
                      (land != g_band_last_land);
        g_band_ok = ok;
        if (changed) {
            g_band_last_w = w;
            g_band_last_h = h;
            g_band_last_t = t;
            g_band_last_sw = sw;
            g_band_last_sh = sh;
            g_band_last_land = land;
            /* 横屏时把"凭什么判横屏"也打出来：prop=1（活性位）还是 geometry（恒假的旧判据）。 */
            const char *why = !landscape ? ""
                            : (have_live ? " (landscape via ROM ⇒ no suppress)"
                                         : (landscape_prop ? " (landscape prop=1 ⇒ no suppress)"
                                                           : " (landscape via geometry ⇒ no suppress)"));
            LOGI("band geom %s w_px=%.1f h_px=%.1f top_px=%.1f screen=%.0fx%.0f land=%.0f%s "
                 "src=%s", ok ? "ok" : "INVALID", w, h, t, sw, sh, land, why,
                 have_live ? "ROM" : "prop");
        }
    }
    return g_band_ok;
}

/* 起手点是否落在触发带内（只在 hsb_band_geom() 为真时调用）。
 * 开头那条是**横屏兜底**（2026-10-13）：属性里的方向位可能停在旧值（只有模块 App 进程
 * 转屏时能重推，它不在就没人推），而横屏坐标会超出"竖屏归一"的屏尺寸——竖屏下 x 不可能
 * > 屏宽、y 不可能 > 屏高 ⇒ 越界即拒。竖屏恒不触发（零误杀）；漏网的是"横屏**左**缘底部
 * 静止停顿"那一窄类（它全程坐标都落在竖屏范围内），只能靠方向位属性挡。 */
static int hsb_band_in(float x, float y) {
    if (x > g_band_scr_w || y > g_band_scr_h) {
        LOGI("back SKIP(oob): raw=(%.1f,%.1f) 超出竖屏 scr=%.0fx%.0f ⇒ 疑横屏，拒判",
             x, y, g_band_scr_w, g_band_scr_h);
        return 0;
    }
    int near_side = (x <= g_band_w_px) || (x >= g_band_scr_w - g_band_w_px);
    int in_y = (y >= g_band_top_px) && (y <= g_band_top_px + g_band_h_px);
    return near_side && in_y;
}

/* 抑制链路的累计计数（报告与日志用；与角区计数同样"进程生命周期内累计、不复位"，
 * 所以 A/B 两档必须各自重启桌面后再读） */
static struct {
    uint32_t seen;        /* 动作是 DOWN 的次数 */
    uint32_t in_band;     /* 其中起手点在触发带内的次数（= would 的上界） */
    uint32_t confirmed;   /* 其中过了"距离+角度"确认门的次数（对齐 :ui 的 swipe confirmed） */
    uint32_t reset;       /* 确认后又滑回条、整条重置的次数（对齐 :ui 的 RESET slide-back） */
    uint32_t would;       /* 档=0：判定"带内起手"的次数（只观测） */
    uint32_t dwelled;     /* 档=2：判定"停顿成立"的次数（= 真正转为吞的手势数） */
    uint32_t swallow;     /* 真吞的手势数 */
    uint32_t swallow_ev;  /* 真吞的事件数 */
    uint32_t cancel_pass; /* 吞的过程中**放行**的 CANCEL 数（清理用，见下） */
    uint32_t arrow_fix;   /* 抬手/取消前把 launcher 状态字节 1/2 → 0 的次数（arrowfix=1） */
    uint32_t arrow_clean; /* 吞掉终态后**替 launcher 补调一次**原函数（走它的 state!=1 收尾）的次数 */
    uint32_t geom_bad;    /* 抑制判定没能进行的 DOWN 数（带几何不可用 ⇒ fail-closed） */
} g_supp;

/* 一条手势的状态（身份 = downTime + deviceId）：
 *   candidate（档=2）：带内起手、尚未停顿 ⇒ **事件照常转发**（Xiaomi 的 back 预览不受影响）
 *   active：条件已达成 ⇒ 后续事件不转发，直到终态
 * 速度窗字段用于判"还在动"：单帧瞬时速度被持指抖动主导（20px/采样 @120Hz ≈ 2500px/s），
 * 必须取窗口均值才分得出"真在滑"与"停住抖"——窗长定义见 HSB_STRIP_WINDOW_PROP（可属性覆盖）。 */
static struct {
    int candidate;
    int confirmed;           /* 已过"距离+角度"确认门（对齐 :ui 的 swipe confirmed） */
    int dwell_done;          /* 本手势的"停顿"只处理一次（否则后续每个事件都会重复判/重复调） */
    int active;
    long long dt;
    int dev;
    float down_x, down_y;    /* DOWN 起手点（算 inward / travel / 夹角） */
    float in_ux;             /* 内滑轴（竖屏条=水平：左边缘 +1 / 右边缘 -1；in_uy 恒 0） */
    uint64_t last_move_ms;   /* 最近一次判定"还在动"的时刻 */
    uint64_t win_ms;         /* 速度窗起点时刻 */
    float win_x, win_y;      /* 速度窗起点坐标 */
} g_supp_stream;

/* （2026-10-13 删除）这里原有一个「委托给侧栏」门控的待命状态 g_delegate_arm：主锚点判出
 * 带内停顿后置位，供第二锚点 try_delegate_back_to_sidebar 的桩 flip 返回值。真机证明
 * "强制委托"对本机无效（官方停顿呼出未启用，见 plan §14/§17），已整段删除。 */

/* 整条重置（对齐 :ui 的 resetGesture：滑回条、或换手势时清干净） */
static void hsb_supp_frame_reset(void) {
    g_supp_stream.candidate = 0;
    g_supp_stream.confirmed = 0;
    g_supp_stream.dwell_done = 0;
}

/* 从这一刻起吞这条手势的后续事件（含当前这条）。返回被吞手势的序号（日志用）。 */
static uint32_t hsb_supp_engage(void) {
    g_supp_stream.candidate = 0;
    g_supp_stream.confirmed = 0;
    g_supp_stream.active = 1;
    g_supp.swallow_ev++;
    return __atomic_add_fetch(&g_supp.swallow, 1u, __ATOMIC_RELAXED);
}

/* 返回 1 = 本事件不转发给原函数（"吞"）。
 *
 * 档=1：带内起手的 DOWN 当场吞（上一版行为；已知会把"带内快滑想返回"一起吞掉）。
 * 档=2（推荐）：带内起手先只是**候选**——事件照常转发（back 预览照走），只有这条手势
 *   **真的停顿**（连续 ≥ dwell 没有"还在动"）时才转为吞。
 *   ⇒ 快滑永不停顿 ⇒ 永不吞 ⇒ 带内快滑的返回不受影响（这就是"延迟/放行"）；
 *     停顿呼出 ⇒ 转为吞 ⇒ UP 不达原函数 ⇒ 不提交 back。
 *   判据与 :ui 的 STALL 定时器同构（速度窗 + 阈值），阈值/窗口都跟 :ui 同源（属性推过来）。 */
static int hsb_suppress_swallow(const HsbBackEv *e, uint64_t now_ms) {
    /* ===== 已在吞：一律吞到终态，**唯一例外是 CANCEL** =====
     * CANCEL 是输入系统给的"清理"信号（pilfer 后原目标会收到）。放行它只会让 Rust 侧把
     * 手势收干净，**不可能产生 back**（back 由 UP 提交）；档=2 时 DOWN/MOVE 是转发过的，
     * 把 CANCEL 也吞掉反而会让那条状态机悬着。 */
    if (g_supp_stream.active) {
        if (e->dt == g_supp_stream.dt && e->dev == g_supp_stream.dev) {
            if (e->act == HSB_ACT_CANCEL) {
                g_supp_stream.active = 0;
                /* CANCEL 是输入系统给的"清理"信号：先按 arrowfix 把 launcher 的手势态打回
                 * "未启动"，它收到 CANCEL 时才会走 state!=1 的收尾支（否则只清状态、箭头留着）。
                 * CANCEL 本身是放行的（return 0）⇒ 原函数自己会跑那支收尾。 */
                hsb_arrow_state_release();
                g_supp.cancel_pass++;
                return 0;
            }
            if (e->act == HSB_ACT_UP) {
                /* 先按 arrowfix 把状态字节从 started 打成 0：这样原函数的 UP 支会走
                 * `state != 1` 的收尾（收箭头 + 收面板 + **不可能提交 back**，见文件末尾证据）。 */
                int fixed = hsb_arrow_state_release();
                g_supp_stream.active = 0;
                /* ⚠ 这一行是 arrowfix 的另一半，2026-10-13 清理 recover 档时误删过一次
                 *（症状：FIX 照打、CLEANUP 永不出现 ⇒ 箭头残留）：UP 被我们吞掉，launcher
                 * 自己那次"收尾调用"根本不会发生，必须由 stub 的吞分支补调一次原函数。
                 * recover=1（UP 放行）已证明对箭头无效，那档已删。 */
                if (fixed) g_arrow_due = 1;
            }
            g_supp.swallow_ev++;
            return 1;
        }
        g_supp_stream.active = 0;   /* 换了手势（上一条终态没经过我们）：清掉重判 */
    }

    /* ===== 候选期（档=2）：事件照常转发；先过"距离+角度"确认门，再判停顿 =====
     * 与 :ui 的 `handleStripGestureCore` 逐步对齐：
     *   未确认 → 只找确认点（travel ≥ confirm 且 inward > 0 且夹角 ≤ max）；
     *            **确认那一刻**才起速度窗/计时（对齐"确认即挂停顿定时器"）；
     *   已确认 → 滑回条整条重置；否则速度窗内超速=还在动，重置计时；
     *            连续 ≥ dwell 没在动 ⇒ 停顿成立 ⇒ 转吞。
     * 未过确认门就什么都不做——这正是 OS2/3 的语义（距离不到位不可能呼出），
     * 也顺手消掉"边缘带内长按被吞"（长按没有位移，永远过不了确认门）。 */
    if (g_supp_stream.candidate && e->dt == g_supp_stream.dt && e->dev == g_supp_stream.dev) {
        float dx = e->x - g_supp_stream.down_x;
        float dy = e->y - g_supp_stream.down_y;
        float inward = dx * g_supp_stream.in_ux;      /* in_uy 恒 0（竖屏条=水平轴） */
        float perp = (dy < 0.0f) ? -dy : dy;          /* = |dx*0 - dy*in_ux| */
        if (g_supp_stream.confirmed && inward < hsb_strip_reset_px()) {
            __atomic_add_fetch(&g_supp.reset, 1u, __ATOMIC_RELAXED);
            hsb_supp_frame_reset();
            return 0;
        }
        if (!g_supp_stream.confirmed) {
            float travel2 = dx * dx + dy * dy;
            float c = hsb_strip_confirm_px();
            /* 夹角比较用 tan：perp <= inward·tan(max)，省 atan2（inward>0 才可能成立） */
            if (inward > 0.0f && travel2 >= c * c && perp <= inward * hsb_strip_max_tan()) {
                g_supp_stream.confirmed = 1;
                __atomic_add_fetch(&g_supp.confirmed, 1u, __ATOMIC_RELAXED);
                g_supp_stream.last_move_ms = now_ms;   /* 确认即起计时（对齐 :ui） */
                g_supp_stream.win_ms = now_ms;
                g_supp_stream.win_x = e->x;
                g_supp_stream.win_y = e->y;
            }
            return 0;   /* 未确认：什么都不做（没过确认门不可能呼出） */
        }
        uint64_t dt_win = now_ms - g_supp_stream.win_ms;
        if (dt_win >= hsb_strip_window_ms()) {
            float wx = e->x - g_supp_stream.win_x;
            float wy = e->y - g_supp_stream.win_y;
            float sp = hsb_strip_stall_speed();
            /* 比较平方，省一次 sqrtf（两者都非负） */
            float v2 = (wx * wx + wy * wy) * 1000000.0f / ((float) dt_win * (float) dt_win);
            if (v2 > sp * sp) g_supp_stream.last_move_ms = now_ms;
            g_supp_stream.win_ms = now_ms;
            g_supp_stream.win_x = e->x;
            g_supp_stream.win_y = e->y;
        }
        if (!g_supp_stream.dwell_done &&
            now_ms - g_supp_stream.last_move_ms >= (uint64_t) hsb_strip_dwell_ms()) {
            g_supp_stream.dwell_done = 1;   /* 一条手势只处理一次 */
            uint32_t seq = __atomic_add_fetch(&g_supp.dwelled, 1u, __ATOMIC_RELAXED);
            /* 中间态（2026-10-13，用户 m03327）：把「native 已判定停顿」发布出去，
             * :ui 侧 strict=1 时只认这条授权才呼出。每手势一次 ⇒ 不进热路径 I/O。 */
            {
                char payload[64];
                snprintf(payload, sizeof(payload), "%u:%lld:%.1f:%.1f",
                         (unsigned) seq, e->dt, e->x, e->y);
                hsb_publish_evoke(payload);
            }
            /* 停顿成立 ⇒ 转"吞这条流"（生产档 suppress=2）。呼出：strict=0（默认）仍归
             * :ui 自己的 dwell 定时器；strict=1 时 :ui 只认上面那次发布。 */
            if (hsb_suppress_value() >= 1) {
                uint32_t n = hsb_supp_engage();
                LOGI("back SUPPRESS(dwell) raw=(%.1f,%.1f) 停顿≥%.0fms #%u act=%d dt=%lld dev=%d",
                     e->x, e->y, hsb_strip_dwell_ms(), n, e->act, e->dt, e->dev);
                return 1;
            }
        }
        return 0;   /* 未停顿 ⇒ **放行**（正常 back 预览照走） */
    }
    hsb_supp_frame_reset();

    if (e->act != HSB_ACT_DOWN) return 0;
    g_supp.seen++;
    if (!hsb_band_geom()) {
        g_supp.geom_bad++;
        return 0;
    }
    if (!hsb_band_in(e->x, e->y)) return 0;
    g_supp.in_band++;

    /*
     * 「我看到了这条流」握手（seq=0）——**必须与抑制档无关**（2026-10-21 用户口径）。
     *
     * 语义（这是 :ui 呼出门的唯一判据）：能走到 `handle_back_gesture` 里、且落在触发带内
     * ⇒ **系统自己的返回手势链路已经认领了这条流**。:ui 侧据此才允许呼出扇形；
     * 系统没当返回手势的流（状态栏高度以内起手 / IME / 手势排除区 / 锁屏…）这里根本不经过
     * ⇒ 不发布 ⇒ 不呼出。这就是「没有触发系统返回手势就不许呼出」的落点。
     *
     * 为什么要与抑制档解耦：抑制档（`debug.hsb.native.suppress`）只决定"吞不吞"，
     * 而呼出判据不该随诊断档变化——否则 `suppress=0`（纯观测）会让呼出静默失效。
     *
     * payload 首段用 **seq=0**（判定成立的授权用 seq≥1），走同一条通道、同一个属性；
     * 每条带内手势只写一次（不是每事件）。
     *
     * 时序依据（真机 10:54:52.916 / 10:54:53.830，逐条时间戳见 plan §26.7）：
     *   spy（:ui 视角）DOWN t=52.916 → :ui 确认 ≈53.147；而 native 的**首个**事件
     *   `back_event #1 act=0 dt=34728177` 落在 **53.830**（晚 914ms）——桌面手势窗口开始
     *   收这条流本身就晚（拿到的是积压事件）。实测入场滞后 290~914ms ⇒ :ui 侧任何**固定**
     *   宽限都追不上 ⇒ 改成事件驱动的握手（:ui 侧 `pollNativeAuth` 按 downTime 匹配，
     *   见 TurboLayout.SYS_GATE_WAIT_CAP_MS）。
     */
    {
        char payload[64];
        snprintf(payload, sizeof(payload), "0:%lld:%.1f:%.1f", e->dt, e->x, e->y);
        hsb_publish_evoke(payload);
    }

    int mode = hsb_suppress_value();
    if (mode <= 0) {
        /* 观测档：零行为变化。每条带内起手都打一行（每手势最多一行，便于逐条对账）。 */
        __atomic_add_fetch(&g_supp.would, 1u, __ATOMIC_RELAXED);
        LOGI("back WOULD-SUPPRESS raw=(%.1f,%.1f) band w=%.1f h=%.1f top=%.1f scr=%.0f dt=%lld dev=%d",
             e->x, e->y, g_band_w_px, g_band_h_px, g_band_top_px, g_band_scr_w, e->dt, e->dev);
        return 0;
    }
    if (mode == 1) {
        uint32_t n = hsb_supp_engage();
        LOGI("back SUPPRESS(now) raw=(%.1f,%.1f) 带内起手立即吞 #%u dt=%lld dev=%d",
             e->x, e->y, n, e->dt, e->dev);
        return 1;
    }
    /* mode==2（生产档）：先走"候选 → 确认门 → 停顿"，只有真停顿才转为吞。 */
    g_supp_stream.candidate = 1;
    g_supp_stream.confirmed = 0;
    g_supp_stream.dt = e->dt;
    g_supp_stream.dev = e->dev;
    g_supp_stream.down_x = e->x;
    g_supp_stream.down_y = e->y;
    /* 内滑轴：与 :ui 的 OS4 竖屏条一致 —— 起手在屏幕左半 ⇒ 向内=+x，否则 -x */
    g_supp_stream.in_ux = (e->x < g_band_scr_w * 0.5f) ? 1.0f : -1.0f;
    /* 「系统已认领这条流」的 seq=0 握手在带内判定后**无条件**发布（见上方），
     * 这里不再重复发——同一手势只写一次属性。 */
    return 0;
}


/* ===== 「主动取消（收尾）」观测：handle_back_gesture 侧的上下文快照（2026-10-12）=====
 * 现在的抑制是"吞事件"——实测只做到"不提交"，**收不了尾**（launcher 的手势悬着 → 返回箭头
 * 残留）。OS2/3 的正解是**让 launcher 走它自己的取消分支**（合成 UP + 翻转 shouldBack）。
 * OS4 上这件事只能由 native 做，落点就是 on_back_cancelled；但它的参数表未验 ⇒ 先只观测。
 * 这里存主锚点侧的身份，供观测桩对账三件事：x0 是 self 还是 self+0xb8（参考实现报告写的
 * this+0xb8 = GestureInputBackHelper）、是不是同一条手势、距上一事件多久。 */
static struct {
    uintptr_t self;      /* handle_back_gesture 的 self（GestureStubViewWindow） */
    uintptr_t window;    /* 同上，但**无条件**记录（arrowfix 要用它改状态字节，不能依赖观测档） */
    long long dt;        /* 该手势 downTime */
    int dev;             /* deviceId */
    uint64_t last_ms;    /* 该手势最近一次事件时刻 */
} g_back_ctx;



/* ===== 「抬手收箭头」修复（2026-10-14，arrowfix）=====
 * 真机症状：停顿手势 → 箭头出现、扇形出现 → 松手 → 扇形收、**返回箭头残留**（稳定复现），
 * 且 `cancel=0/2` × `recover=0/1` 四个组合**都无差异**。
 *
 * 反汇编定案（设备 libapp_launcher.so；GestureStubViewWindow::handle_back_gesture = lib+0x6fd3ac）：
 * 手势状态字节 = self+0xf0（0 idle / 2 armed(DOWN 之后) / 1 started / 4 sidebar 支），
 * UP 与 CANCEL 共用一块：
 *   0x6fd580: ldrb w8,[x19,#0xf0]; cmp w8,#1; b.ne 0x6fd5c4
 *     ├ state==1 → 0x6fd7a0：w21 = (|Δx| > 55dp) || (速度 > 2.0 px/ms)
 *     │     · 模式标志(self+0xf1) bit0==0 → 0x6fd99c：w21==0 ⇒ **跳 0x6fe624 只把 state 清零**
 *     │       （箭头/面板/动画一个都不收；我们的停顿手势位移小、速度低 ⇒ w21 恒 0）
 *     │     · 其它 → 0x6fd800（sidebar 支）/ on_swipe_stop_direct（提交支）
 *     └ state!=1 → 0x6fd5c4 → 0x6fd688：
 *          `cancel_back_panel_arrow_scenario([x20+0x78])`                ← **收箭头①**
 *          … `GestureBackArrowView::on_swipe_stop([x20+0x58]+0x18, 0)`   ← **收箭头②**
 *          然后直奔 0x6fe61c/0x6fe624（0x6fe614 的 delegate 分支在此**之前**）
 *          ⇒ 既收干净，又**完全不可能提交 back**。
 * 结论：残根不在"谁拥有 UP"，而在**抬手那一刻的 state 值** —— 我们要的就是那条 state!=1 收尾支。
 *
 * 本档做的事（极小、默认开、可关、写前判值）：
 *   抬手/取消前，若 `[self+0xf0] ∈ {1,2}` 就写成 0（fail-closed：其它值一律不碰），
 *   于是原函数的终态支落到 0x6fd5c4 收尾支：
 *     · recover=1（UP 放行）→ 原函数自己就跑收尾，不需要额外调用；
 *     · recover=0（UP 被我们吞掉）→ stub 里补调一次原函数（`g_arrow_due`），收尾照跑。
 * `debug.hsb.native.arrowfix`：1=开（默认）；0=关（只观测、不写、不补调）。 */
#define HSB_ARROWFIX_PROP   "debug.hsb.native.arrowfix"
#define HSB_WIN_STATE_OFF   0xf0ull   /* GestureStubViewWindow 手势状态字节（= helper+0x30） */
#define HSB_WIN_STATE_IDLE  0u
#define HSB_WIN_STATE_START 1u        /* started：箭头已亮，而终态支"只清状态"的那一档 */
#define HSB_WIN_STATE_ARMED 2u        /* DOWN 之后：还没 start */

static int g_arrowfix_value;
static uint64_t g_arrowfix_checked_s;

static int hsb_arrowfix_value(void) {
    uint64_t now = hsb_now_s();
    if (g_arrowfix_checked_s != now) {
        char v[8] = {0};
        g_arrowfix_checked_s = now;
        /* 默认开（属性缺失/非 "0" 都算开）；`setprop … 0` 一键回退成旧行为 */
        g_arrowfix_value = (__system_property_get(HSB_ARROWFIX_PROP, v) > 0 && v[0] == '0') ? 0 : 1;
    }
    return g_arrowfix_value;
}

/* 抬手/取消前把 launcher 的手势状态打回"未启动"。返回 1 = 真改了。 */
static int hsb_arrow_state_release(void) {
    if (!hsb_arrowfix_value()) return 0;
    uintptr_t w = g_back_ctx.window;
    if (!w) return 0;
    volatile uint8_t *p = (volatile uint8_t *) (w + HSB_WIN_STATE_OFF);
    uint8_t cur = *p;
    if (cur != (uint8_t) HSB_WIN_STATE_START && cur != (uint8_t) HSB_WIN_STATE_ARMED) {
        return 0;   /* idle / 其它：没有箭头悬着，别乱写（fail-closed） */
    }
    *p = (uint8_t) HSB_WIN_STATE_IDLE;
    uint32_t n = __atomic_add_fetch(&g_supp.arrow_fix, 1u, __ATOMIC_RELAXED);
    LOGI("arrow FIX #%u state[%p]=%u→0（抬手前强制走 launcher 自己的 state!=1 收尾："
         "收箭头 + 收面板，不提交 back）", n, (void *) (w + HSB_WIN_STATE_OFF), (unsigned) cur);
    return 1;
}



static uintptr_t hsb_stub(uintptr_t a0, uintptr_t a1, uintptr_t a2, uintptr_t a3,
                          uintptr_t a4, uintptr_t a5, uintptr_t a6, uintptr_t a7,
                          double d0, double d1, double d2, double d3,
                          double d4, double d5, double d6, double d7) {
    (void) a2; (void) a3; (void) a4; (void) a5; (void) a6; (void) a7;
    (void) d0; (void) d1; (void) d2; (void) d3;
    (void) d4; (void) d5; (void) d6; (void) d7;
    uint32_t n = __atomic_add_fetch(&g_hook.hits, 1u, __ATOMIC_RELAXED);
    /* soak 判据"30 分钟后还活着、还在响"**按时间**成立，不按次数：
     * 次数阈值（原来的 16/32/64 对数里程碑）把两件事混成一件——"hook 没被打回文件映射"
     * 和"用户正好做了 16 次手势"。低频使用下 30 分钟可能只有 3 次命中，阈值永远等不到，
     * 于是"没日志"会被误读成"hook 掉了"。现在：任何一次命中，只要距上次出声 ≥60s，
     * 就打一行带"安装后秒数"的心跳——30 分钟后第一次手势即证据。
     * g_hook.installed_at_s 是"安装→此刻"的固定基准；空闲时长由 logcat 时间戳给出。 */
    const char *anchor = g_hook.anchor[0] ? g_hook.anchor : "?";
    uint64_t now = hsb_now_s();
    uint64_t last = g_hook.last_log_s ? g_hook.last_log_s : g_hook.installed_at_s;
    int quiet = (now >= last + HSB_HEARTBEAT_S);
    int first = __atomic_fetch_add(&g_stub_logged, 1u, __ATOMIC_RELAXED) < hsb_log_limit();
    /* ===== 侧滑线 S3：抑制必须赶在调 g_orig **之前**决定 =====
     * "不转发"就是抑制的全部内容 ⇒ 必须先读 event 权威坐标（getter 是纯函数，读在转发
     * 前后等价，所以观测档的行为与从前逐位一致）。 */
    HsbBackEv bev;
    int bev_ok = g_hook.has_back_event && hsb_back_event_read(a1, &bev);
    if (bev_ok) {
        /* 供 arrowfix 记账（窗身份 + 时序）；都是纯 getter / 一次读。 */
        g_back_ctx.dt = bev.dt;
        g_back_ctx.dev = bev.dev;
        g_back_ctx.last_ms = hsb_now_ms();
        g_back_ctx.window = a0;   /* arrowfix 靠它取 launcher 的状态字节 */
    }
    g_arrow_due = 0;
    if (bev_ok && hsb_suppress_swallow(&bev, hsb_now_ms())) {
        /* 吞掉的若是"带内停顿"这条流的终态 UP、且已把 launcher 的状态字节打回未启动（arrowfix），
         * 就补调一次原函数 ⇒ launcher 自己跑 state!=1 的收尾支（收箭头 + 收面板）。
         * 该支既不调 delegate 也不调 invoke/cancel ⇒ **不可能提交 back**（反汇编见 arrowfix 段）。 */
        if (g_arrow_due && g_orig) {
            g_arrow_due = 0;
            __atomic_add_fetch(&g_supp.arrow_clean, 1u, __ATOMIC_RELAXED);
            LOGI("arrow CLEANUP：吞掉终态后补调一次原函数跑收尾（self=%p，只为收箭头）",
                 (void *) a0);
            g_orig(a0, a1, a2, a3, a4, a5, a6, a7, d0, d1, d2, d3, d4, d5, d6, d7);
        }
        return 0u;   /* 本事件不转发：Rust 侧看不到 ⇒ 不认领、不起 back */
    }
    /* 先接住返回值再打日志（原来是尾部调用，返回值直接透传、打不到）。
     * 只给 has_ret 的点打——见 hsb_anchor_has_ret：其余锚点返回值无门控意义
     * （init_gesture_type 甚至被调用方整段丢弃，判定写进对象字段）。 */
    uintptr_t rv = g_orig
                       ? g_orig(a0, a1, a2, a3, a4, a5, a6, a7,
                                d0, d1, d2, d3, d4, d5, d6, d7)
                       : 0u;
    /* ===== 双锚点方案的观测桩：init_gesture_type 在 DOWN 拍锁存角区 =====
     * 与返回值无关、无条件跑。锁存的 (x,y) 供第二锚点 pilfer_pointers 判断本手势是否角区。
     * 这是"左中右 xy 差不多"的根治：锁的是 DOWN 锚点，不是每个事件的 live 点。 */
    if (g_hook.has_latch) {
        hsb_latch_down(a0, a1, d0, d1);
    }
    /* 侧滑线确认层①：handle_back_gesture 读 event 权威坐标（观察档）。
     * 抑制命中时上面已 return，不会走到这里。 */
    if (bev_ok) {
        hsb_back_event_log(&bev);
    }
    /* ===== 3.2 角区门控 =====
     * 只在 rv==1（系统已判底部区）时才跑判定——其余情况 hsb_corner_gate 第一行就返回 0，
     * 提前跳过省一次属性查询和两次内存读。
     * corner=0：命中只打 WOULD-HIT，rv 不动。目的是拿判定分布、校准阈值。
     * corner=1：命中即把 rv 强改 0，让 on_input_event 认为"不在底部区"
     *   ⇒ 期望系统走它自己的"区外重定向"路径，不认领、不 pilfer，流留给 spy。
     * 只打命中行 + 少量采样行、不打 pass 行：这个 hook 跑在每个输入事件上，pass 行会把
     * logcat 冲爆（采样规则见下方）。 */
    int gate_hit = 0;
    if (g_hook.has_gate) {
        uint32_t nfire = __atomic_add_fetch(&g_cs.fires, 1u, __ATOMIC_RELAXED);
        /* 几何刷新**无条件**跑（原来只在 rv==1 里 ⇒ rv 恒 0 时连属性写没写进去都看不到）。 */
        int dims_ok = hsb_corner_geom(a0, HSB_DIMS_W_IMPL, HSB_DIMS_H_IMPL);
        if (rv != 1u) {
            /* ⚠ 这一支是"读数稳定性"的关键（2026-10-11 实机教训）：实测整轮 rv 恒 0，
             * 而此前所有诊断（geom / MISS / SKIP）都挂在 rv==1 里面 ⇒ 一行证据都没有，
             * 只剩 `fires=N eval=0`，连"系统到底判了什么值"都读不出来。
             * 现在 rv 本身无条件出读数：第 1 次 + 每 50 次（一次手势约 5~15 次调用，
             * 够看分布又不冲爆 logcat）。 */
            uint32_t ns = __atomic_add_fetch(&g_rv_logged, 1u, __ATOMIC_RELAXED);
            if (ns == 1u || ns % 50u == 0u) {
                char sfx[128];
                hsb_cs_suffix(sfx, sizeof(sfx));
                LOGI("corner rv x=%.1f y=%.1f rv=0x%lx dims=%d fire#%u%s",
                     hsb_lo32_float(d0), hsb_lo32_float(d1),
                     (unsigned long) rv, dims_ok, nfire, sfx);
            }
        }
        if (rv == 1u) {
            int corner_on = hsb_corner_enabled();
            int why = 0;
            /* 2026-10-11 双锚点方案：本锚点（is_in_bottom_gesture_region）**降级为纯观测**——
             * force 恒 0，不再强改 rv（旧 FORCE 污染共享字段 self+0xf9，且要 rv==1 才动）。
             * 动作改到 pilfer_pointers（见 hsb_stub_pilfer）。corner_on 仅作日志标签。 */
            gate_hit = hsb_corner_gate(a0, d0, d1, rv, 0, &rv, &why);
            /* 命中必打；另外"第 1 次 eval"与"每 25 次 eval"各补一条采样——
             * 前者的用途：一轮手势常常只有个位数次 eval（19:15 那轮 eval=7），
             * 只按 25 采样等于一行证据都拿不到，而"为什么没命中"恰恰要靠这些行。
             * 采样行**不套用命中行的标签**：没命中就写 MISS，免得"缺行=真没命中"
             * 这条推断被采样行证伪。 */
            uint32_t ev = __atomic_load_n(&g_cs.eval, __ATOMIC_RELAXED);
            if (gate_hit || ev <= 1u || ev % 25u == 0u) {
                char sfx[128];
                hsb_cs_suffix(sfx, sizeof(sfx));
                float lx = hsb_lo32_float(d0);
                float ly = hsb_lo32_float(d1);
                if (gate_hit) {
                    LOGI("corner gate WOULD-HIT x=%.1f y=%.1f (observe-only, rv unchanged)%s",
                         lx, ly, sfx);
                } else if (why == 2) {
                    /* 横屏跳过：不写 MISS——写 MISS 会被读成"竖屏角滑没命中"，
                     * 而这一档根本没判（:ui 横屏不接管，这是刻意的对齐）。 */
                    LOGI("corner gate SKIP x=%.1f y=%.1f (landscape w>=h)%s", lx, ly, sfx);
                } else if (why == 3) {
                    LOGI("corner gate BADDIMS x=%.1f y=%.1f (w/h unreadable)%s", lx, ly, sfx);
                } else {
                    LOGI("corner gate MISS x=%.1f y=%.1f corner=%d%s", lx, ly, corner_on, sfx);
                }
            }
        }
    }
    if (first || quiet) {
        char detail[192];
        if (g_hook.has_args4) {
            /* 3.1 签名验证：全 4 个整型参数。a2/a3 是不是屏幕坐标，对着同手势的
             * is_in_bottom_gesture_region 读数一比就知道。 */
            snprintf(detail, sizeof(detail), "a0=%p a1=%p a2=%p a3=%p",
                     (void *) a0, (void *) a1, (void *) a2, (void *) a3);
        } else if (g_hook.has_xy) {
            snprintf(detail, sizeof(detail), "x=%.1f y=%.1f",
                     hsb_lo32_float(d0), hsb_lo32_float(d1));
        } else {
            snprintf(detail, sizeof(detail), "a0=%p a1=%p", (void *) a0, (void *) a1);
        }
        if (g_hook.has_ret) {
            char t[64];
            snprintf(t, sizeof(t), " ret=0x%lx", (unsigned long) rv);
            strncat(detail, t, sizeof(detail) - strlen(detail) - 1);
        }
        if (g_hook.has_dims) {
            /* self+0x244 / +0x260 两个 int：is_point_out_of_screen 的用法是与 x/y 比较的
             * 屏幕尺寸。用显式偏移而不是数组下标——曾经把 (0x260-0x244)/4 算成 13
             * （实际 7），野出一整个字段。读之前先验值域，野指针会让热路径直接崩桌面。 */
            char t[80];
            const volatile int *base = (const volatile int *) a0;
            int h = base[0x244 / 4];   /* 与 y(s1) 比 ⇒ 高 */
            int w = base[0x260 / 4];   /* 与 x(s0) 比 ⇒ 宽 */
            if (w > 0 && w < 100000 && h > 0 && h < 100000) {
                snprintf(t, sizeof(t), " w@0x260=%d h@0x244=%d", w, h);
            } else {
                snprintf(t, sizeof(t), " dims? w@0x260=%d h@0x244=%d", w, h);
            }
            strncat(detail, t, sizeof(detail) - strlen(detail) - 1);
        }
        if (g_hook.has_gate) {
            /* 角区门控锚点：HIT/ALIVE 行带累计计数 + 存活时长 + 坐标 + 屏幕尺寸。
             *
             * ⚠ 坐标**必须留在这条行上**（2026-10-11 实机教训）：我曾以为"x/y 已由
             * FORCE/WOULD-HIT/MISS 行单独打了"，就把 detail 丢了——结果只要 `rv` 一次都
             * 不为 1，gate 行一条都不会打（它整个在 `rv==1` 分支里），而那轮的 HIT 行里
             * 只剩 `fires=N eval=0`，**连"系统到底判了什么"都看不出来**。
             * 这条行受 loglimit 限制但必打（first/quiet），是 rv 恒 0 时唯一证据；
             * 它也是 hsb_corner_geom 唯一会被调用的路径——几何有没有生效同样只能看这里。 */
            char sfx[128];
            hsb_cs_suffix(sfx, sizeof(sfx));
            LOGI("%s %s #%u %s since_install=%llus%s %s", first ? "hook HIT" : "hook ALIVE",
                 anchor, n, detail, (unsigned long long) (now - g_hook.installed_at_s),
                 quiet ? " (quiet)" : "", sfx);
        } else {
            /* 侧滑线（has_back_event）多带一段抑制读数：否则"抑制没生效"与"根本没走抑制
             * 分支"在日志里长得一样（与角区 fires/eval 同款可观测性教训）。 */
            char sup[224] = "";
            if (g_hook.has_back_event) {
                snprintf(sup, sizeof(sup),
                         " [supp on=%d seen=%u inband=%u cf=%u rst=%u dw=%u would=%u sw=%u ev=%u cp=%u geomx=%u af=%u ac=%u]",
                         hsb_suppress_value(), g_supp.seen, g_supp.in_band, g_supp.confirmed,
                         g_supp.reset, g_supp.dwelled, g_supp.would, g_supp.swallow,
                         g_supp.swallow_ev, g_supp.cancel_pass, g_supp.geom_bad,
                         g_supp.arrow_fix, g_supp.arrow_clean);
            }
            LOGI("%s %s #%u %s%s d0=%016llx d1=%016llx since_install=%llus%s",
                 first ? "hook HIT" : "hook ALIVE", anchor, n, detail, sup,
                 (unsigned long long) hsb_bits64(d0),
                 (unsigned long long) hsb_bits64(d1),
                 (unsigned long long) (now - g_hook.installed_at_s),
                 quiet ? " (quiet)" : "");
        }
        g_hook.last_log_s = now;
    }
    return rv;
}

/* ===== 第二锚点桩：pilfer_pointers（动作位，2026-10-11 双锚点方案）=====
 * 只吃 self（InputMonitor），没有返回值语义。角区锁存成立时**不调用原函数**
 * ⇒ 系统不执行 InputMonitor.pilferPointers() ⇒ 不抢流 ⇒ :ui 拿全流。
 * 非角区透传，正常 Home 不受影响。
 *
 * ⚠ 2026-10-11 真机四轮结论：**系统对底角斜滑从不调本函数**（`pblk` 恒 0、无任何
 *   pilfer 行）⇒ 本动作位对角滑**无效**，已降级为默认关（`debug.hsb.native.corner2=0`）。
 *   角滑实为**底部手势**（慢/小→最近任务、大→Home，系统用停顿区分）。保留本代码作
 *   观测/存档；底角恢复路径见 `docs/adaptation/os4-native-session-*.md` 与计划文件。
 *   侧滑停顿线（`GestureInputBackHelper` 家族）才是主线。 */
static uintptr_t hsb_stub_pilfer(uintptr_t a0, uintptr_t a1, uintptr_t a2, uintptr_t a3,
                                 uintptr_t a4, uintptr_t a5, uintptr_t a6, uintptr_t a7,
                                 double d0, double d1, double d2, double d3,
                                 double d4, double d5, double d6, double d7) {
    (void) a1; (void) a2; (void) a3; (void) a4; (void) a5; (void) a6; (void) a7;
    (void) d0; (void) d1; (void) d2; (void) d3;
    (void) d4; (void) d5; (void) d6; (void) d7;
    int corner = hsb_latch_is_corner();
    int on = hsb_corner2_enabled();
    if (on && corner) {
        uint32_t n = __atomic_add_fetch(&g_cs.pilfer_blocked, 1u, __ATOMIC_RELAXED);
        if (n == 1u || n % 20u == 0u) {
            char sfx[176];
            hsb_cs_suffix(sfx, sizeof(sfx));
            LOGI("pilfer BLOCKED (corner latch x=%.1f y=%.1f)%s", g_latch.x, g_latch.y, sfx);
        }
        return 0u;   /* 不调原函数 */
    }
    uint32_t np = __atomic_add_fetch(&g_cs.pilfer_passed, 1u, __ATOMIC_RELAXED);
    if (corner) {
        /* 锁存是角区但没拦：只剩"corner2 没开"这一种（否则上面就 return 了）。**必打**——
         * 否则"拦了没用"和"根本没开"在日志里长得一样（22:33 那轮无法归因的根因）。 */
        char sfx[176];
        hsb_cs_suffix(sfx, sizeof(sfx));
        LOGI("pilfer CORNER-PASS (corner2=%d, NOT blocked) x=%.1f y=%.1f%s",
             on, g_latch.x, g_latch.y, sfx);
    } else if (np == 1u || np % 20u == 0u) {
        /* 透传采样行（第 1 次 + 每 20 次）：否则"正常 Home 没被误伤"只能靠 report 反推。 */
        char sfx[176];
        hsb_cs_suffix(sfx, sizeof(sfx));
        LOGI("pilfer PASSED%s", sfx);
    }
    return g_orig_pilfer
               ? g_orig_pilfer(a0, a1, a2, a3, a4, a5, a6, a7,
                               d0, d1, d2, d3, d4, d5, d6, d7)
               : 0u;
}

/* ===== 第三锚点：系统导航热区谓词的**只读**观测桩 =====
 * 为什么只读：它是 trigger_gesture_judge 的第 7 参来源，系统的 Home/recents 判据就从这里出。
 * 在改任何东西之前，必须先有一张"系统自己认为哪里是热区"的地图——否则互斥设计就是猜
 * （x<10 组的教训：我们判据最好的地方，是我们和系统撞得最准的地方，跟区域大小无关）。
 * (x,y)∈s0/s1 且按 float 存（调用点 fmov s0,s11 / fmov s1,s12），返回 bool∈w0。
 *
 * 为什么**每行必打**（2026-10-15 改，原实现是"第1次 + 每20次 + rv翻转"）：
 * 一次手势只有个位数到十几次调用，每 20 次一采等于整轮手势一行都不出；第一版装机就是
 * 这样，7 个手势只换来 2 行读数，边界图全是采样假象——探针开着却什么都没测到，比不打
 * 更坏。这个 hook 只在 `debug.hsb.native.anchor3 nav_hot_zone` 显式打开后才装，量级由
 * 采集人自己控制，所以这里不替任何人省日志。真的怕吵，用 logcat 抓完再关 prop 即可。
 * 由此 g_navprobe_last_rv 那个共享 latch 也删了：它本来就是限流的附属品，且是多线程
 * 下无同步的裸 int。 */
static HsbFn16 g_orig_navprobe;
static uint32_t g_navprobe_logged;

static uintptr_t hsb_stub_navprobe(uintptr_t a0, uintptr_t a1, uintptr_t a2, uintptr_t a3,
                                   uintptr_t a4, uintptr_t a5, uintptr_t a6, uintptr_t a7,
                                   double d0, double d1, double d2, double d3,
                                   double d4, double d5, double d6, double d7) {
    uintptr_t rv = g_orig_navprobe
                       ? g_orig_navprobe(a0, a1, a2, a3, a4, a5, a6, a7,
                                         d0, d1, d2, d3, d4, d5, d6, d7)
                       : 0u;
    uint32_t n = __atomic_add_fetch(&g_navprobe_logged, 1u, __ATOMIC_RELAXED);
    int rvi = (int) (uint32_t) rv;
    LOGI("navprobe x=%.1f y=%.1f rv=%d #%u",
         hsb_lo32_float(d0), hsb_lo32_float(d1), rvi, n);
    return rv;
}

/* ===== 第三锚点（judge_probe）：`trigger_gesture_judge` 的**只读**观测桩 =====
 * 为什么打它：nav_hot_zone 已测出系统热区=横向中段、与我们角窗 0 重叠 ⇒ "角窗内压制热区"
 * 这条路被否。但三连照旧发生，说明系统在角区拿到"不在热区"的 verdict 后仍走了 Home 分支。
 * 这个函数就是那个分叉点本体（调用点 0x7afca8 `bl trigger_gesture_judge`，w2 = 热区 verdict）。
 *
 * 签名（0x7afca8 调用点 + 本体序言双证）：
 *   trigger_gesture_judge(x0 = downTime(ms), x1 = eventTime(ms),
 *                         s0 = x, s1 = y, s2 = x2, s3 = y2, w2 = hot_zone verdict)
 * 本体 `fsub s12, s3, s1` / `mov w19, w2` / `mov x20, x1` / `mov x21, x0`：这几个寄存器
 * 一路活到后段，说明 x0/x1/w2 与 s0..s3 都是被缓存的入参；s3-s1 是终点-起点位移分量，
 * 用于角度判定（s12）。s8/s9 是 s0/s1 的副本（`fmov s8,s2` / `fmov s9,s0`）。
 *
 * 每行必打（同 navprobe 的理由：不采样，采样会漏掉判据翻转点）。坐标原样打、不做任何
 * 缩放或换算——第一版 navprobe 的教训就是"为了让日志好看而动数据"，下游脚本会拿错量纲。 */
static HsbFn16 g_orig_judgeprobe;
static uint32_t g_judgeprobe_logged;

static uintptr_t hsb_stub_judgeprobe(uintptr_t a0, uintptr_t a1, uintptr_t a2, uintptr_t a3,
                                     uintptr_t a4, uintptr_t a5, uintptr_t a6, uintptr_t a7,
                                     double d0, double d1, double d2, double d3,
                                     double d4, double d5, double d6, double d7) {
    uintptr_t rv = g_orig_judgeprobe
                       ? g_orig_judgeprobe(a0, a1, a2, a3, a4, a5, a6, a7,
                                           d0, d1, d2, d3, d4, d5, d6, d7)
                       : 0u;
    uint32_t n = __atomic_add_fetch(&g_judgeprobe_logged, 1u, __ATOMIC_RELAXED);
    LOGI("judge dt=%lld et=%llu x=%.1f y=%.1f x2=%.1f y2=%.1f hot=%u rv=%u #%u",
         (long long) a0, (unsigned long long) a1,
         hsb_lo32_float(d0), hsb_lo32_float(d1), hsb_lo32_float(d2), hsb_lo32_float(d3),
         (unsigned) a2, (unsigned) (uint32_t) rv, n);
    return rv;
}

/* ===== 第三锚点（pilfer_probe）：`GestureInputHomeHelper::do_pilfer` 的**只读**观测桩 =====
 * 为什么换它（2026-10-15，judge_probe 无效之后）：
 *   - judge_probe 实测 0 调用（69 个真人角区手势 + 我的标准中段上滑，全部 0 行）⇒
 *     trigger_gesture_judge 不在我们的观测路径上，原"冲突在 judge"的推断作废；
 *   - 调用点全表扫描（bl 0x6b8464）只有两处，且都在 `GestureInputMonitorImpl` 内部：
 *         78e90c: add x0, x19, #0xc8 ; bl do_pilfer
 *         78ef30: add x0, x19, #0xc8 ; bl do_pilfer
 *     do_pilfer(x0 = monitor+0xc8) 单参；函数体自旋锁后 `bl input_InputMonitor_pilferPointers@plt`
 *     (0x6b84f4) —— **pilfer 动作本体就在这里**。
 * 现在只有它是"系统主动把角区流 pilfer 走"的可疑位置；打它 = 直接拿这个证据。
 *
 * 签名（调用点 + 本体双证）：do_pilfer(x0 = self+0xc8 子对象指针)，只 1 个整型参。
 * 只读：透传 + 打 self/返回值，绝不改返回、绝不拦流。 */
static HsbFn16 g_orig_pilferprobe;
static uint32_t g_pilferprobe_logged;

static uintptr_t hsb_stub_pilferprobe(uintptr_t a0, uintptr_t a1, uintptr_t a2, uintptr_t a3,
                                      uintptr_t a4, uintptr_t a5, uintptr_t a6, uintptr_t a7,
                                      double d0, double d1, double d2, double d3,
                                      double d4, double d5, double d6, double d7) {
    uintptr_t rv = g_orig_pilferprobe
                       ? g_orig_pilferprobe(a0, a1, a2, a3, a4, a5, a6, a7,
                                            d0, d1, d2, d3, d4, d5, d6, d7)
                       : 0u;
    uint32_t n = __atomic_add_fetch(&g_pilferprobe_logged, 1u, __ATOMIC_RELAXED);
    LOGI("pilfer self=%p rv=%u #%u", (void *) a0, (unsigned) (uint32_t) rv, n);
    return rv;
}

/* ===== 第三锚点（action_probe）：`motion_event_handled_proxy::should_handle_event` 桩 =====
 * 静勘结论（docs/adaptation/os4-hook-point-survey.md）：pilfer 的唯一出口是 H2
 * (GestureInputMonitor::pilfer_pointers)，但角区 pilfer 的**放行闸**是 H1——
 *   `7af85c: mov x0, x19(事件) ; mov w1, #2(MOVE)`
 *   `7af864: bl  should_handle_event`
 *   `7af868: cbz w0, 0x7afb2c        // 返 0 ⇒ 整段 pilfer 不执行`
 * 所以"只掐角区 pilfer"的着力点在 H1，而不是一拦全拦的 H2。
 *
 * 签名（调用点 + 本体双证）：should_handle_event(x0 = MotionEvent*, w1 = 期望 action)。
 *
 * ## 抑制档（2026-10-16，`debug.hsb.native.corner.supp`）
 * 只读实测（os4-hook-point-survey.md §6.2）已证明**系统自己的返回值就带区域差异**：
 * 角窗内 44 行 rv 全为 1、中段 62 行 rv=0 ⇒ `should_handle_event` 本来就是按区域放行的，
 * 我们按同一区域返 false 不需要新造判据，天然对齐系统语义。
 *
 * 抑制条件（三条必须同时成立，缺一条就透传）：
 *   1. 属性已开（默认关；debug. 前缀，重启即失效 = 安全的默认态）
 *   2. `want == 2`（MOVE 闸）。只掐 pilfer 放行那一处，不碰 want=0/1 的另两个调用点
 *   3. 事件坐标落在角窗（hsb_corner_hit_xy，与 hsb_corner_gate / :ui inCornerTriangle 同源）
 *
 * ## fail-open（本桩的硬约束）
 * getter 未解析 / 屏尺寸属性缺失 / 横屏 / 比例属性非法 —— 任何一种都**原样返回系统的 verdict**。
 * 宁可漏抑制（回到今天的三连），也绝不猜：猜错就是"吞了系统 Home 而扇形又没呼出"的死带，
 * 2026-10-11 已经为这个付过一次代价（见 hsb_corner_ratio 的注释）。
 *
 * ## 观测
 * 命中抑制必打一行（带 edge/band 便于回溯阈值），透传每 50 行 + 第 1 行打一条采样。
 *
 * 属性（2026-10-17 改）：`persist.hsb.corner.supp`（原来是 `debug.hsb.native.corner.supp`）。
 * 原因：debug.* 重启即清 ⇒ 抑制默认不生效；现在由模块 App 的设置开关（"底角斜滑独占"）推送，
 * 用户随时可关，且重启后仍在（读法仍是 1 秒缓存 ⇒ 改动 ≤1s 生效，无需重启宿主）。 */
#define HSB_ACTION_SUPP_PROP HSB_CORNER_SUPP_PROP

static int g_act_supp_on;
static uint64_t g_act_supp_s;
static uint32_t g_act_supp;    /* 命中抑制次数（进程内累计） */
static uint32_t g_act_pass;    /* 透传次数 */

static int hsb_action_supp_enabled(void) {
    uint64_t now = hsb_now_s();
    if (g_act_supp_s != now) {
        char v[8] = {0};
        g_act_supp_s = now;
        g_act_supp_on = __system_property_get(HSB_ACTION_SUPP_PROP, v) > 0 && v[0] == '1';
    }
    return g_act_supp_on;
}

/* 屏尺寸（1 秒缓存）。action 桩手里没有 Impl self（a0 是 MotionEvent），屏幕尺寸只能走属性；
 * 与条线同一来源（模块 App 推的 persist.hsb.band.screen_*，实测 1080x2400）。
 * 缺失 ⇒ 0 ⇒ 上层 fail-open。 */
static float g_act_scr_w, g_act_scr_h;
static uint64_t g_act_scr_s;

static void hsb_action_screen(float *w, float *h) {
    uint64_t now = hsb_now_s();
    if (g_act_scr_s != now) {
        g_act_scr_s = now;
        g_act_scr_w = hsb_cfg_float(HSB_BAND_SW_PX_PROP, 1.0f, 100000.0f, 0.0f);
        g_act_scr_h = hsb_cfg_float(HSB_BAND_SH_PX_PROP, 1.0f, 100000.0f, 0.0f);
    }
    *w = g_act_scr_w;
    *h = g_act_scr_h;
}

static HsbFn16 g_orig_actionprobe;
static uint32_t g_actionprobe_logged;

static uintptr_t hsb_stub_actionprobe(uintptr_t a0, uintptr_t a1, uintptr_t a2, uintptr_t a3,
                                      uintptr_t a4, uintptr_t a5, uintptr_t a6, uintptr_t a7,
                                      double d0, double d1, double d2, double d3,
                                      double d4, double d5, double d6, double d7) {
    /* 先调原函数：保留它 2468B 里的全部副作用（状态写、派发），只改回给调用方的 verdict。
     * 不调原函数就返 0 会绕过副作用，那不是我这次敢改的东西。 */
    uintptr_t rv = g_orig_actionprobe
                       ? g_orig_actionprobe(a0, a1, a2, a3, a4, a5, a6, a7,
                                            d0, d1, d2, d3, d4, d5, d6, d7)
                       : 0u;
    uint32_t n = __atomic_add_fetch(&g_actionprobe_logged, 1u, __ATOMIC_RELAXED);

    HsbBackEv ev;
    int ev_ok = hsb_back_event_read(a0, &ev);
    int want = (int) a1;

    /* 抑制：属性开 + MOVE 闸 + 角窗。任一条件不满足（含读不到坐标/屏尺寸）都走透传。 */
    int suppressed = 0;
    if (hsb_action_supp_enabled() && want == 2 && ev_ok) {
        float sw, sh, ratio, hot_pct;
        hsb_action_screen(&sw, &sh);
        hsb_corner_ratio(&ratio, &hot_pct);
        int why = 0;
        if (hsb_corner_hit_xy(ev.x, ev.y, sw, sh, ratio, hot_pct, &why)) {
            suppressed = 1;
            __atomic_add_fetch(&g_act_supp, 1u, __ATOMIC_RELAXED);
            LOGI("action SUPPRESS want=2 act=%d raw=(%.1f,%.1f) dt=%lld dev=%d rv=%u->0 #%u "
                 "[edge=%.1f band=%.1f scr=%.0fx%.0f]",
                 ev.act, ev.x, ev.y, (long long) ev.dt, ev.dev, (unsigned) rv, n,
                 sw * ratio, sh - (sh * hot_pct / 100.0f), sw, sh);
        } else if (n % 50u == 0u) {
            LOGI("action PASS not-corner want=2 act=%d raw=(%.1f,%.1f) rv=%u #%u (why=%d)",
                 ev.act, ev.x, ev.y, (unsigned) rv, n, why);
        }
    } else if (n == 1u || n % 50u == 0u) {
        if (ev_ok) {
            LOGI("action_probe ev=%p want=%d act=%d raw=(%.1f,%.1f) dt=%lld dev=%d rv=%u #%u%s",
                 (void *) a0, want, ev.act, ev.x, ev.y, (long long) ev.dt, ev.dev,
                 (unsigned) rv, n, hsb_action_supp_enabled() ? " (supp armed)" : "");
        } else {
            LOGI("action_probe ev=%p want=%d rv=%u #%u (coords unavailable: getters unresolved)",
                 (void *) a0, want, (unsigned) rv, n);
        }
    }

    if (suppressed) return 0u;   /* 只改 verdict：系统据此跳过 pilfer 放行段 */
    __atomic_add_fetch(&g_act_pass, 1u, __ATOMIC_RELAXED);
    return rv;
}

/* --- 候选 load bias + 可执行段边界（两条来源共用，先 phdr 后 maps 追加） --- */
typedef struct {
    uintptr_t start;
    uintptr_t end;
} HsbRange;

static HsbRange g_xsegs[HSB_MAX_SEGS];
static unsigned g_xseg_count;

static uintptr_t g_cands[HSB_MAX_CANDS];
static unsigned g_cand_count;

static void hsb_add_cand(uintptr_t b) {
    if (!b) return;
    for (unsigned i = 0; i < g_cand_count; i++) if (g_cands[i] == b) return;
    if (g_cand_count < HSB_MAX_CANDS) g_cands[g_cand_count++] = b;
}

/* --- 来源 A：linker 自己的视图（跨 namespace，APK 内嵌也给出真 bias） --- */
typedef struct {
    uint32_t matches;
} HsbImageRequest;

static void hsb_add_xseg(uintptr_t start, uintptr_t end) {
    if (!start || end <= start || g_xseg_count >= HSB_MAX_SEGS) return;
    g_xsegs[g_xseg_count].start = start;
    g_xsegs[g_xseg_count].end = end;
    g_xseg_count++;
}

static int hsb_find_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    HsbImageRequest *req = (HsbImageRequest *) data;
    const char *name = info->dlpi_name ? info->dlpi_name : "";
    if (!hsb_leaf_is_target(name)) return 0;
    req->matches++;
    hsb_add_cand((uintptr_t) info->dlpi_addr);
    /* 可执行段边界也从 program header 拿一份：maps 万一读不到，仍有界可校验 */
    for (uint16_t i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_X)) continue;
        hsb_add_xseg((uintptr_t) info->dlpi_addr + ph->p_vaddr,
                     (uintptr_t) info->dlpi_addr + ph->p_vaddr + ph->p_memsz);
    }
    return 0;
}

/*
 * --- 来源 B：/proc/self/maps（参考工程的 lsplt 路线） ---
 *
 * 实机（1008）读数：这条腿在桌面进程**匹配不到**目标（`lib=` 为空、安装靠 phdr 成功），
 * 原因是结构性的而非权限问题：`extractNativeLibs=false` 时内核映射的是**整个 APK 文件**，
 * maps 里的路径是 `/product/priv-app/MiuiHome/MiuiHome.apk`，`…!/lib/…` 只是 linker 的
 * 内部名（= `dlpi_name`）。所以按末段 soname 匹配 maps 天然落空。
 * 这是**好事**：APK 里还有别的库/dex 也在映射同一个文件，maps 分不清归属，若把它们的可执行段
 * 也当作目标范围，特征扫就可能在整个 APK 的映射里撞到别的库的字节 → 假阳性装错地址。
 * phdr 的 PT_LOAD(R+X) 恰好是目标库自己的紧边界，故本 spike 以 phdr 为准，maps 只在
 * "库被解到磁盘"的形态下补一拍。
 */
static uint32_t g_maps_logged;

static unsigned hsb_scan_maps(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    uintptr_t lowest = (uintptr_t) -1;
    while (fgets(line, sizeof(line), f)) {
        /* 形如 78abcd0000-78abcd4000 r-xp 00040000 07:32 123456 /path/lib.app.so */
        unsigned long start = 0, end = 0, pgoff = 0;
        char perms[5] = {0};
        if (sscanf(line, "%lx-%lx %4s %lx %*x:%*x %*u", &start, &end, perms, &pgoff) != 4) {
            continue;
        }
        /* 路径：跳过前 6 个字段后的剩余文本 */
        char *p = line;
        for (int i = 0; i < 5 && p; i++) p = strchr(p, ' ');
        if (!p) continue;
        while (*p == ' ') p++;
        size_t plen = strlen(p);
        while (plen && (p[plen - 1] == '\n' || p[plen - 1] == '\r')) p[--plen] = '\0';
        if (!hsb_leaf_is_target(p)) continue;
        if (g_hook.lib_path[0] == '\0') {
            snprintf(g_hook.lib_path, sizeof(g_hook.lib_path), "%s", p);
        }
        if (g_maps_logged++ < 4u) LOGI("maps: %s", p);
        if (start < lowest) lowest = start;
        if (perms[2] == 'x') {
            if (g_xseg_count < HSB_MAX_SEGS) {
                g_xsegs[g_xseg_count].start = (uintptr_t) start;
                g_xsegs[g_xseg_count].end = (uintptr_t) end;
                g_xseg_count++;
            }
            /* 常规 ELF：可执行段 start - pgoff = load bias（vaddr ≡ off mod 页）；
             * APK 内嵌时这条会偏一个库在包内的偏移，故只作为候选，由段内校验+prologue 裁决 */
            hsb_add_cand((uintptr_t) (start - pgoff));
        }
    }
    fclose(f);
    if (lowest != (uintptr_t) -1) hsb_add_cand(lowest);  /* 首段（含 ELF 头）起点≈bias */
    return g_xseg_count;
}

/* 目标地址（含 prologue 读取长度）必须落在已记录的可执行段内——越界即拒，防误基引发崩桌面 */
static int hsb_in_exec(uintptr_t addr, size_t len) {
    for (unsigned i = 0; i < g_xseg_count; i++) {
        if (addr >= g_xsegs[i].start && addr + len <= g_xsegs[i].end) return 1;
    }
    return 0;
}

/*
 * phdr 与 maps 会各给一份**同一段内存**的可执行区间（如 .12 里那条 RX PT_LOAD）。
 * 不合并 ⇒ 特征扫会把同一命中数两遍（hits=2）而被"歧义即拒"挡死。故扫之前先区间归并。
 */
static void hsb_merge_xsegs(void) {
    for (unsigned i = 1; i < g_xseg_count; i++) {           /* 插入排序（≤16 条） */
        HsbRange k = g_xsegs[i];
        unsigned j = i;
        while (j > 0 && g_xsegs[j - 1].start > k.start) {
            g_xsegs[j] = g_xsegs[j - 1];
            j--;
        }
        g_xsegs[j] = k;
    }
    unsigned n = 0;
    for (unsigned i = 0; i < g_xseg_count; i++) {
        if (n && g_xsegs[i].start <= g_xsegs[n - 1].end) {  /* 重叠/相接：并入上一条 */
            if (g_xsegs[i].end > g_xsegs[n - 1].end) g_xsegs[n - 1].end = g_xsegs[i].end;
            continue;
        }
        g_xsegs[n++] = g_xsegs[i];
    }
    g_xseg_count = n;
}

/*
 * 兜底定位：按 profile 的 32B prologue 特征在该库可执行段里扫。
 * 为什么要它：设备构建（4.0.0.14）与手头镜像（marble .12 / dada .16）的 .text 会整体
 * 漂移（.12 vs .16 已见 0x390 位移），固定 st_value 在异构建上必然对不上；而同一 Rust
 * crate 的同版本族里**函数序言字节通常不变**，扫特征就能把地址自愈回来。
 * 安全：只在 prop 开时扫；步长 4（AArch64 入口必 4 对齐）；命中数≠1 一律不用（歧义=拒）。
 */
static uintptr_t hsb_sigscan(const uint8_t *pat, uint32_t *hits_out) {
    uintptr_t found = 0;
    uint32_t hits = 0;
    for (unsigned i = 0; i < g_xseg_count; i++) {
        const uint8_t *base = (const uint8_t *) g_xsegs[i].start;
        size_t span = (size_t) (g_xsegs[i].end - g_xsegs[i].start);
        if (span < HSB_PROLOGUE_BYTES) continue;
        size_t n = span - HSB_PROLOGUE_BYTES + 1u;
        for (size_t k = 0; k < n; k += 4u) {
            if (base[k] != pat[0]) continue;
            unsigned b = 1u;
            for (; b < HSB_PROLOGUE_BYTES; b++) if (base[k + b] != pat[b]) break;
            if (b == HSB_PROLOGUE_BYTES) {
                hits++;
                found = (uintptr_t) (base + k);
                if (hits > 1u) { *hits_out = hits; return 0u; }  /* 歧义：早停，别猜 */
            }
        }
    }
    *hits_out = hits;
    return hits == 1u ? found : 0u;
}

/*
 * PC 相对指令的「立即数字段掩码」（跨构建指纹用）。
 * 为什么需要：同一 Rust crate 的同族构建里，函数序言**除 PC 相对位移外逐字节相同**——实测
 * 镜像 8.01.02.7654 vs 设备 8.01.02.7719：on_swipe_process / on_back_invoke / on_back_cancelled /
 * handle_back_gesture / is_in_bottom_gesture_region / init_gesture_type / match_region_check
 * 32/32 全同；try_delegate_back_to_sidebar 只有第 20–23 字节（bl imm）不同；on_swipe_start 与
 * on_swipe_stop_direct 只有末 4 字节（adrp imm）不同。
 * 不做掩码会怎样：新锚点拿镜像 prologue 去比设备代码必然 mismatch ⇒ 被 fail-closed 挡在门外
 * （宁可拒装也不猜是对的，但"整族都拒"就失去兜底意义）。掩码只放开**必然随布局变化**的字段，
 * 其余位仍逐位比 ⇒ 指纹强度基本不变。
 */
static uint32_t hsb_word_mask(uint32_t w) {
    if ((w & 0xFC000000u) == 0x94000000u) return 0xFC000000u;  /* BL  imm26 */
    if ((w & 0xFC000000u) == 0x14000000u) return 0xFC000000u;  /* B   imm26 */
    if ((w & 0xFF000010u) == 0x54000000u) return 0xFF00001Fu;  /* B.cond imm19 */
    if ((w & 0x7E000000u) == 0x34000000u) return 0x7E00001Fu;  /* CBZ/CBNZ imm19 */
    if ((w & 0x7E000000u) == 0x36000000u) return 0x7E00001Fu;  /* TBZ/TBNZ imm14 */
    if ((w & 0x9F000000u) == 0x90000000u) return 0x9F00001Fu;  /* ADRP */
    if ((w & 0x9F000000u) == 0x10000000u) return 0x9F00001Fu;  /* ADR  */
    if ((w & 0x3B000000u) == 0x18000000u) return 0xC400001Fu;  /* LDR (literal) */
    if ((w & 0x3B000000u) == 0x38000000u) return 0xFFC003FFu;  /* load/store 无符号立即数 */
    return 0xFFFFFFFFu;                                        /* 其它一律全等 */
}

/* prologue 匹配：先逐字节全等（快路径），失败再按字掩码比；*masked 标出"靠掩码才过"。 */
static int hsb_pl_match(const volatile uint8_t *p, const uint8_t *pat, uint32_t *bad_out,
                        int *masked_out) {
    unsigned b = 0;
    for (; b < HSB_PROLOGUE_BYTES; b++) {
        if (p[b] != pat[b]) break;
    }
    if (bad_out) *bad_out = b;
    if (b == HSB_PROLOGUE_BYTES) {
        if (masked_out) *masked_out = 0;
        return 1;
    }
    for (unsigned w = 0; w + 4u <= HSB_PROLOGUE_BYTES; w += 4u) {
        uint32_t pw = (uint32_t) p[w] | ((uint32_t) p[w + 1] << 8) |
                      ((uint32_t) p[w + 2] << 16) | ((uint32_t) p[w + 3] << 24);
        uint32_t pp;
        memcpy(&pp, pat + w, 4u);
        uint32_t m = hsb_word_mask(pp);
        if ((pw & m) != (pp & m)) return 0;
    }
    if (masked_out) *masked_out = 1;
    return 1;
}

/* 掩码版特征扫描：与 hsb_sigscan 同一套纪律（步长 4、命中≠1 即弃），只是按字掩码比。
 * 首字若含 PC 相对字段就不能用"首字节预筛"（立即数变了首字节也变）⇒ 那种情况全量扫。 */
static uintptr_t hsb_sigscan_mask(const uint8_t *pat, uint32_t *hits_out) {
    uintptr_t found = 0;
    uint32_t hits = 0;
    uint32_t want[HSB_PROLOGUE_BYTES / 4u];
    uint32_t mask[HSB_PROLOGUE_BYTES / 4u];
    for (unsigned i = 0; i < HSB_PROLOGUE_BYTES / 4u; i++) {
        memcpy(&want[i], pat + i * 4u, 4u);
        mask[i] = hsb_word_mask(want[i]);
    }
    for (unsigned s = 0; s < g_xseg_count; s++) {
        const volatile uint8_t *base = (const volatile uint8_t *) g_xsegs[s].start;
        size_t span = (size_t) (g_xsegs[s].end - g_xsegs[s].start);
        if (span < HSB_PROLOGUE_BYTES) continue;
        size_t n = span - HSB_PROLOGUE_BYTES + 1u;
        for (size_t k = 0; k < n; k += 4u) {
            if (mask[0] == 0xFFFFFFFFu && base[k] != pat[0]) continue;
            unsigned w = 0;
            for (; w < HSB_PROLOGUE_BYTES / 4u; w++) {
                const volatile uint8_t *q = base + k + w * 4u;
                uint32_t qw = (uint32_t) q[0] | ((uint32_t) q[1] << 8) |
                              ((uint32_t) q[2] << 16) | ((uint32_t) q[3] << 24);
                if ((qw & mask[w]) != (want[w] & mask[w])) break;
            }
            if (w == HSB_PROLOGUE_BYTES / 4u) {
                hits++;
                found = (uintptr_t) (base + k);
                if (hits > 1u) { *hits_out = hits; return 0u; }  /* 歧义：早停，别猜 */
            }
        }
    }
    *hits_out = hits;
    return hits == 1u ? found : 0u;
}

/* （第二锚点只支持 pilfer_pointers：backup 存 g_orig_pilfer，存活标志 g_hook2_installed；
   第三锚点只支持 nav_hot_zone：backup 存 g_orig_navprobe，存活标志 g_hook3_installed，
   桩是只读的——透传 + 打 (x,y)→rv，绝不改返回值） */

/* 附锚点（anchor3）的桩选择与 backup 落位。抽成函数是为了让**安装与自愈走同一套**：
 * 否则两处各写一遍 strcmp 链，日后加第五个探针时改漏一处 = 自愈后调错原函数（崩桌面）。 */
static void *hsb_aux3_stub_for(const char *name) {
    if (name && strcmp(name, "action_probe") == 0) return (void *) hsb_stub_actionprobe;
    if (name && strcmp(name, "pilfer_probe") == 0) return (void *) hsb_stub_pilferprobe;
    if (name && strcmp(name, "judge_probe") == 0) return (void *) hsb_stub_judgeprobe;
    return (void *) hsb_stub_navprobe;
}

static void hsb_aux3_save_backup(const char *name, void *backup) {
    if (name && strcmp(name, "judge_probe") == 0) {
        g_orig_judgeprobe = (HsbFn16) (uintptr_t) backup;
    } else if (name && strcmp(name, "pilfer_probe") == 0) {
        g_orig_pilferprobe = (HsbFn16) (uintptr_t) backup;
    } else if (name && strcmp(name, "action_probe") == 0) {
        g_orig_actionprobe = (HsbFn16) (uintptr_t) backup;
    } else {
        g_orig_navprobe = (HsbFn16) (uintptr_t) backup;
    }
}

static void hsb_install_at(const HsbLauncherAnchor *a, uintptr_t at, uintptr_t base,
                           const char *how, int secondary) {
    void *backup = NULL;
    void *stub = secondary == 2 ? hsb_aux3_stub_for(a->name)
                     : secondary == 1 ? (void *) hsb_stub_pilfer
                                      : (void *) hsb_stub;
    int rc = g_api->hook_func((void *) at, stub, &backup);
    /* ABI 稳健性（2026-10-14）：LSPosed 的 native `hook_func` **官方头文件我们并不持有**
     * ——所依赖的三个 artifact（api/interface/service 101.x）里没有任何 .h/.so，本结构体
     * 是手工 ABI 镜像；真机实测返回 0 表成功（version=2）。但若某版本把它改成"返回原函数
     * 指针 / null 表失败"（Dobby/shadowhook 两种流派都存在），只认 `rc == 0` 会把**成功
     * 判成失败**（指针低 32 位通常非 0）。故判据只看 out-param：失败时 backup 保持 NULL
     * （调用前清零），成功与否与返回值语义解耦；rc 只用于日志。 */
    if (backup != NULL) {
        if (secondary) {
            if (secondary == 2) {
                /* 第三锚点（只读观测件）：只记 backup + 存活标志，不碰主/副锚点状态。
                 * 四个探针各存各的 backup：混用一个的话，从 A 切到 B 时旧 backup 会指向
                 * 已 unhook 的函数，调过去直接崩桌面。
                 * 2026-10-16：同时记 target + 快照 + 原始序言，供心跳巡检与自愈用
                 * （原先附锚点没有 G3：实测装上 235s 被 ROM 抹掉后静默失效，
                 * 而日志与"探针没开"长得一模一样）。 */
                hsb_aux3_save_backup(a->name, backup);
                g_hook3_installed = 1;
                snprintf(g_hook3_name, sizeof(g_hook3_name), "%s", a->name);
                g_hook3_target = at;
                memcpy(g_hook3_prol, a->prologue, sizeof(g_hook3_prol));
                memcpy(g_hook3_snap, (const void *) at, sizeof(g_hook3_snap));
                LOGI("hook installed(3) %s target=%p backup=%p size=%llu via=%s bias=%p stub=%s",
                     a->name, (void *) at, backup, (unsigned long long) a->size, how, (void *) base,
                     strcmp(a->name, "action_probe") == 0 ? "actionprobe"
                       : strcmp(a->name, "pilfer_probe") == 0 ? "pilferprobe"
                       : strcmp(a->name, "judge_probe") == 0 ? "judgeprobe"
                                                             : "navprobe");
                return;
            }
            /* 第二锚点（pilfer_pointers）：只记 backup + 存活标志，不碰主锚点状态。 */
            g_orig_pilfer = (HsbFn16) (uintptr_t) backup;
            g_hook2_installed = 1;
            snprintf(g_hook2_name, sizeof(g_hook2_name), "%s", a->name);
            LOGI("hook installed(2) %s target=%p backup=%p size=%llu via=%s bias=%p stub=pilfer",
                 a->name, (void *) at, backup, (unsigned long long) a->size, how, (void *) base);
            return;
        }
        g_orig = (HsbFn16) (uintptr_t) backup;
        g_hook.hook_installed++;
        g_hook.bias = base;
        g_hook.target = at;
        /* 补丁快照（供 60s 心跳比对，见 g_patch_snapshot 注释）：安装后**立刻**读回 32B，
         * 它就是"补丁还在"的基准；被抹时会变回 a->prologue（原始序言）。 */
        memcpy(g_patch_orig, a->prologue, sizeof(g_patch_orig));
        memcpy(g_patch_snapshot, (const void *) at, sizeof(g_patch_snapshot));
        g_patch_armed = 1;
        g_patch_wiped = 0;
        g_hook.installed_at_s = hsb_now_s();
        g_hook.last_log_s = g_hook.installed_at_s;
        snprintf(g_hook.anchor, sizeof(g_hook.anchor), "%s", a->name);
        snprintf(g_hook.how, sizeof(g_hook.how), "%s", how);
        g_hook.has_xy = hsb_anchor_has_xy(a->name);
        g_hook.has_ret = hsb_anchor_has_ret(a->name);
        g_hook.has_args4 = hsb_anchor_has_args4(a->name);
        g_hook.has_dims = hsb_anchor_has_dims(a->name);
        g_hook.has_gate = hsb_anchor_has_gate(a->name);
        g_hook.has_latch = hsb_anchor_has_latch(a->name);
        g_hook.has_back_event = hsb_anchor_has_back_event(a->name);
        g_corner_checked_s = 0;   /* 换锚点时强制重读 corner 开关，别用上一个人的缓存 */
        LOGI("hook installed %s target=%p backup=%p size=%llu via=%s bias=%p lib=%s xy=%d ret=%d args4=%d dims=%d gate=%d latch=%d",
             a->name, (void *) at, backup, (unsigned long long) a->size, how,
             (void *) base, g_hook.lib_path, g_hook.has_xy, g_hook.has_ret,
             g_hook.has_args4, g_hook.has_dims, g_hook.has_gate, g_hook.has_latch);
    } else {
        g_hook.hook_failed++;
        snprintf(g_hook.reason, sizeof(g_hook.reason),
                 "hook_func rc=%d backup=%p at=%p via=%s", rc, backup, (void *) at, how);
        LOGI("hook failed: %s", g_hook.reason);
    }
}

/*
 * 结构校验需要"扫描命中地址所属的那一段的基址"（ELF 视图的 base）。
 * sigscan 返回的是 base+k（base 为某条可执行段起点）⇒ 从 g_xsegs 里反查包含它的那条。
 * 找不到返回 0（调用方按失败处理）。
 */
static uintptr_t hsb_xseg_base_of(uintptr_t addr) {
    for (unsigned i = 0; i < g_xseg_count; i++) {
        if (addr >= g_xsegs[i].start && addr < g_xsegs[i].end) return g_xsegs[i].start;
    }
    return 0u;
}

/*
 * ===== 锚点结构校验（D2，2026-10-16）=====
 * prologue 逐字节/掩码匹配只能证明"这 32 字节像那个函数"，不能证明"这就是那个函数"。
 * 这里再加一层更强的身份证据：**该函数体内确有 bl/blr 落到该锚点声明的导入的 GOT 槽**
 * （底座 hsb_elf_view / hsb_import_got / hsb_a64_decode 由参考工程 MiuiBackGestureHook
 * 迁入，见 hsb_elf_view.h 头注释）。
 *
 * 只在锚点声明了 `required_imports` 时生效（NULL = 跳过，行为与引入前完全一致）。
 * 返回 1 = 通过（或未声明）；0 = 不符，*which_out 指向失败的那条导入符号名。
 * 任何解析失败都算"不符"——但调用方**不因此拒装**：打 `struct-mismatch` 后继续走特征
 * 扫描路，保留既有的兜底能力（fail-closed 的是"这一条候选"，不是整个锚点）。
 */
static int hsb_anchor_import_ok(const HsbLauncherAnchor *a, uintptr_t base, uintptr_t at,
                                const char **which_out) {
    HsbElfView view;
    uintptr_t fn_off;
    if (which_out) *which_out = NULL;
    if (a->required_imports == NULL) return 1;
    if (!hsb_elf_parse((const uint8_t *) base, &view)) {
        if (which_out) *which_out = "(elf parse failed)";
        return 0;
    }
    if (at < base) {
        if (which_out) *which_out = "(target below base)";
        return 0;
    }
    fn_off = at - base;
    for (const char *const *p = a->required_imports; p && *p; ++p) {
        uintptr_t got = 0u;
        uintptr_t off;
        int found = 0;
        if (!hsb_import_find_got(&view, *p, &got)) {
            if (which_out) *which_out = *p;
            return 0;
        }
        for (off = 0u; off + 4u <= (uintptr_t) a->size; off += 4u) {
            if (hsb_a64_call_targets_import(&view, fn_off + off, got)) {
                found = 1;
                break;
            }
        }
        if (!found) {
            if (which_out) *which_out = *p;
            return 0;
        }
    }
    return 1;
}

/* 定位一个锚点（主/副/附共用）。返回 0 = 没找到。
 * 命中时 *base_out = 采纳的 load bias（sigscan 命中为 0），how = "offset"/"sigscan"。
 * 两路都 fail-closed：可执行段边界校验（hsb_in_exec）+ 32B prologue 校验（逐字节 → PC 相对掩码）。
 * 特征扫描命中数≠1 一律不用（歧义=拒，宁可不装也不猜）。 */
static uintptr_t hsb_locate(const HsbLauncherAnchor *a, uintptr_t *base_out, char *how,
                            size_t how_sz) {
    *base_out = 0u;

    /* offset==0 = profile 明确标记"本锚点还没取到偏移"（跨构建）⇒ 跳过偏移路，直接特征扫描。
     * 否则 base+0 会撞在 ELF 头上，每个候选白打一行 out-of-exec，日志噪音盖住有效读数。 */
    int have_offset = a->offset != 0u;
    if (!have_offset) {
        LOGI("locate %s: profile offset=0 ⇒ 直接特征扫描（真机读到 lib+偏移 后再回填）", a->name);
    }

    /* 第一路：profile 偏移 + 候选 load bias（同构建设备应直接命中） */
    for (unsigned c = 0; have_offset && c < g_cand_count; c++) {
        uintptr_t base = g_cands[c];
        uintptr_t at = base + (uintptr_t) a->offset;
        if (!hsb_in_exec(at, HSB_PROLOGUE_BYTES)) {
            LOGI("cand %p out of exec seg, skip (target=%p)", (void *) base, (void *) at);
            continue;
        }
        g_hook.prologue_checked++;
        const volatile uint8_t *p = (const volatile uint8_t *) at;
        uint32_t bad = HSB_PROLOGUE_BYTES;
        int masked = 0;
        if (hsb_pl_match(p, a->prologue, &bad, &masked)) {
            /* 结构校验（D2）：prologue 过了还要证明"体内确实调用声明的导入"。
             * 不符 → 打 struct-mismatch 并继续找下一个候选/走特征扫描，不直接拒装。 */
            const char *bad_imp = NULL;
            if (!hsb_anchor_import_ok(a, base, at, &bad_imp)) {
                g_hook.prologue_mismatch++;
                snprintf(g_hook.reason, sizeof(g_hook.reason),
                         "%s cand=%p struct-mismatch %s", a->name, (void *) base,
                         bad_imp ? bad_imp : "?");
                LOGI("cand rejected: %s", g_hook.reason);
                continue;
            }
            snprintf(how, how_sz, "%s", "offset");
            if (masked) {
                g_hook.prologue_masked++;
                LOGI("cand accepted(masked) %s base=%p 首个差异字节=%u（PC 相对立即数漂移，其余位全等）",
                     a->name, (void *) base, bad);
            }
            *base_out = base;
            return at;
        }
        g_hook.prologue_mismatch++;
        snprintf(g_hook.reason, sizeof(g_hook.reason), "%s cand=%p prologue[%u]=%02x want %02x",
                 a->name, (void *) base, bad, p[bad], a->prologue[bad]);
        LOGI("cand rejected: %s", g_hook.reason);
    }

    /* 第二路：偏移全崩（构建漂移）⇒ 按 prologue 特征扫地址；全等扫零命中才用掩码版 */
    g_hook.sig_scans++;
    uint32_t hits = 0;
    uintptr_t sig = hsb_sigscan(a->prologue, &hits);
    int masked = 0;
    if (!sig && hits == 0u) {
        sig = hsb_sigscan_mask(a->prologue, &hits);
        masked = 1;
    }
    g_hook.sig_hits = hits;
    if (sig) {
        /* 结构校验同样适用于扫描路：命中地址若过不了"必调导入"证明，就不能当这个锚点用
         * （宁可不装也不装错——错误的地址上挂 inline hook 会崩桌面）。 */
        const char *bad_imp = NULL;
        uintptr_t sig_base = (uintptr_t) hsb_xseg_base_of(sig);
        if (!hsb_anchor_import_ok(a, sig_base, sig, &bad_imp)) {
            g_hook.prologue_mismatch++;
            snprintf(g_hook.reason, sizeof(g_hook.reason),
                     "%s sigscan@%p struct-mismatch %s", a->name, (void *) sig,
                     bad_imp ? bad_imp : "?");
            LOGI("hook refused: %s", g_hook.reason);
            return 0u;
        }
        if (masked) g_hook.prologue_masked++;
        LOGI("sigscan%s found %s at=%p (offsets stale, hits=1)", masked ? "(masked)" : "", a->name,
             (void *) sig);
        snprintf(how, how_sz, "%s", "sigscan");
        return sig;
    }
    snprintf(g_hook.reason, sizeof(g_hook.reason),
             "offset cands=%u mismatch=%u | sigscan hits=%u segs=%u (build drift or codegen diff)",
             g_cand_count, g_hook.prologue_mismatch, hits, g_xseg_count);
    LOGI("hook refused: %s", g_hook.reason);
    return 0u;
}

/* 解析并安装一个具名锚点（主/副共用）。返回 1 = 装成功。 */
static int hsb_install_named(const char *want, int secondary) {
    for (unsigned i = 0; i < HSB_ANCHOR_COUNT; i++) {
        const HsbLauncherAnchor *a = &kHsbAnchors[i];
        if (strcmp(a->name, want) != 0) continue;
        char how[16] = {0};
        uintptr_t base = 0u;
        /* 第三锚点只收只读观测件：把"能不能挂 action/pilfer 桩"在这里就挡死，
         * 不给后人把 nav_hot_zone 接到会改判定的桩上的机会（判定那一类集合见 has_gate/has_ret）。 */
        if (secondary == 2 && !hsb_anchor_has_navprobe(a->name)) {
            snprintf(g_hook.reason, sizeof(g_hook.reason), "aux3 %s 非只读观测件", want);
            LOGI("hook skipped: %s", g_hook.reason);
            return 0;
        }
        uintptr_t at = hsb_locate(a, &base, how, sizeof(how));
        if (!at) return 0;
        hsb_install_at(a, at, base, how, secondary);
        return 1;
    }
    snprintf(g_hook.reason, sizeof(g_hook.reason), "anchor %s not in profile", want);
    LOGI("hook skipped: %s", g_hook.reason);
    return 0;
}

/* ── 只解析、不挂钩（2026-10-18，rotate_size）──────────────────────────────────────
 * 与 [hsb_install_named] 的区别：不调 hsb_install_at（不写任何字节、不改任何判定），
 * 只把地址存起来给 hsb_live_screen_size 直接调。候选枚举由 hsb_try_install_hooks 做完，
 * 所以必须在那之后调用。
 * 返回 1 = 解析成功（g_rotate_size_fn 已就位）。 */
static int hsb_resolve_call_only(const char *want) {
    for (unsigned i = 0; i < HSB_ANCHOR_COUNT; i++) {
        const HsbLauncherAnchor *a = &kHsbAnchors[i];
        if (strcmp(a->name, want) != 0) continue;
        char how[16] = {0};
        uintptr_t base = 0u;
        uintptr_t at = hsb_locate(a, &base, how, sizeof(how));
        if (!at) return 0;
        g_rotate_size_fn = (void (*)(int *)) at;
        LOGI("rotate_size resolved @%p via %s（只调用，不挂钩）", (void *) at, how);
        return 1;
    }
    LOGI("rotate_size: profile 里没有这个锚点");
    return 0;
}

static void hsb_try_install_hooks(void) {
    const char *want2 = hsb_chosen_anchor2();
    int need2 = want2 != NULL;
    const char *want3 = hsb_chosen_anchor3();
    int need3 = want3 != NULL;
    /* 全都装好了才停；主已装、副/附未装时仍要进来补（不重装已装的）。 */
    if (g_hook.hook_installed && (!need2 || g_hook2_installed) && (!need3 || g_hook3_installed))
        return;
    if (!g_api || !g_api->hook_func) return;
    if (g_hook.attempts >= HSB_MAX_ATTEMPTS) return;
    g_hook.attempts++;

    g_cand_count = 0;
    g_xseg_count = 0;
    g_hook.lib_path[0] = '\0';
    HsbImageRequest req = {0u};
    dl_iterate_phdr(hsb_find_cb, &req);
    g_hook.matches_phdr = req.matches;
    unsigned after_phdr = g_xseg_count;
    hsb_scan_maps();
    g_hook.matches_maps = g_xseg_count - after_phdr;
    hsb_merge_xsegs();
    if (g_xseg_count == 0u) {
        snprintf(g_hook.reason, sizeof(g_hook.reason),
                 "target lib absent here (phdr=%u maps=%u cb=%u)",
                 req.matches, g_xseg_count, g_report.launcher_cb_hits);
        LOGI("hook not ready: %s", g_hook.reason);
        return;
    }

    if (!g_hook.hook_installed) {
        const char *want = hsb_chosen_anchor();
        LOGI("anchor selected: %s (phdr=%u maps_x=%u cands=%u)", want, g_hook.matches_phdr,
             g_hook.matches_maps, g_cand_count);
        if (!hsb_install_named(want, 0)) return;
    }
    /* 第二锚点（动作位）：角区双锚点方案。缺省不装。 */
    if (need2 && !g_hook2_installed) {
        /* 只有"真的在装 pilfer_pointers"时那条锁存警告才有意义（2026-10-14 降噪）：
         * 之前无论 anchor2 是什么都打，`anchor2=on_swipe_stop_direct` 的正常档也被刷成
         * "拦截不生效"，真机看日志时是纯噪声。 */
        if (!g_hook.has_latch && strcmp(want2, "pilfer_pointers") == 0) {
            /* 静默失效是最坏的：拦截桩在跑，但锁存从不写 ⇒ pblk 永远 0，会被误读成
             * "拦了没用"。这里显式点名正确配置。 */
            LOGI("WARN anchor2=pilfer_pointers 但主锚点 %s 不锁存(has_latch=0) ⇒ 拦截不生效；"
                 "请设 debug.hsb.native.anchor=init_gesture_type", g_hook.anchor);
        }
        LOGI("anchor2 selected: %s", want2);
        if (!hsb_install_named(want2, 1)) LOGI("anchor2 install failed (primary stays)");
    }
    /* 第三锚点（只读观测 nav_hot_zone）：底角热区地图，2026-10-14。缺省不装。
     * 与副锚点不同，它**不要求主锚点是谁**——hook 装在系统自己的谓词上，与我们的主锚点
     * 选哪条线无关；这也是它能独立当测量工具的原因。 */
    if (need3 && !g_hook3_installed) {
        LOGI("anchor3 selected: %s", want3);
        if (!hsb_install_named(want3, 2)) LOGI("anchor3 install failed (primary stays)");
    }
    /* 只调用不挂钩的锚点（当前旋转下的屏尺寸）。失败不致命：侧滑带几何回落属性推送
     * （既有行为），下轮巡检再试一次；成了就不再进这个分支。 */
    if (!g_rotate_size_fn && !g_rotate_size_giveup) {
        if (!hsb_resolve_call_only("rotate_size")) g_rotate_size_giveup = 1;
    }
    /* 安装面汇总：一条可 grep 的"定论行"。真机取证/脚本判词都靠它——Java 侧的
     * NativeProbe.report 在 launcher 域没人调（OS4 没有模块 Java 半侧），别等它。
     * 走到这里说明本轮补齐结束；全部装好时上面的早退会拦住后续重复调用，故只打一次。 */
    if (g_hook.hook_installed) {
        LOGI("hooks ready: main=%s anchor2=%s aux3=%s masked=%u", g_hook.anchor,
             (g_hook2_installed && g_hook2_name[0]) ? g_hook2_name : "-",
             (g_hook3_installed && g_hook3_name[0]) ? g_hook3_name : "-",
             g_hook.prologue_masked);
        /* 装上之后起心跳：从此"native 还活着吗/补丁还在吗"不用做手势也能看（见 hsb_heartbeat_thread） */
        hsb_heartbeat_start();
    }
}

/* ── 文件 marker 关断通道（2026-10-13 追加，见 plan §17）─────────────────────────────
 * 真机已证：property 的两条免-su 写路都被拒——本 .so 里的 `__system_property_set`
 * 回 `rc=-1 readback=0`，systemui 侧反射抛
 * `RuntimeException: failed to set system property "persist.hsb.native.hook" to "1"`。
 * ⇒ 需要一条**完全不需要 root** 的通道：模块 App 能无权限写自己的
 * `Android/media/<applicationId>/`，这里让 launcher 侧读同一个文件。
 * 语义：首字符 '0' ⇒ 显式关断（与 `persist.hsb.native.hook == "0"` **并联**，
 * 任一为 0 即关）；文件不存在 / 读不到 / 空 / 其它值 ⇒ 不表态（fail-open 不变）。
 * 多候选路径是**自证**用的：真机日志打出哪条路径读到了什么，
 * "SELinux 挡哪条、/storage 在不在本进程 namespace 里"一眼可见，不用再猜。 */
#define HSB_GATE_MARKER_NAME "hsb_native_gate"
static const char *kGateMarkerPaths[] = {
    "/storage/emulated/0/Android/media/io.github.mikudayoooooooo.hypersidebar/" HSB_GATE_MARKER_NAME,
    "/sdcard/Android/media/io.github.mikudayoooooooo.hypersidebar/" HSB_GATE_MARKER_NAME,
    "/data/media/0/Android/media/io.github.mikudayoooooooo.hypersidebar/" HSB_GATE_MARKER_NAME,
    "/data/local/tmp/" HSB_GATE_MARKER_NAME,
    NULL,
};
static const char *kGateMarkerTags[] = {"emulated0", "sdcard", "dmedia0", "tmp"};

static int g_marker_state = -1; /* -1 未读 / 0 读到但非 "0" / 1 读到 "0" */
static int g_marker_path = -1;  /* 命中的候选下标；-1 = 一条都没读到 */
static char g_marker_val[8] = {0};

/* 返回 1 = 有文件明确写着 "0"（关断指令）；0 = 不表态。结果同时记进 g_marker_* 供取证行打印。 */
static int hsb_gate_marker_probe(void) {
    g_marker_state = -1;
    g_marker_path = -1;
    g_marker_val[0] = '\0';
    for (int i = 0; kGateMarkerPaths[i]; i++) {
        FILE *f = fopen(kGateMarkerPaths[i], "rb");
        if (!f) continue; /* 不存在 / 被 SELinux 挡 / 路径没挂进本进程 namespace：都不表态 */
        char buf[8] = {0};
        size_t n = fread(buf, 1, sizeof(buf) - 1, f);
        fclose(f);
        if (n == 0) continue;
        char c = 0;
        for (size_t k = 0; k < n; k++) {
            const char ch = buf[k];
            if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') {
                c = ch;
                break;
            }
        }
        if (c == 0) continue;
        g_marker_path = i;
        g_marker_val[0] = c;
        g_marker_val[1] = '\0';
        g_marker_state = (c == '0') ? 1 : 0;
        return g_marker_state;
    }
    return 0;
}


/*
 * ── 通道测量（2026-10-13，用户 m03291）──────────────────────────────────────
 * 需求原话：不复用 SidebarEvokeHelper（那是参考资料，不是拿来直接用的组件）；要的是
 * "native 判定之后再呼出"。而 native 活在 `com.miui.home` 里——那是被 hyos_spawner
 * fork 出来的**无 ART 进程**，没有模块 Java 半侧，所以"告诉 :ui 一声"必须先有一条
 * 通道。这条通道只能由 OS 级机制提供，候选就三类：**属性 / 文件 / binder**。
 *
 * 本探针一次把前两类（成本最低的）全试掉：只写、只回读、只打日志，不改任何行为。
 * 判词 `chanprop:` / `chanfile:` 每行都自带 ⇒ OK / FAILED，并带 errno。
 * 开关：`setprop debug.hsb.native.chanprobe 1`（默认关；native_init 读一次，需重启宿主）。
 *
 * 为什么属性要试 5 个名字：`property_contexts` 里没有 `hsb` 条目 ⇒ 名字落在哪个"已有前缀"
 * 决定它的 SELinux 类型，而**只有特定类型**才允许 app 域 set（default_prop 只有 init/shell
 * 能写）。之前 `persist.hsb.native.hook` 从 systemui 与模块 App 都写失败，很可能就是名字/
 * 类型问题，而不是"域不允许写属性"。
 * 为什么文件要试 5 个路径：既要过 SELinux（域对文件类型），又要过 storage 挂载
 * （scoped storage / FUSE 的跨 uid 写判定）——两个都是本机无法离线查证的，只能实测。
 */
#define HSB_CHANPROBE_PROP "debug.hsb.native.chanprobe"

static int hsb_chanprobe_on(void) {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get(HSB_CHANPROBE_PROP, v) > 0) return v[0] == '1';
    return 0;
}

static void hsb_chan_probe(void) {
    static const char *kProps[] = {
        "debug.hsb.probe", "sys.hsb.probe", "vendor.hsb.probe",
        "persist.sys.hsb.probe", "persist.hsb.probe", NULL,
    };
    for (int i = 0; kProps[i]; i++) {
        errno = 0;
        int rc = __system_property_set(kProps[i], "1");
        char back[PROP_VALUE_MAX + 1] = {0};
        int n = __system_property_get(kProps[i], back);
        int ok = (n > 0 && strcmp(back, "1") == 0);
        LOGI("chanprop: set %s=1 rc=%d readback=%s ⇒ %s（errno=%d）",
             kProps[i], rc, n > 0 ? back : "-", ok ? "OK" : "FAILED", errno);
    }

    static const char *kFiles[] = {
        "/data/local/tmp/hsb_chan_probe",
        "/sdcard/hsb_chan_probe",
        "/storage/emulated/0/hsb_chan_probe",
        "/storage/emulated/0/Android/media/io.github.mikudayoooooooo.hypersidebar/hsb_chan_probe",
        "/data/user_de/0/com.miui.home/hsb_chan_probe",
        NULL,
    };
    for (int i = 0; kFiles[i]; i++) {
        errno = 0;
        FILE *f = fopen(kFiles[i], "wb");
        if (!f) {
            LOGI("chanfile: %s ⇒ 写 FAILED（errno=%d）", kFiles[i], errno);
            continue;
        }
        fputs("1", f);
        fclose(f);
        char b[4] = {0};
        int ok = 0;
        errno = 0;
        FILE *g = fopen(kFiles[i], "rb");
        if (g) {
            ok = (fread(b, 1, 1, g) == 1 && b[0] == '1');
            fclose(g);
        }
        LOGI("chanfile: %s ⇒ 写 OK / 回读 %s（errno=%d）", kFiles[i], ok ? "OK" : "FAILED", errno);
    }
}

/*
 * ── native → :ui 的「授权呼出」发布通道（2026-10-13，用户 m03327 选中间态）────────
 * 语义：native 判定"停顿成立"的那一刻，把「这条手势由我接管、:ui 可以出扇形」写到一个
 *       :ui 读得到的地方。**每手势只写一次**（不是每事件）⇒ 不进输入热路径。
 * 写哪：先按序试属性，再按序试文件；第一个"写成功且回读一致"的记为 winner 并缓存，
 *       之后只用它（失败才重新全扫，例如属性被清掉）。候选顺序与 :ui 侧
 *       TurboLayout.AUTH_PROPS 一致。
 * 读哪：:ui 侧读同一批名字，按 downTime 匹配（payload 第 2 段 = 物理 downTime）。
 * 开关：发布本身无副作用（写失败只打日志）；真正的行为开关在 :ui 侧
 *       `debug.hsb.native.strict`（默认 0 ⇒ 仍由 :ui 自判，中间态不生效）。
 * 判据：日志 `chanpublish:` —— 出现 `winner=prop|file …` 即通道打通。
 */
static const char *kPubProps[] = {
    "debug.hsb.evoke", "sys.hsb.evoke", "vendor.hsb.evoke",
    "persist.sys.hsb.evoke", "persist.hsb.evoke", NULL,
};
static const char *kPubFiles[] = {
    "/data/user_de/0/com.miui.home/hsb_evoke",
    "/data/local/tmp/hsb_evoke",
    "/storage/emulated/0/Android/media/io.github.mikudayoooooooo.hypersidebar/hsb_evoke",
    "/sdcard/hsb_evoke",
    NULL,
};
static volatile uint32_t g_pub_ok;
static volatile uint32_t g_pub_fail;
static int g_pub_kind = -1;   /* 0=prop winner, 1=file winner, -1=未定 */
static int g_pub_idx = -1;

static int hsb_pub_try_prop(const char *name, const char *payload) {
    errno = 0;
    int rc = __system_property_set(name, payload);
    char back[PROP_VALUE_MAX + 1] = {0};
    int n = __system_property_get(name, back);
    int ok = (n > 0 && strcmp(back, payload) == 0);
    LOGI("chanpublish: prop %s=%s rc=%d readback=%s ⇒ %s（errno=%d）ok=%u fail=%u",
         name, payload, rc, n > 0 ? back : "-", ok ? "OK" : "FAILED", errno,
         g_pub_ok, g_pub_fail);
    return ok;
}

static int hsb_pub_try_file(const char *path, const char *payload) {
    errno = 0;
    FILE *f = fopen(path, "wb");
    if (!f) {
        LOGI("chanpublish: file %s ⇒ 写 FAILED（errno=%d）", path, errno);
        return 0;
    }
    fputs(payload, f);
    fclose(f);
    char b[80] = {0};
    int ok = 0;
    errno = 0;
    FILE *g = fopen(path, "rb");
    if (g) {
        ok = (fgets(b, (int) sizeof(b), g) != NULL && strcmp(b, payload) == 0);
        fclose(g);
    }
    LOGI("chanpublish: file %s=%s ⇒ 写 OK / 回读 %s（errno=%d）ok=%u fail=%u",
         path, payload, ok ? "OK" : "FAILED", errno, g_pub_ok, g_pub_fail);
    return ok;
}

static void hsb_publish_evoke(const char *payload) {
    if (g_pub_kind == 0 && g_pub_idx >= 0 && hsb_pub_try_prop(kPubProps[g_pub_idx], payload)) {
        g_pub_ok++;
        return;
    }
    if (g_pub_kind == 1 && g_pub_idx >= 0 && hsb_pub_try_file(kPubFiles[g_pub_idx], payload)) {
        g_pub_ok++;
        return;
    }
    g_pub_kind = -1;
    g_pub_idx = -1;
    for (int i = 0; kPubProps[i]; i++) {
        if (hsb_pub_try_prop(kPubProps[i], payload)) {
            g_pub_kind = 0;
            g_pub_idx = i;
            g_pub_ok++;
            LOGI("chanpublish: winner=prop %s（后续只走它）", kPubProps[i]);
            return;
        }
    }
    for (int i = 0; kPubFiles[i]; i++) {
        if (hsb_pub_try_file(kPubFiles[i], payload)) {
            g_pub_kind = 1;
            g_pub_idx = i;
            g_pub_ok++;
            LOGI("chanpublish: winner=file %s（后续只走它）", kPubFiles[i]);
            return;
        }
    }
    g_pub_fail++;
    LOGI("chanpublish: 全部候选失败 ⇒ native 无法通知 :ui（ok=%u fail=%u）", g_pub_ok, g_pub_fail);
}

/* ── 心跳线程：补丁完整性巡检（G1 检测者）+ 被抹即自愈（G3）────────────────────
 *
 * 真机证据（本机，hook 装上约 3.5 分钟后被抹）：
 *   - libapp_launcher.so 的 .text 是**从 MiuiHome.apk 直接映射**（/proc/<pid>/maps 里是 apk
 *     路径 + 文件偏移，没有独立 .so 行）⇒ plan §5.1 的归档镜像形态坐实；
 *   - 被抹同一刻系统打 `LowMemDetector: Memory pressure level: 3 (ai)` +
 *     `ScenarioStub:AI MEMORY PRESSURE POLICY` ⇒ ROM 的内存压力策略在抹页；
 *   - 进程内 tracefs 被 MIUI bpf 占用、kprobe 挂不上 ⇒ 抓不到确切系统调用；
 *   - `libapp_launcher.so` 的 .dynsym **不含 madvise**（只有 mmap/munmap）⇒ plan §5.2 的
 *     G2（挂 libapp_launcher 自己的 madvise PLT）在这个构建上**没有槽位可挂**；且抹页方是
 *     进程外的 LowMemDetector（process_madvise 一类），进程内 hook 也拦不到 ⇒ 只剩 G3 自愈。
 *
 * 做法（用框架自己的姿势，不自己写 .text）：`unhook_func(target)` → 校验目标已回到原始序言
 * → `hook_func(target, stub, &backup)` 重装 → 校验读回与新快照一致。
 *   · 为什么不 memcpy 快照回去：hook 是框架 hook_func 装的，trampoline/备份都归它管；
 *     自己写字节要额外处理 mprotect，以及"入口 br 之前 x17 还没被 adrp/add 初始化"的
 *     指令序撕裂（并发线程踩到就是崩桌面）。
 *   · 为什么不直接再调一次 hook_func：若某版框架是链式 hook，backup 会指回我们自己的桩
 *     ⇒ 每次事件递归爆栈。先 unhook 摘掉旧注册就没有这个歧义。
 * 不封顶，改指数退避（2026-10-17 修）：原来 5 次硬封顶，而这台 ROM 约每 8 分钟抹一次，
 * 封顶一到就 heal=5/0 永久静默（用户实测侧条线莫名失效）。现在每次自愈成功后退避翻倍
 * （5s→10s→…→上限 300s），连续 3 次没再被抹就回落 5s；第 8 次打一行建议重启桌面。
 * 心跳字段 heal=N/M：N = 累计自愈次数，M = 当前退避秒数。
 * 只由心跳线程调用（单线程），不碰任何流状态。
 *
 * ## 与 Java 侧热重载（LSPosed API 102）的关系：**互不干涉**
 * 2026-10-16 起 module.prop 开 `autoHotReload=true`。Java 侧热重载只换模块 APK 的
 * classloader，**不会**重装本 .so：`System.loadLibrary` 只在 `onModuleLoaded`（且热重载
 * 不重放该回调）调用一次，inline/PLT hook 绑在宿主 spawner 世代上——框架文档与参考工程
 * MiuiBackGestureHook 的 AGENTS.md 都把"热重载期间卸载/重建 APK native 入口"列为禁止操作。
 * 所以热重载后本文件的补丁与巡检**照旧**：被抹仍由本线程 5s 一拍自愈，与 Java 侧重装无关。
 * 反向亦然：本文件的失败路径只打日志（封顶后等用户重启桌面），不参与 Java 侧生命周期。 */
/* 2026-10-17 改：**不再硬封顶**。ROM 的内存压力策略约每 8 分钟抹一次 .text（真机实测：
 * `LowMemDetector` + `ScenarioStub`），5 次封顶 ⇒ 挂机 ~40 分钟后侧条静默失效、直到用户
 * 重启桌面——比"多自愈几次"糟得多。现在改用**指数退避**：每次自愈成功后等待翻倍
 * （5s→10s→…，上限 300s）；巡检连续 3 拍读到 `patch=ok` 则回落 5s（说明这轮擦除过去了）。
 * 心跳里的 `heal=N/M` 随之改语义：N = 已自愈次数，M = 当前退避秒数。 */
static uint32_t g_patch_heals;      /* 自愈成功次数（心跳 heal 的第一个数） */
static uint32_t g_patch_heal_fail;  /* 自愈失败/中止次数 */
static uint32_t g_heal_backoff_ms = 5000u;  /* 下一次自愈的等待（指数退避） */
static unsigned g_heal_clean_ticks;         /* 巡检连续读到 ok 的拍数（≥3 ⇒ 退避回落） */

/** 巡检间隔：退避值的下限 5s（心跳线程用它做睡眠长度）。 */
static uint32_t hsb_heal_interval_ms(void) {
    return g_heal_backoff_ms < 5000u ? 5000u : g_heal_backoff_ms;
}

static int hsb_patch_heal(void) {
    if (!g_patch_armed || !g_hook.target) return 0;
    if (!g_api || !g_api->hook_func || !g_api->unhook_func) return 0;
    /* 2026-10-17：这里原来有"5 次封顶 ⇒ g_patch_heal_stop=1 不再自愈"。现在不封顶——
     * 节奏由心跳线程的睡眠长度（[hsb_heal_interval_ms]，指数退避）控制，本函数每次被调就尝试。 */

    /* ① 摘掉旧注册（把目标页还原成原始字节；被抹时这步等于"本来就已是原始"） */
    g_api->unhook_func((void *) g_hook.target);

    /* ② 校验：目标必须已回到 profile 原始序言（G3「成组一致校验」的单锚点版）。
     *    不满足 = 第三种扰动，不重装。 */
    uint8_t now[32];
    memcpy(now, (const void *) g_hook.target, sizeof(now));
    if (memcmp(now, g_patch_orig, sizeof(now)) != 0) {
        g_patch_heal_fail++;
        LOGI("hook HB patch 自愈中止：unhook 后仍未回到原始序言 now=%02x%02x%02x%02x…"
             "（第三种扰动，不重装）", now[0], now[1], now[2], now[3]);
        return 0;
    }

    /* ③ 重装：与首次安装同一条路径（新 trampoline + 新 backup） */
    void *backup = NULL;
    (void) g_api->hook_func((void *) g_hook.target, (void *) hsb_stub, &backup);
    if (backup == NULL) {
        g_patch_heal_fail++;
        LOGI("hook HB patch 自愈失败：hook_func 未给 backup ⇒ 补丁仍是被抹状态");
        return 0;
    }
    g_orig = (HsbFn16) (uintptr_t) backup;

    /* ④ 校验：读回必须既不是原始序言、也要与刚落的快照一致，否则这次自愈不算数 */
    uint8_t chk[32];
    memcpy(chk, (const void *) g_hook.target, sizeof(chk));
    memcpy(g_patch_snapshot, chk, sizeof(g_patch_snapshot));
    if (memcmp(chk, g_patch_orig, sizeof(chk)) == 0) {
        g_patch_heal_fail++;
        LOGI("hook HB patch 自愈失败：hook_func 后目标仍是原始序言 ⇒ 补丁没写进去");
        return 0;
    }
    g_patch_wiped = 0;
    g_patch_heals++;
    /* 指数退避：这次刚自愈成功 ⇒ 下一次的等待翻倍（上限 300s），避免"ROM 连抹、我们连装"打表。
     * 巡检连续 3 拍 ok 会把退避回落（见心跳循环）——擦除是**周期性**的，不是一直发生。 */
    g_heal_clean_ticks = 0;
    g_heal_backoff_ms = (g_heal_backoff_ms < 300000u) ? (g_heal_backoff_ms * 2u) : 300000u;
    if (g_heal_backoff_ms > 300000u) g_heal_backoff_ms = 300000u;
    if (g_patch_heals == 8u) {
        LOGI("hook HB：已自愈 8 次 —— 本机 ROM 在频繁回收桌面 .text（内存压力策略）。"
             "功能仍在自愈（当前退避 %us 一拍），但若手势偶发失联，建议重启桌面。",
             g_heal_backoff_ms / 1000u);
    }
    LOGI("hook HB patch RE-ARMED (G3) #%u：装上后 %llus 被抹、本轮重装成功 target=%p backup=%p；"
         "下一事件起重新走本桩（命中/发布/抑制/arrowfix 恢复）%s",
         g_patch_heals,
         (unsigned long long) (hsb_now_s() >= g_hook.installed_at_s
                                  ? hsb_now_s() - g_hook.installed_at_s : 0),
         (void *) g_hook.target, backup,
         g_hook2_installed
             ? "⚠ anchor2 也在同一轮被抹且未存 target ⇒ 本轮不自愈它，需要重启桌面（anchor2 默认关，见 debug.hsb.native.anchor2）"
             : "");
    return 1;
}

/* ── 附锚点（anchor3）的自愈（2026-10-16）──────────────────────────────────────
 * 为什么单独一份：G3 原来只自愈主锚点。实测（12:15 那轮之前）`action_probe` 装上 **235 秒**
 * 就被 ROM 的内存压力策略抹掉，此后日志与"探针没开"完全一样 ⇒ 白跑一轮手势。
 * 抹除机制与主锚点同一个（见 hsb_patch_heal 上方长注释：.text 直接 mmap 自 APK，
 * LowMemDetector 外部抹页），所以做法也照抄：unhook → 校验回到原始序言 → 用**同一只桩**
 * 重装 → 校验读回。不自己写字节（mprotect + 指令序撕裂，见主锚点注释）。
 *
 * 封顶与主锚点共用一个上限常量：连续失败同样停止自愈只报警（fail-closed）。 */
static int hsb_hook3_heal(void) {
    if (!g_hook3_installed || !g_hook3_target || !g_hook3_name[0]) return 0;
    if (!g_api || !g_api->hook_func || !g_api->unhook_func) return 0;
    /* 2026-10-17：与主锚点同步取消硬封顶（节奏由心跳线程的指数退避控制） */

    g_api->unhook_func((void *) g_hook3_target);

    uint8_t now[32];
    memcpy(now, (const void *) g_hook3_target, sizeof(now));
    if (memcmp(now, g_hook3_prol, sizeof(now)) != 0) {
        g_hook3_heal_fail++;
        LOGI("hook HB aux3 自愈中止：unhook 后仍未回到原始序言 now=%02x%02x%02x%02x…（第三种扰动，不重装）",
             now[0], now[1], now[2], now[3]);
        return 0;
    }

    void *backup = NULL;
    (void) g_api->hook_func((void *) g_hook3_target, hsb_aux3_stub_for(g_hook3_name), &backup);
    if (backup == NULL) {
        g_hook3_heal_fail++;
        LOGI("hook HB aux3 自愈失败：hook_func 未给 backup ⇒ 补丁仍是被抹状态");
        return 0;
    }
    uint8_t chk[32];
    memcpy(chk, (const void *) g_hook3_target, sizeof(chk));
    if (memcmp(chk, g_hook3_prol, sizeof(chk)) == 0) {
        g_hook3_heal_fail++;
        LOGI("hook HB aux3 自愈失败：hook_func 后目标仍是原始序言 ⇒ 补丁没写进去");
        return 0;
    }
    memcpy(g_hook3_snap, chk, sizeof(g_hook3_snap));
    hsb_aux3_save_backup(g_hook3_name, backup);
    g_hook3_heals++;
    LOGI("hook HB aux3 RE-ARMED (G3) #%u %s target=%p backup=%p；下一事件起探针/抑制恢复",
         g_hook3_heals, g_hook3_name, (void *) g_hook3_target, backup);
    return 1;
}

/* ── 心跳线程：补丁完整性巡检（G1 检测者）+ 被抹即自愈（G3）────────────────────
 *
 * 为什么必须是一条**定时**线：本文件里原来那条 `hook ALIVE …` 其实是 `hook HIT` 的限流版
 * （`hsb_stub` 里 `first ? "hook HIT" : "hook ALIVE"`）——**只有事件到达才会打印**。
 * 于是"hook 被抹掉"与"hook 从没装上"在 logcat 里长得一模一样：彻底安静。
 * 真机 12:27 那轮（用户 m0xxxx）：每条手势都是 `native 未曾握手`、通道里最后一条发布老了
 * **42 分钟** ⇒ 桌面侧 native 一整段时间没干活，却拿不出任何一行证据说明是哪一种。
 *
 * 判词（读目标处 32B 与两个基准比）：
 *   patch=ok       与我们安装后立刻读回的快照**逐字节相同** ⇒ 补丁在位
 *   patch=WIPED    与 profile 里的**原始序言**逐字节相同 ⇒ 补丁被 ROM 内存压力策略抹掉
 *                  ⇒ 直接进 G3 自愈；自愈不成功才另打 `ARCHIVE REMAP DETECTED`
 *   patch=CHANGED  两者都不是（第三种扰动）⇒ 带出实读字节，**不重装**
 *
 * 节奏（2026-10-14 实测后改）：巡检 5s 一拍，摘要行仍 60s 一行。
 * 理由：真机看到"装上约 3.5 分钟被抹"，60s 一拍意味着最长 1 分钟手势链路不可用；5s 一拍
 * 读 32B + memcmp 的成本可以忽略，自愈晚了用户已经在骂。 */
static void *hsb_heartbeat_thread(void *arg) {
    (void) arg;
    uint8_t live[32];
    const char *last_state = "ok";
    unsigned ticks = 0;
    uint64_t last_sum_s = 0;   /* 摘要行节流（时间基，2026-10-17：退避后拍间隔可变） */
    for (;;) {
        struct timespec ts;
        uint32_t iv = hsb_heal_interval_ms();   /* 指数退避：5s 起，最多 300s（2026-10-17） */
        ts.tv_sec = (time_t) (iv / 1000u);
        ts.tv_nsec = (long) ((iv % 1000u) * 1000000u);
        nanosleep(&ts, NULL);
        if (!g_patch_armed || !g_hook.target) continue;
        ticks++;
        const char *state;
        memcpy(live, (const void *) g_hook.target, sizeof(live));
        if (memcmp(live, g_patch_snapshot, sizeof(live)) == 0) {
            state = "ok";
            g_patch_wiped = 0;
            /* 连续 3 拍 ok ⇒ 这一轮擦除过去了 ⇒ 退避回落 5s（指数退避的另一半，2026-10-17） */
            if (++g_heal_clean_ticks >= 3u) {
                g_heal_clean_ticks = 0;
                g_heal_backoff_ms = 5000u;
            }
        } else if (memcmp(live, g_patch_orig, sizeof(live)) == 0) {
            /* WIPED ⇒ 先自愈；自愈成功这一拍就当 ok（下一拍自然会读到新快照） */
            if (hsb_patch_heal()) {
                memcpy(live, (const void *) g_hook.target, sizeof(live));
                state = (memcmp(live, g_patch_snapshot, sizeof(live)) == 0) ? "ok" : "WIPED";
            } else {
                state = "WIPED";
                g_patch_wiped = 1;
            }
        } else {
            state = "CHANGED";
        }
        if (state != last_state) {   /* 指针比较：全部是字符串字面量 */
            if (state[0] == 'W') {
                LOGI("ARCHIVE REMAP DETECTED %s：目标页 32B 已还原成原始序言 ⇒ 手势链路静默失效"
                     "（此后不会再出现 hook HIT / chanpublish / back SUPPRESS）；"
                     "G3 自愈未成功（heal=%u fail=%u next=%us），恢复=重启桌面重装 hook",
                     g_hook.anchor, g_patch_heals, g_patch_heal_fail, g_heal_backoff_ms / 1000u);
            } else if (state[0] == 'C') {
                LOGI("hook HB patch CHANGED live=%02x%02x%02x%02x… snap=%02x%02x%02x%02x…"
                     " orig=%02x%02x%02x%02x…（第三种扰动，只报不重装）",
                     live[0], live[1], live[2], live[3],
                     g_patch_snapshot[0], g_patch_snapshot[1], g_patch_snapshot[2], g_patch_snapshot[3],
                     g_patch_orig[0], g_patch_orig[1], g_patch_orig[2], g_patch_orig[3]);
            }
            last_state = state;
        }
        /* ---- 附锚点（anchor3）巡检：同一块 .text 被抹时它一起死 ------------------
         * 2026-10-16 之前这里什么都没有 ⇒ action_probe 装上 235s 被抹后静默失效，
         * 而"被抹"与"没开探针"在日志里一模一样（B 组第一轮就是这么废掉的）。
         * 判词与主锚点同构（snap=补丁在位 / prol=已被抹 / 其它=第三种扰动只报）。
         * 单独一条行、不改主摘要行的格式：`hsb_native_silence_probe.ps1` 等脚本按主行判词，
         * 动它会让既有判读失效。 */
        if (g_hook3_installed && g_hook3_target) {
            static const char *last3 = "-";
            uint8_t l3[32];
            memcpy(l3, (const void *) g_hook3_target, sizeof(l3));
            const char *s3;
            if (memcmp(l3, g_hook3_snap, sizeof(l3)) == 0) {
                s3 = "ok";
            } else if (memcmp(l3, g_hook3_prol, sizeof(l3)) == 0) {
                s3 = hsb_hook3_heal() ? "healed" : "WIPED";
            } else {
                s3 = "CHANGED";
            }
            if (s3 != last3) {
                if (s3[0] == 'W') {
                    LOGI("hook HB aux3 WIPED %s：附锚点补丁被抹 ⇒ 探针/抑制静默失效"
                         "（自愈 heal=%u fail=%u 未成功）", g_hook3_name, g_hook3_heals,
                         g_hook3_heal_fail);
                } else if (s3[0] == 'C') {
                    LOGI("hook HB aux3 CHANGED %s live=%02x%02x%02x%02x…（第三种扰动，只报不重装）",
                         g_hook3_name, l3[0], l3[1], l3[2], l3[3]);
                }
                last3 = s3;
            }
            if (ticks % 12u == 0u) {
                LOGI("hook HB aux3=%s patch=%s heal=%u/%u target=%p", g_hook3_name, s3,
                     g_hook3_heals, g_hook3_heal_fail, (void *) g_hook3_target);
            }
        }
        if (hsb_now_s() >= last_sum_s + 60u) {   /* 摘要 60s 一条（退避后每拍间隔可能 >60s ⇒ 每拍都打） */
            last_sum_s = hsb_now_s();
            LOGI("hook HB main=%s alive=%llus patch=%s hits=%u seen=%u inband=%u dw=%u sw=%u af=%u ac=%u pub=%u/%u heal=%u/%u",
                 g_hook.anchor[0] ? g_hook.anchor : "-",
                 (unsigned long long) (hsb_now_s() >= g_hook.installed_at_s
                                           ? hsb_now_s() - g_hook.installed_at_s : 0),
                 state, g_hook.hits, g_supp.seen, g_supp.in_band, g_supp.dwelled,
                 g_supp.swallow, g_supp.arrow_fix, g_supp.arrow_clean, g_pub_ok, g_pub_fail,
                 g_patch_heals, g_heal_backoff_ms / 1000u);
        }
    }
    return NULL;
}

/* 启动心跳（幂等）：只在主锚点真装上之后调用，见 hooks ready 那一拍。 */
static void hsb_heartbeat_start(void) {
    if (g_hb_started) return;
    g_hb_started = 1;
    pthread_t th;
    if (pthread_create(&th, NULL, hsb_heartbeat_thread, NULL) == 0) {
        pthread_detach(th);
        LOGI("hook HB thread started（5s 巡检 + 60s 摘要：补丁完整性 / 计数；不打印 = 线程没起来）");
    } else {
        LOGI("hook HB thread create FAILED（补丁完整性无法巡检）");
    }
}

static int hsb_prop_enabled(void) {
#if !HSB_NATIVE_SPIKE
    /* 编译期总闸（release 线）：返回常量 0 ⇒ 所有 hook 路径（扫描/装 hook/回调补拍/
     * Java retry）都走不到，链接期还能被 dead-strip 掉。OS4 spike 分支由
     * CMake 的 HSB_NATIVE_SPIKE=ON 打开。这条替代"OS3 装机回归"做静态证明：
     * 主线包里这段代码不是"运行时不执行"，而是"根本不存在"。 */
    return 0;
#else
    /* 分三档（2026-10-13 **改 fail-open**，见 docs/adaptation/os4-native-hold-route-plan.md §16）：
     *   ① `debug.hsb.native.hook` 有值 ⇒ 用它（'1'=开、其它=关）：排障/压测的临时覆盖，
     *      重启即清、不落盘；
     *   ② 否则**任一通道的显式关断** ⇒ 关：`persist.hsb.native.hook == "0"`（模块 App 用 su 写）
     *      或文件 marker 首字符 '0'（模块 App **免 root** 写，见 plan §17）；
     *   ③ 其余（属性缺失 / 读不到 / 值非 "0"，marker 不存在/空/非 "0"）⇒ **开**。
     * 为什么两路关断**并联**：真机上属性写路全被拒，若只看属性，"用户关开关"这个动作会
     * 关不掉（属性停在旧的 "1"）——marker 那条不需要 root，是真正能落地的关断通道。
     * 为什么从 fail-closed 改成 fail-open（2026-10-13）：原语义要求"先有属性=1 才接管"，
     * 可真机上两条免-su 写路都可能被拒（模块 App 的 `su` 被 EACCES 挡在 execve；systemui 的
     * `SystemProperties.set` 反射抛异常），属性一旦停在 0，重启后 native 静默不装载 ——
     * 功能"时灵时不灵"，比误接管糟得多。而**本 .so 是被 LSPosed 按"模块启用 + 作用域"
     * dlopen 进 launcher 的**，"我现在在这里"本身就是用户授权的最强证据（参考实现 4371
     * 也是把 Zygisk module enabled state 当成唯一运行 gate，不再依赖运行期属性推送）。
     * 代价（已写进文档风险栏）：把模块开关关掉、属性与 marker 又都写不进的机型上 native
     * 仍会接管（停顿手势被吞、无扇形），须靠 LSPosed 禁用模块 / `debug.hsb.native.hook=0` 彻底关。 */
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.hsb.native.hook", v) > 0) return strcmp(v, "1") == 0;
    v[0] = '\0';
    if (__system_property_get(HSB_ENABLE_PROP, v) > 0 && strcmp(v, "0") == 0) return 0;
    if (hsb_gate_marker_probe() == 1) return 0;
    return 1;
#endif
}

__attribute__((visibility("default")))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    g_report.init_fired = 1;
    LOGI("native_init: entries=%p version=%u hookFunc=%p unhookFunc=%p",
         (const void *) entries,
         entries ? entries->version : 0u,
         entries ? (void *) (uintptr_t) entries->hook_func : NULL,
         entries ? (void *) (uintptr_t) entries->unhook_func : NULL);

    if (!entries || !entries->hook_func || !entries->unhook_func) {
        g_report.api_incomplete = 1;
        LOGI("native_init: api incomplete, probe stops (no hooks installed)");
        return NULL;
    }
    g_api = entries;
    g_report.api_version = entries->version;
    g_report.has_hook_func = entries->hook_func ? 1u : 0u;
    g_report.has_unhook_func = entries->unhook_func ? 1u : 0u;

    /* 总闸来源取证：真机排查"为什么没装 hook"第一句就看这行——是 debug 覆盖、是模块 App
     * 推的 persist/marker 显式关断（"0"）、还是都没表态（⇒ fail-open：被加载即接管）。
     * marker= 那栏把"哪条候选路径读到了什么"直接打出来（自证，见 plan §17）。 */
    {
        char d[PROP_VALUE_MAX] = {0};
        int hd = __system_property_get("debug.hsb.native.hook", d);
        char pv[PROP_VALUE_MAX] = {0};
        int hp = __system_property_get(HSB_ENABLE_PROP, pv);
        hsb_gate_marker_probe();
        char mdesc[64];
        if (g_marker_path >= 0) {
            snprintf(mdesc, sizeof mdesc, "\"%s\"@%s", g_marker_val, kGateMarkerTags[g_marker_path]);
        } else {
            snprintf(mdesc, sizeof mdesc, "none");
        }
        const char *src =
            hd > 0 ? "debug"
                   : (hp > 0 && strcmp(pv, "0") == 0)
                         ? "persist-kill"
                         : (g_marker_state == 1) ? "marker-kill"
                                                 : (hp > 0) ? "persist-open"
                                                            : (g_marker_state == 0) ? "marker-open" : "loaded-open";
        LOGI("gate: debug=%s persist=%s marker=%s src=%s ⇒ enabled=%d"
             "（优先级 debug 覆盖 > 任一通道显式 0 关断 > 缺失即开 fail-open）",
             hd > 0 ? d : "-", hp > 0 ? pv : "-", mdesc, src, hsb_prop_enabled());
    }

    /* 通道测量（默认关）：一次性把"launcher 能不能对别的进程序列化一句话"量出来。 */
    if (hsb_chanprobe_on()) hsb_chan_probe();

    /* 已加载库回放（照抄参考实现，但**只作取证**）：裸 soname 的 RTLD_NOLOAD 查询受
     * linker namespace 隔离与加载时机影响，实机给出 absent 而桌面库其实在内存里
     * （见 §门控复盘）。hook 侧现在自己扫 maps+phdr，不再依赖这里的结论。 */
    for (int i = 0; kReplayLibs[i]; i++) {
        void *h = dlopen(kReplayLibs[i], RTLD_NOW | RTLD_NOLOAD);
        if (h) {
            g_report.replay_mask |= 1u << i;
            LOGI("replay: %s already loaded (handle=%p)", kReplayLibs[i], h);
            on_module_loaded(kReplayLibs[i], h);
            dlclose(h);
        }
    }

    /* 门控只看 prop：prop 关=一行扫描代码都不多跑；prop 开=尝试安装（内部 fail-closed） */
    if (hsb_prop_enabled()) hsb_try_install_hooks();

    LOGI("native_init: ready installed=%u hits=%u rejected=%s",
         g_hook.hook_installed, g_hook.hits, g_hook.reason[0] ? g_hook.reason : "-");
    return on_module_loaded;
}

JNIEXPORT jstring JNICALL
Java_com_lsp_hypersidebar_util_NativeProbe_report(JNIEnv *env, jclass clazz);

/*
 * Java 侧补拍（第三个触发点）。native_init 那次尝试很可能太早——桌面的 Rust 库是它自己
 * System.loadLibrary 出来的，可能在我们之后；框架的 dlopen 回调能不能覆盖到这次加载，
 * 取决于 LSPosed 对 `android_dlopen_ext` 的拦截是否作用于该 namespace（未证）。
 * 所以再给一条**确定可达**的路：TurboLayout（桌面进程）init 时和延迟 5s 各调一次。
 * prop 关=直接返回，什么都不做。
 */
JNIEXPORT void JNICALL
Java_com_lsp_hypersidebar_util_NativeProbe_retry(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (!hsb_prop_enabled()) return;
    hsb_try_install_hooks();
    LOGI("retry from java: installed=%u hits=%u via=%s reason=%s",
         g_hook.hook_installed, g_hook.hits, g_hook.how[0] ? g_hook.how : "-",
         g_hook.reason[0] ? g_hook.reason : "-");
}

/*
 * Java 读系统属性口（2026-10-14，中间态配套）。TurboLayout 在 :ui 里要读 native 发布在
 * `debug.hsb.evoke` 上的授权值；:ui 若用反射 `android.os.SystemProperties.get`，hidden API
 * 可能直接拒（静默返回空）——而本 .so 在 :ui 里同样是已装载的模块（`native_init` 打过 gate 行）
 * ⇒ 直接用它自己的 `__system_property_get`，不经 hidden API。
 * 返回：非 null 字符串 = 本口可用（属性缺失时返回空串）；null = 参数非法/取串失败 ⇒ Java 回退反射。
 */
JNIEXPORT jstring JNICALL
Java_com_lsp_hypersidebar_util_NativeProbe_nativeGetProp(JNIEnv *env, jclass clazz, jstring jkey) {
    (void) clazz;
    if (!jkey) return NULL;
    jsize klen = (*env)->GetStringUTFLength(env, jkey);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    if (klen <= 0 || klen > PROP_NAME_MAX) return NULL;
    char key[PROP_NAME_MAX + 1] = {0};
    (*env)->GetStringUTFRegion(env, jkey, 0, klen, key);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    char val[PROP_VALUE_MAX + 1] = {0};
    int n = __system_property_get(key, val);
    if (n <= 0) return (*env)->NewStringUTF(env, "");   /* 本口可用，只是属性为空 */
    return (*env)->NewStringUTF(env, val);
}

/* 2026-10-14 晚删除：`Java_..._NativeProbe_nativeSetProp`（免-su 写属性的 native 写法人）。
 * 真机 19:24 实测证明它写不进去——`__system_property_set("persist.hsb.native.hook","1")`
 * 回 `rc=-1 readback=0 ⇒ FAILED`（systemui 里 SystemProperties.set 也抛 RuntimeException）。
 * 而总闸语义是 fail-open（属性/marker 都不表态即开）⇒ "写 1"本是空操作，唯一需要写的是
 * 免 root 的**关断** marker（`Android/media/<applicationId>/hsb_native_gate`，Java 侧写）。
 * 属性通道保留给手工 `adb shell su -c setprop …`；native 侧照旧读它（`hsb_prop_enabled()`）。
 */

/*
 * Java 取报告口（静态 native，符号进 exports-lsposed.map 的 global 段）。
 * 每行一个事实，Java 侧逐行写 HLog → 运行日志导出/自检报告自动携带。
 */
JNIEXPORT jstring JNICALL
Java_com_lsp_hypersidebar_util_NativeProbe_report(JNIEnv *env, jclass clazz) {
    (void)clazz;
    /* ctor 时刻进程名还是 `<pre-initialized>`（ specialize 未完），那时抓的 cmdline 没用：
     * 报告前重读一次（1008 导出里每段都显示 <pre-initialized> 就是这个原因） */
    if (!g_report.process[0] || strstr(g_report.process, "pre-initialized")) note_process();
    /* buf 尺寸按最坏情况算：process 127 + anchor/via/lib/reason 四栏 + 若干指针与满值 %u。
     * 历史上从 1024 一路抬到 5376（每加一行观测就抬一次；1024 那版会截掉尾部"拒装原因"）。
     * 2026-10-13 清掉 aux/evoke/sbobs/cancelobs/delegate/recover/swipestop 六行后收紧。 */
    char buf[4608];
    snprintf(buf, sizeof(buf),
             "magic=HSB1 process=%s\n"
             "ctor=%u native_init=%u api_version=%u hookFunc=%u unhookFunc=%u api_incomplete=%u\n"
             "dlopen_callback_calls=%u launcher_cb=%u replay_mask=0x%x\n"
             "hook: attempts=%u phdr=%u maps_x=%u checked=%u mismatch=%u masked=%u sigscan=%u/%u "
             "installed=%u failed=%u hits=%u anchor=%s via=%s bias=%p target=%p alive=%llus\n"
             "hook2: anchor2=%s installed2=%u (corner2 双锚点方案的 pilfer 动作位)\n"
             "hook3: aux3=%s installed3=%u navprobe=%u (系统导航热区谓词，只读观测)\n"
             "action: supp=%u on=%d pass=%u (should_handle_event 角窗抑制档)\n"
             "corner: fires=%u eval=%u hit=%u miss=%u bad=%u skip=%u force=%u "
             "latch=%u pblk=%u ppass=%u\n"
             "suppress: on=%d seen=%u inband=%u confirmed=%u reset=%u dwelled=%u would=%u "
             "swallow=%u ev=%u cancelp=%u geomx=%u (侧滑线 S3 抑制档)\n"
             "arrowfix: on=%d fixed=%u cleaned=%u (抬手/取消前把 self+0xf0 的 1/2 打成 0 "
             "⇒ 走 launcher 自己的 state!=1 收尾支：收箭头、不收 back)\n"
             "hook: lib=%s reason=%s",
             g_report.process[0] ? g_report.process : "(unknown)",
             g_report.ctor_ran, g_report.init_fired, g_report.api_version,
             g_report.has_hook_func, g_report.has_unhook_func, g_report.api_incomplete,
             g_report.callback_calls, g_report.launcher_cb_hits, g_report.replay_mask,
             g_hook.attempts, g_hook.matches_phdr, g_hook.matches_maps,
             g_hook.prologue_checked, g_hook.prologue_mismatch, g_hook.prologue_masked,
             g_hook.sig_scans, g_hook.sig_hits, g_hook.hook_installed,
             g_hook.hook_failed, g_hook.hits,
             g_hook.anchor[0] ? g_hook.anchor : "-",
             g_hook.how[0] ? g_hook.how : "-", (void *) g_hook.bias, (void *) g_hook.target,
             g_hook.installed_at_s ? (unsigned long long) (hsb_now_s() - g_hook.installed_at_s) : 0ull,
             g_hook2_name[0] ? g_hook2_name : "-", g_hook2_installed,
             g_hook3_name[0] ? g_hook3_name : "-", g_hook3_installed, g_navprobe_logged,
             g_act_supp, hsb_action_supp_enabled(), g_act_pass,
             g_cs.fires, g_cs.eval, g_cs.hit, g_cs.miss, g_cs.bad, g_cs.skip, g_cs.force,
             g_cs.latch, g_cs.pilfer_blocked, g_cs.pilfer_passed,
             hsb_suppress_value(), g_supp.seen, g_supp.in_band, g_supp.confirmed, g_supp.reset,
             g_supp.dwelled, g_supp.would, g_supp.swallow, g_supp.swallow_ev, g_supp.cancel_pass,
             g_supp.geom_bad,
             hsb_arrowfix_value(), g_supp.arrow_fix, g_supp.arrow_clean,
             g_hook.lib_path[0] ? g_hook.lib_path : "-",
             g_hook.reason[0] ? g_hook.reason : "-");
    return (*env)->NewStringUTF(env, buf);
}
