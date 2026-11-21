/*
 * hsb_fn_enum.c —— 函数边界流式枚举（native 锚点解析层「底座」第四层）。
 *
 * 来源（Apache-2.0）：DW_EH_PE 解码与 .eh_frame_hdr 布局取自参考工程
 *   MiuiBackGestureHook 的 miui-home-hyos-native/runtime_profile_resolver.cpp
 *   （DecodeEh / ParseEhFrameHeader 系列）。本仓库首次落地是
 *   hypersidebar_native.c 的 selflocate 段（2026-11-07，只读探针）；这里把它
 *   提成独立可复用模块，并改成**流式**（一条 lookahead，内存 O(1)）。
 * 上游为 Apache License 2.0；本文件是衍生作品，保留来源与许可说明
 * （见 README「第三方组件与许可」）。
 *
 * 不做任何"谁是某个锚点"的业务判断——那在 hypersidebar_native.c 的描述符层。
 *
 * 长度为什么必须滞后一条：FDE 表只给起点，不给长度。第 i 条的长度 = 第 i+1 条
 * 起点 − 第 i 条起点。所以遍历时保留一条待上报条目，读到新起点时把上一条补齐
 * size 再回调；最后一条 size 记 0（长度不可知，调用方必须把它排除在尺寸判据外）。
 */
#include "hsb_fn_enum.h"

#include <string.h>

/* ── DW_EH_PE 解码（只支持实测出现过的格式；未知编码一律拒）────────────── */

static int fn_read(const HsbElfView *v, uintptr_t off, void *dst, size_t n) {
    if (!hsb_elf_contains(v, off, n, PF_R, 0u)) return 0;
    memcpy(dst, v->base + off, n);
    return 1;
}

static int fn_dec(const HsbElfView *v, uint8_t enc, uintptr_t *cursor,
                  uintptr_t datarel, uintptr_t *out) {
    uint8_t fmt = (uint8_t) (enc & 0x0fu);
    uint8_t app = (uint8_t) (enc & 0x70u);
    uintptr_t field = *cursor;
    uintptr_t val = 0u;
    if (fmt == 0x00u) {                       /* absptr */
        uint64_t x = 0;
        if (!fn_read(v, *cursor, &x, 8)) return 0;
        val = (uintptr_t) x;
        *cursor += 8u;
    } else if (fmt == 0x01u) {                /* uleb128 */
        unsigned shift = 0;
        for (unsigned i = 0; i < 10u; i++) {
            uint8_t b = 0;
            if (!fn_read(v, *cursor, &b, 1)) return 0;
            *cursor += 1u;
            val |= (uintptr_t) (b & 0x7fu) << shift;
            if (!(b & 0x80u)) break;
            shift += 7u;
            if (i == 9u) return 0;
        }
    } else if (fmt == 0x02u) {                /* udata2 */
        uint16_t x = 0;
        if (!fn_read(v, *cursor, &x, 2)) return 0;
        val = x;
        *cursor += 2u;
    } else if (fmt == 0x03u) {                /* udata4 */
        uint32_t x = 0;
        if (!fn_read(v, *cursor, &x, 4)) return 0;
        val = x;
        *cursor += 4u;
    } else if (fmt == 0x04u) {                /* udata8 */
        uint64_t x = 0;
        if (!fn_read(v, *cursor, &x, 8)) return 0;
        val = (uintptr_t) x;
        *cursor += 8u;
    } else if (fmt == 0x0bu) {                /* sdata4 */
        int32_t x = 0;
        if (!fn_read(v, *cursor, &x, 4)) return 0;
        val = (uintptr_t) (intptr_t) x;
        *cursor += 4u;
    } else {
        return 0;
    }
    if (app == 0x10u) val += field;            /* pcrel */
    else if (app == 0x30u) val += datarel;     /* datarel */
    else if (app != 0x00u) return 0;           /* textrel 等不支持 */
    *out = val;
    return 1;
}

/* ── 掩码（必须与 hypersidebar_native.c 的 hsb_word_mask 逐条同源）──────── */

