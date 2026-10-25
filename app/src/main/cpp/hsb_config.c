#include "hsb_config.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <android/log.h>

/* 与 hypersidebar_native.c 的 LOG_TAG 保持一致：取证 grep 单一 tag。 */
#define CFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "HSBNative", __VA_ARGS__)

/*
 * 多候选路径（2026-10-24 免 root 迁址，模式照抄 gate marker 的 kGateMarkerPaths）：
 * 写方 = 模块 App，直接写自己的**应用专属媒体目录**（平台规则：免权限、免 su）。
 * 原落点 /data/local/tmp 对 app 是 EACCES（2026-10-17 真机实测，当时靠 su 落盘），
 * 降为 @tmp 候选——手工排障 `su -c 'cp … /data/local/tmp/hsb.conf'` 仍有效。
 * 命中一次后粘住（g_cfg_idx），文件消失才重扫 ⇒ 1 秒一次的 stat 成本 = 1 次。
 * 首次命中打一行 `cfg: file=…@tag`——"哪条候选真能读"真机自证，不猜。
 */
static const char *kCfgPaths[] = {
    "/storage/emulated/0/Android/media/io.github.mikudayoooooooo.hypersidebar/hsb.conf",
    "/sdcard/Android/media/io.github.mikudayoooooooo.hypersidebar/hsb.conf",
    "/data/media/0/Android/media/io.github.mikudayoooooooo.hypersidebar/hsb.conf",
    "/data/local/tmp/hsb.conf",
    NULL,
};
static const char *kCfgTags[] = {"emulated0", "sdcard", "dmedia0", "tmp"};

#define HSB_CFG_MAX    8192
#define HSB_CFG_PREFIX "persist.hsb."

static char       g_buf[HSB_CFG_MAX];
static size_t     g_len;
static uint64_t   g_checked_s;
static long       g_mtime;
static long long  g_size;
static int        g_cfg_idx = -1;     /* 命中的候选下标；-1 = 尚无文件 */
static int        g_no_file_logged;   /* "四条候选全缺"只报一次，避免 1Hz 刷屏 */

static uint64_t cfg_now_s(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t) ts.tv_sec;
}

/* 1 秒粒度检查 + mtime/size 判变更 ⇒ 只在文件真变了才读盘。 */
static void cfg_reload_if_changed(void) {
    uint64_t n = cfg_now_s();
    if (g_checked_s == n) return;
    g_checked_s = n;
    struct stat st;

    /* 每 tick 按**优先级序**重扫候选（≤4 次 stat，成本可忽略）。不"粘住第一条命中"：
     * 旧构建（su 落盘时代）写在 @tmp 的陈旧 hsb.conf 会永久遮蔽新免 root 文件
     * （2026-10-25 真机实况：launcher 粘住了 @tmp 的 253B 陈旧文件，@emulated0
     * 出现后仍轮不到它）。 */
    int idx = -1;
    for (int i = 0; kCfgPaths[i]; i++) {
        if (stat(kCfgPaths[i], &st) == 0 && S_ISREG(st.st_mode)) { idx = i; break; }
    }
    if (idx < 0) {
        if (!g_no_file_logged) {
            g_no_file_logged = 1;
            /* 候选全缺 = 模块 App 还没写过 / 本进程读不到（SELinux/挂载）。不算错：
             * 继续走属性 → 编译期缺省，fail-open 语义不变。 */
            CFG_LOGI("cfg: no file（四条候选全缺 ⇒ 走属性/缺省）");
        }
        g_cfg_idx = -1;
        g_len = 0;
        g_mtime = 0;
        g_size = 0;
        return;
    }
    g_no_file_logged = 0;
    if (idx != g_cfg_idx) {
        g_cfg_idx = idx;
        CFG_LOGI("cfg: file=%s@%s size=%lld（免 root 配置通道命中）",
                 kCfgPaths[idx], kCfgTags[idx], (long long) st.st_size);
        /* 换了来源 ⇒ mtime/size 缓存按文件算，必须作废重读 */
        g_mtime = 0;
        g_size = 0;
    } else if (st.st_mtime == g_mtime && (long long) st.st_size == g_size) {
        return;
    }

    FILE *f = fopen(kCfgPaths[idx], "rb");
    if (!f) {
        /* stat 过了但 fopen 失败（与写方 rename 撞车等）：作废缓存，下个 tick 重读。 */
        g_len = 0;
        g_mtime = 0;
        g_size = 0;
        return;
    }
    size_t n_read = fread(g_buf, 1, sizeof(g_buf) - 1u, f);
    fclose(f);
    g_buf[n_read] = 0;
    g_len = n_read;
    g_mtime = st.st_mtime;
    g_size = (long long) st.st_size;
}

/* 行式 `key=value`（`#` 注释、允许两侧空白与 CRLF）里找 key（短名）。 */
static const char *cfg_file_value(const char *key, size_t *vlen) {
    if (!g_len) return NULL;
    const char *short_key = key;
    size_t plen = strlen(HSB_CFG_PREFIX);
    if (strncmp(key, HSB_CFG_PREFIX, plen) == 0) short_key = key + plen;
    size_t klen = strlen(short_key);
    const char *p = g_buf;
    const char *end = g_buf + g_len;
    while (p < end) {
        const char *eol = (const char *) memchr(p, '\n', (size_t) (end - p));
        const char *stop = eol ? eol : end;
        const char *eq = (const char *) memchr(p, '=', (size_t) (stop - p));
        if (eq && *p != '#') {
            const char *ks = p;
            const char *ke = eq;
            while (ks < ke && (*ks == ' ' || *ks == '\t')) ks++;
            while (ke > ks && (ke[-1] == ' ' || ke[-1] == '\t')) ke--;
            if ((size_t) (ke - ks) == klen && strncmp(ks, short_key, klen) == 0) {
                const char *vs = eq + 1;
                const char *ve = stop;
                while (vs < ve && (*vs == ' ' || *vs == '\t')) vs++;
                while (ve > vs && (ve[-1] == ' ' || ve[-1] == '\t' || ve[-1] == '\r')) ve--;
                *vlen = (size_t) (ve - vs);
                return vs;
            }
        }
        if (!eol) break;
        p = eol + 1;
    }
    return NULL;
}

/* 文件 → 属性（同名，完整 key）→ 都没有返回 0。 */
static int cfg_lookup(const char *key, char *out, size_t cap) {
    cfg_reload_if_changed();
    size_t vlen = 0;
    const char *v = cfg_file_value(key, &vlen);
    if (v && vlen > 0 && vlen < cap) {
        memcpy(out, v, vlen);
        out[vlen] = 0;
        return 1;
    }
    char prop[128] = {0};
    if (__system_property_get(key, prop) > 0 && prop[0]) {
        snprintf(out, cap, "%s", prop);
        return 1;
    }
    return 0;
}

float hsb_cfg_float(const char *key, float lo, float hi, float dflt) {
    char v[32] = {0};
    if (!cfg_lookup(key, v, sizeof(v))) return dflt;
    char *end = NULL;
    float f = strtof(v, &end);
    if (end == v) return dflt;
    if (!(f >= lo && f <= hi)) return dflt;   /* NaN 也落这里 */
    return f;
}

int hsb_cfg_int(const char *key, int lo, int hi, int dflt) {
    char v[32] = {0};
    if (!cfg_lookup(key, v, sizeof(v))) return dflt;
    char *end = NULL;
    long n = strtol(v, &end, 10);
    if (end == v || n < (long) lo || n > (long) hi) return dflt;
    return (int) n;
}
