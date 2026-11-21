/*
 * hsb_fn_enum.h —— 函数边界流式枚举（native 锚点解析层「底座」第四层）。
 *
 * 为什么需要这一层：`launcher_profiles.h` 的 10 张表是**每构建一份偏移**，每次
 * launcher OTA 都要「抽 APK → 生成 → 镜像同步 → 校验 → 重编 → 装机 → 真机矩阵」。
 * 实测 13 个官方桌面包（10 个不同 .so）后确认：**偏移是每构建独有的，但函数形状不是**。
 * 于是正确做法是「形状描述符 + 首次运行枚举」，而不是「预先备好数张表」。
 * 本层提供枚举所需的唯一事实：**每个真实函数的 [start, size) 与前 32 字节**。
 *
 * 来源只能是 PT_GNU_EH_FRAME 指向的 .eh_frame_hdr：
 *   - dynsym 里 2105 条全是 C ABI 导入名，没有业务符号（10 个构建实测一致）；
 *   - 节头表在 e_shoff、超出所有 PT_LOAD ⇒ 运行期读不到；
 *   - .gnu_debugdata 里虽有完整 symtab，但那是 LZMA2 压缩的 5.35 MB，解压要3x
 *     ~35ms 且要嵌完整解码器——不适合放在 fail-closed 的原生路径。
 * 实测 10 个构建：23010–23632 个 FDE，每个目标锚点都落在某个 FDE 起点上。
 *
 * 为什么是**流式**（visitor）而不是先建表：全表 23k 条 × 16B = 368 KB 静态内存，
 * 而调用方真正要的只是"17 个锚点的候选各≤8 个"。一次遍历、O(1) 内存即可，
 * 且天然按地址升序——于是"下一条起点 − 本起点"就是本条的长度上界，不需要二次遍历。
 *
 * 安全边界（每一条都不可省，理由同 hsb_elf_view.h）：
 *   - 所有读都经 hsb_elf_contains（段内 + 权限）。
 *   - FDE 数量封顶（HSB_FN_MAX_FDE）；超了说明表不可信 ⇒ 整体失败，不截断后继续
 *     （截断会让"唯一命中"变成假的）。
 *   - 每条表项的解码都走 DW_EH_PE 白名单（只认实际出现过的格式）；未知编码 ⇒ 拒。
 *   - 起点必须落在可执行段内，且严格升序；任一违反 ⇒ 整体失败（不跳过继续）。
 *   - 不 malloc、无全局状态：内存全由调用方给（本层只回调）。
 */
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "hsb_elf_view.h"

/* FDE 数量上限（10 个实测构建的最大值 23632，留约 2.7 倍余量）。
 * 超限 ⇒ 视为畸形表，整体失败（见头注释）。 */
#define HSB_FN_MAX_FDE 65536u

/* 一条枚举结果。
 * start = 函数起点（视图内偏移，相对 load bias）。
 * size  = 到下一个 FDE 起点的距离，是**上界**：Rust 的冷热分片会让不连续片段
 *         相邻，因此不能假设 [start, start+size) 是紧凑的单一函数体。
 *         最后一条 FDE 没有下一个起点 ⇒ size == 0（长度不可知）。
 * prologue = 该起点的前 32 字节（小端 8 个 uint32），调用方按需做掩码比较。
 *         不保证 32 字节全在可执行段内 ⇒ 越界时 visitor 不会被调用。 */
typedef struct {
    uintptr_t start;
    uintptr_t size;
    uint32_t prologue[8];
} HsbFnEntry;

/* visitor 返回 0 = 调用方要求提前停止（仍然算解析成功）。
 * 返回非 0 = 继续。ctx 由调用方自定。 */
typedef int (*HsbFnVisitor)(void *ctx, const HsbFnEntry *entry);

/* 遍历 .eh_frame_hdr 描述的全部函数（按地址升序）。
 * 返回 1 = 遍历完成（*visited_out 为回调次数），0 = 本库没有该表 / 表畸形 /
 * 任一解码失败（调用方必须当"没有描述符路"处理，不能当"没有函数"）。
 * visitor 返回 0 提前停止时返回 1，*visited_out 为已回调次数。 */
int hsb_fn_scan(const HsbElfView *view, HsbFnVisitor visit, void *ctx,
                size_t *visited_out);

/* 把 32B 起点字节按"跨构建稳定位"做掩码（PC 相对立即数一律放开）。
 * 与 hypersidebar_native.c 的 hsb_word_mask 必须逐条同源——两处都有测试守着
 * （NativeSigscanDisambiguationTest / NativeDescriptorCorpusTest），
 * 改一处必须改另一处。 */
uint32_t hsb_fn_word_mask(uint32_t word);
