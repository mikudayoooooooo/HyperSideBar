/*
 * ELF 只读视图 + PT_LOAD/PT_DYNAMIC 解析（native 结构校验层「底座」）。
 *
 * 来源（Apache-2.0）：参考工程 MiuiBackGestureHook 的
 *   _repos/MiuiBackGestureHook/miui-home-hyos-native/runtime_profile_resolver.cpp
 * 逐检查点 1:1 移植为 C 的函数与类型：
 *   ElfView（结构） / AddOverflows / Contains / ReadInstruction /
 *   NormalizeDynamicPointer / ParseElf
 * 上游为 Apache License 2.0；本文件是衍生作品，保留来源与许可说明
 * （见 README「第三方组件与许可」）。与之配套的还有 hsb_import_got.{h,c}
 * 与 hsb_a64_decode.{h,c}。
 *
 * 层次位置：「底座」的第一层，也是最底下的一层。本文件只回答一个问题——
 * "给定偏移处的 [offset, offset+size) 是否落在这个 ELF 的某个已映射段内、
 * 且段权限满足要求"。它不做任何业务判定（"谁是侧边手势函数"之类的谓词留在
 * 上游 launcher anchor 表与 hypersidebar_native.c）。上一层的分工：
 *   hsb_import_got.c —— 用 dynsym/dynstr/.rela.plt 把"符号名 → GOT 槽"解出来；
 *   hsb_a64_decode.c —— 在已经证明可读/可执行的字节上解码 A64 指令（adrp/add/ldr/
 *                       bl/PLT 桩），从而证明"这个函数体确实调用了那个导入"。
 *
 * 为什么这些边界检查一个字都不能省：本视图的 base 来自 dl_iterate_phdr
 * 命中的**被 ROM 映射进桌面进程的库/APK**（如 libapp_launcher.so），我们只拿到
 * 首地址，**不持有映射长度**。上游那条链上任何一处少一次 Contains，就等于按一个
 * 越界偏移去 memcpy / 读指令——踩到未映射页的那一瞬间是 SIGSEGV，用户看到的是
 * "模块把桌面搞崩了"。所以本文件的每个入口都先证明范围落在某个 PT_LOAD 段内且
 * 该段的 p_flags 含要求位（读 / 执行）、不含禁止位（如"可写"），再碰那个字节：
 *   1) Contains —— 段内 + 权限双重证明，size==0 与 offset+size 回绕都要拒；
 *   2) AddOverflows —— 所有 `a + b` 之前先证明不回绕（回绕过的范围检查是假证明）；
 *   3) ParseElf —— 只认 ELFCLASS64/ELFDATA2LSB/ET_DYN/EM_AARCH64 且程序头表
 *      紧跟在文件头后 0x1000 内并被第一个 PT_LOAD 覆盖（否则 p_offset 取自
 *      不可信来源）；段数/映像跨度/MaxLoadSegments 全部设上限，防止畸形头把
 *      解析拖进未映射区或写爆栈上数组；
 *   4) NormalizeDynamicPointer —— 动态表里的指针可能是"绝对地址"（ET_DYN 运行时）
 *      或"文件内偏移"（映射前的原始值），两者都必须落回 [0, image_span) 才算数，
 *      否则把 ROM 的任意地址当成段内偏移去读。
 * 每一条都在源工程里被真机验证过（它靠这些检查在 Android 17 桌面上 fail-closed）。
 * 移植时**没有删除、没有放宽、没有合并**任何一条。
 */
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <elf.h>

/* 上限（与上游 constexpr 同值）：16 个 PT_LOAD 足够覆盖任何正常 so/APK；
 * 映像跨度 64MB 是本机桌面 so 量级的一个上界，超了就是解析进了野指针。 */
#define HSB_ELF_MAX_LOAD_SEGMENTS 16u
#define HSB_ELF_MAX_IMAGE_SPAN ((uintptr_t) 0x4000000u)