uint32_t hsb_fn_word_mask(uint32_t w) {
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

/* ── 表头解析 ────────────────────────────────────────────────────────────── */

typedef struct {
    uintptr_t hdr;      /* .eh_frame_hdr 起点（也是 datarel 基准） */
    uintptr_t href;     /* 表项数组起点 */
    uint32_t count;
    uint8_t table_enc;
} FnHdr;

static int fn_parse_hdr(const HsbElfView *view, FnHdr *out) {
    uint8_t b[4];
    uintptr_t cur, tmp = 0u, cnt = 0u;
    if (view == NULL || out == NULL) return 0;
    if (view->eh_frame_hdr == 0u || view->eh_frame_hdr_size < 12u) return 0;
    if (!fn_read(view, view->eh_frame_hdr, b, 4)) return 0;
    if (b[0] != 1u) return 0;                           /* version */
    cur = view->eh_frame_hdr + 4u;
    if (!fn_dec(view, b[1], &cur, view->eh_frame_hdr, &tmp)) return 0;
    if (!fn_dec(view, b[2], &cur, view->eh_frame_hdr, &cnt)) return 0;
    if (cnt == 0u || cnt > HSB_FN_MAX_FDE) return 0;
    if (b[3] == 0u) return 0;
    /* 表项数组必须整体落在段内：否则逐条解码会在中途撞墙，而那时游标已经改了
     * ⇒ 等于把"半张表"当整张用。 */
    if (!hsb_elf_contains(view, cur, (size_t) cnt * 8u, PF_R, 0u)) return 0;
    out->hdr = view->eh_frame_hdr;
    out->href = cur;
    out->count = (uint32_t) cnt;
    out->table_enc = b[3];
    return 1;
}

/* ── 流式遍历 ────────────────────────────────────────────────────────────── */

int hsb_fn_scan(const HsbElfView *view, HsbFnVisitor visit, void *ctx,
                size_t *visited_out) {
    FnHdr h;
    uintptr_t cur;
    uintptr_t prev = 0u;
    HsbFnEntry pending;
    int have_pending = 0;
    size_t visited = 0u;
    size_t index;
    if (view == NULL || visit == NULL) return 0;
    if (visited_out != NULL) *visited_out = 0u;
    memset(&pending, 0, sizeof(pending));
    if (!fn_parse_hdr(view, &h)) return 0;
    cur = h.href;
    for (index = 0u; index < h.count; ++index) {
        uintptr_t loc = 0u, fde = 0u;
        if (!fn_dec(view, h.table_enc, &cur, h.hdr, &loc)) return 0;
        if (!fn_dec(view, h.table_enc, &cur, h.hdr, &fde)) return 0;
        (void) fde;   /* FDE 自身在 .eh_frame 内的地址，本层不需要 */
        /* 起点必须落在可执行段里：表项地址来自库本身，但"库本身"也可能被损坏
         * 或合成，直接当函数入口去装 inline hook 就是拿不可信输入赌桌面。 */
        if (!hsb_elf_contains(view, loc, 4u, PF_R | PF_X, 0u)) return 0;
        if (index != 0u && loc <= prev) return 0;   /* 必须严格升序（乱序=表畸形） */
        prev = loc;
        if (have_pending) {
            /* 上一条的长度此刻才确定。先补齐再回调，回调里看到的 size 才是真的。 */
            pending.size = loc - pending.start;
            if (!visit(ctx, &pending)) {
                if (visited_out != NULL) *visited_out = visited + 1u;
                return 1;
            }
            visited++;
        }
        /* 本条目入待上报位。32B 序言跨段边界时仍然保留条目（长度信息对
         * "下一个同形状候选"仍有用），只是序言留零——调用方比较时会不命中，
         * 相当于"这个起点形状不可用"，与"不是我们要的函数"同效且更保守。 */
        pending.start = loc;
        pending.size = 0u;
        memset(pending.prologue, 0, sizeof(pending.prologue));
        if (hsb_elf_contains(view, loc, sizeof(pending.prologue),
                             PF_R | PF_X, 0u)) {
            memcpy(pending.prologue, view->base + loc, sizeof(pending.prologue));
        }
        have_pending = 1;
    }
    /* 最后一条：长度不可知 ⇒ size 保持 0。调用方必须把它排除在尺寸判据之外。 */
    if (have_pending) {
        if (!visit(ctx, &pending)) {
            if (visited_out != NULL) *visited_out = visited + 1u;
            return 1;
        }
        visited++;
    }
    if (visited_out != NULL) *visited_out = visited;
    return 1;
}