/* 一个 PT_LOAD 段在「文件内偏移坐标系」里的区间 [start, end) 与其 p_flags。 */
typedef struct {
    uintptr_t start;
    uintptr_t end;
    uint32_t flags;
} HsbLoadSegment;

/* ELF 只读视图。base = 库首地址（映射后的运行时地址），其余字段全是
 * **相对 base 的文件内偏移**，所以本结构的生命周期比映射本身还弱：
 * 调用方必须保证 base 指向的映射在视图使用期间有效（我们只读，不锁）。 */
typedef struct {
    const uint8_t *base;
    HsbLoadSegment loads[HSB_ELF_MAX_LOAD_SEGMENTS];
    size_t load_count;
    uintptr_t image_span;      /* 所有 PT_LOAD 的 end 最大值 */
    uintptr_t string_table;    /* DT_STRTAB，已归一化为段内偏移 */
    size_t string_table_size;  /* DT_STRSZ */
    uintptr_t symbol_table;    /* DT_SYMTAB，已归一化为段内偏移 */
    size_t symbol_entry_size;  /* DT_SYMENT，必须 == sizeof(Elf64_Sym) */
    uintptr_t jump_relocations;/* DT_JMPREL，已归一化为段内偏移 */
    size_t jump_relocations_size; /* DT_PLTRELSZ，必须整除 sizeof(Elf64_Rela) */
    size_t relocation_entry_size; /* DT_RELAENT，必须 == sizeof(Elf64_Rela) */
    /* PT_GNU_EH_FRAME 指向的 .eh_frame_hdr（视图内偏移）。0 = 本库没有这张表。
     * 它是**函数边界**的唯一可运行时来源：Rust 桌面 .so 剥掉了节头与 dynsym 里的
     * 业务符号（实测 10 个构建的 dynsym 只有 C ABI 导入名），但程序头表留在映射里，
     * 于是 FDE 表的"起点+下一个起点"就是每个真实函数的 [start, size)。
     * 用途见 hypersidebar_native.c 的 hsb_descriptor_locate（锚点按形状枚举）。
     * 注意：节头表在 e_shoff 处、超出所有 PT_LOAD ⇒ 运行期读不到，只能走程序头。 */
    uintptr_t eh_frame_hdr;    /* PT_GNU_EH_FRAME 的 p_vaddr */
    size_t eh_frame_hdr_size;  /* PT_GNU_EH_FRAME 的 p_filesz；0 = 缺 */
} HsbElfView;

/* left + right 是否回绕（uintptr_t）。任何范围检查的前置。 */
int hsb_elf_add_overflows(uintptr_t left, uintptr_t right);

/* [offset, offset+size) 是否完整落在一个满足权限要求的 PT_LOAD 段内。
 * required_flags 必须全部置位，forbidden_flags 必须全部为空（例如查只读数据要
 * 求 PF_R 且禁止 PF_W）。size==0 一律拒（"空区间在段内"这种真值没有意义，
 * 而且上游把它当作解析失败信号）。 */
int hsb_elf_contains(const HsbElfView *view, uintptr_t offset, size_t size,
                     uint32_t required_flags, uint32_t forbidden_flags);

/* 读一条指令（4 字节，PF_R|PF_X）。失败时不写 *instruction。 */
int hsb_elf_read_instruction(const HsbElfView *view, uintptr_t offset,
                             uint32_t *instruction);

/* 把动态表里的指针（可能是运行时绝对地址，也可能是映射前偏移）归一化成
 * 视图内的偏移。两种形态都不成立即失败（fail-closed）。 */
int hsb_elf_normalize_dynamic_pointer(const HsbElfView *view, Elf64_Addr value,
                                      uintptr_t *offset);

/* 解析 ELF 头 + 程序头 + 动态表，填充 *output。整函数 fail-closed：
 * 任一结构不符合预期就返回 0，且 *output 保持未定义（调用方不得使用）。 */
int hsb_elf_parse(const uint8_t *base, HsbElfView *output);
