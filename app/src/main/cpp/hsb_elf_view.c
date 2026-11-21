/*
 * hsb_elf_view.c —— ELF 只读视图与段/动态表解析（「底座」第一层）。
 *
 * 来源（Apache-2.0）：参考工程 MiuiBackGestureHook
 *   _repos/MiuiBackGestureHook/miui-home-hyos-native/runtime_profile_resolver.cpp
 *   的 ElfView / AddOverflows / Contains / ReadInstruction /
 *   NormalizeDynamicPointer / ParseElf —— 1:1 移植为 C（逐条边界检查保留，见 .h 顶部）。
 *
 * 与上游的唯一取舍：无。本文件不依赖任何本仓库其它模块，只依赖 libc 的 memcpy/
 * memcmp；不 malloc、不写全局状态，纯函数（输入视图 → 判断）。
 */
#include "hsb_elf_view.h"

#include <string.h>

int hsb_elf_add_overflows(uintptr_t left, uintptr_t right) {
    return right > UINTPTR_MAX - left;
}

int hsb_elf_contains(const HsbElfView *view, uintptr_t offset, size_t size,
                     uint32_t required_flags, uint32_t forbidden_flags) {
    uintptr_t end;
    size_t index;
    if (view == NULL || size == 0u ||
            hsb_elf_add_overflows(offset, (uintptr_t) size)) {
        return 0;
    }
    end = offset + (uintptr_t) size;
    for (index = 0u; index < view->load_count; ++index) {
        const HsbLoadSegment *load = &view->loads[index];
        if (offset >= load->start && end <= load->end &&
                (load->flags & required_flags) == required_flags &&
                (load->flags & forbidden_flags) == 0u) {
            return 1;
        }
    }
    return 0;
}

int hsb_elf_read_instruction(const HsbElfView *view, uintptr_t offset,
                             uint32_t *instruction) {
    if (view == NULL || instruction == NULL ||
            !hsb_elf_contains(view, offset, sizeof(*instruction),
                              PF_R | PF_X, 0u)) {
        return 0;
    }
    memcpy(instruction, view->base + offset, sizeof(*instruction));
    return 1;
}

int hsb_elf_normalize_dynamic_pointer(const HsbElfView *view, Elf64_Addr value,
                                      uintptr_t *offset) {
    uintptr_t raw;
    uintptr_t base_address;
    if (view == NULL || offset == NULL) return 0;
    raw = (uintptr_t) value;
    base_address = (uintptr_t) view->base;
    if (raw >= base_address && raw - base_address < view->image_span) {
        *offset = raw - base_address;
        return 1;
    }
    if (raw < view->image_span) {
        *offset = raw;
        return 1;
    }
    return 0;
}

int hsb_elf_parse(const uint8_t *base, HsbElfView *output) {
    Elf64_Ehdr header = {0};
    HsbElfView view = {0};
    const Elf64_Phdr *program_headers;
    uintptr_t headers_end;
    uintptr_t dynamic_offset = 0u;
    size_t dynamic_size = 0u;
    int headers_covered = 0;
    size_t index;
    Elf64_Addr string_table = 0u;
    Elf64_Addr symbol_table = 0u;
    Elf64_Addr jump_relocations = 0u;
    size_t string_table_size = 0u;
    size_t symbol_entry_size = 0u;
    size_t jump_relocations_size = 0u;
    size_t relocation_entry_size = sizeof(Elf64_Rela);
    Elf64_Sxword relocation_kind = 0;
    int terminated = 0;
    size_t dynamic_count;

    if (base == NULL || output == NULL) return 0;

    memcpy(&header, base, sizeof(header));
    /* 只接受"本机能直接用的 64 位小端动态 AArch64 so"：别的形态不但解析没意义，
     * 而且后面的固定宽度结构体拷贝会按错误的布局读。e_phoff/e_phnum 必须让程序头
     * 表落在文件头紧邻的 0x1000 内——超出就不认（否则等于把 p_offset 交给不可信输入）。 */
    if (memcmp(header.e_ident, ELFMAG, SELFMAG) != 0 ||
            header.e_ident[EI_CLASS] != ELFCLASS64 ||
            header.e_ident[EI_DATA] != ELFDATA2LSB ||
            header.e_type != ET_DYN || header.e_machine != EM_AARCH64 ||
            header.e_phentsize != sizeof(Elf64_Phdr) ||
            header.e_phnum == 0u || header.e_phnum > 64u ||
            header.e_phoff > 0x1000u ||
            hsb_elf_add_overflows((uintptr_t) header.e_phoff,
                                  (uintptr_t) header.e_phnum *
                                          sizeof(Elf64_Phdr)) ||
            (uintptr_t) header.e_phoff +
                    (uintptr_t) header.e_phnum * sizeof(Elf64_Phdr) > 0x1000u) {
        return 0;
    }

    view.base = base;
    program_headers = (const Elf64_Phdr *) (const void *)
            (base + (size_t) header.e_phoff);
    headers_end = (uintptr_t) header.e_phoff +
            (uintptr_t) header.e_phnum * sizeof(Elf64_Phdr);
    for (index = 0u; index < header.e_phnum; ++index) {
        Elf64_Phdr program_header = {0};
        memcpy(&program_header, program_headers + index,
               sizeof(program_header));
        if (program_header.p_type == PT_LOAD) {
            HsbLoadSegment segment;
            uintptr_t start;
            uintptr_t end;
            /* 段数超上限 / 空的段 / vaddr+memsz 回绕：三者都说明头不可信，
             * 直接整体放弃（不做"跳过这一段继续"的容错——跳过等于接受了一个
             * 我们没验证过的视图）。 */
            if (view.load_count >= HSB_ELF_MAX_LOAD_SEGMENTS ||
                    program_header.p_memsz == 0u ||
                    hsb_elf_add_overflows((uintptr_t) program_header.p_vaddr,
                                          (uintptr_t) program_header.p_memsz)) {
                return 0;
            }
            start = (uintptr_t) program_header.p_vaddr;
            end = start + (uintptr_t) program_header.p_memsz;
            if (end > HSB_ELF_MAX_IMAGE_SPAN) return 0;
            segment.start = start;
            segment.end = end;
            segment.flags = program_header.p_flags;
            view.loads[view.load_count++] = segment;
            /* 程序头表本身必须被"从 vaddr=0 起的那一段"在文件里覆盖到：
             * 这条是"我们读过的头确实属于这个映像"的自证。 */
            if (start == 0u &&
                    headers_end <= (uintptr_t) program_header.p_filesz) {
                headers_covered = 1;
            }
            if (end > view.image_span) view.image_span = end;
        } else if (program_header.p_type == PT_DYNAMIC) {
            if (program_header.p_memsz == 0u ||
                    program_header.p_memsz > (Elf64_Xword) SIZE_MAX) {
                return 0;
            }
            dynamic_offset = (uintptr_t) program_header.p_vaddr;
            dynamic_size = (size_t) program_header.p_memsz;
        } else if (program_header.p_type == PT_GNU_EH_FRAME) {
            /* 函数边界来源（见 .h）。这里只记录位置，不在这里解析——解析要按需
             * 做（不是每个设备都会走到描述符路），且解析失败必须只影响描述符路，
             * 不能让"这台设备的 .so 恰好没有 FDE 表"变成整个 hook 安装失败。 */
            view.eh_frame_hdr = (uintptr_t) program_header.p_vaddr;
            view.eh_frame_hdr_size = (size_t) program_header.p_filesz;
        }
    }
    if (!headers_covered || view.load_count == 0u ||
            view.image_span == 0u || dynamic_size < sizeof(Elf64_Dyn) ||
            !hsb_elf_contains(&view, dynamic_offset, dynamic_size, PF_R, 0u)) {
        return 0;
    }

    /* 动态表是自描述的：边读边收集，DT_NULL 截断。DT_NULL 没出现（截断/畸形）
     * 就整体拒——否则后面会拿"半个动态表"解出的指针去读字符串表。 */
    dynamic_count = dynamic_size / sizeof(Elf64_Dyn);
    for (index = 0u; index < dynamic_count; ++index) {
        Elf64_Dyn entry = {0};
        memcpy(&entry, view.base + dynamic_offset + index * sizeof(Elf64_Dyn),
               sizeof(entry));
        if (entry.d_tag == DT_NULL) {
            terminated = 1;
            break;
        }
        switch (entry.d_tag) {
            case DT_STRTAB:
                string_table = entry.d_un.d_ptr;
                break;
            case DT_STRSZ:
                string_table_size = (size_t) entry.d_un.d_val;
                break;
            case DT_SYMTAB:
                symbol_table = entry.d_un.d_ptr;
                break;
            case DT_SYMENT:
                symbol_entry_size = (size_t) entry.d_un.d_val;
                break;
            case DT_JMPREL:
                jump_relocations = entry.d_un.d_ptr;
                break;
            case DT_PLTRELSZ:
                jump_relocations_size = (size_t) entry.d_un.d_val;
                break;
            case DT_PLTREL:
                relocation_kind = (Elf64_Sxword) entry.d_un.d_val;
                break;
            case DT_RELAENT:
                relocation_entry_size = (size_t) entry.d_un.d_val;
                break;
            default:
                break;
        }
    }
    /* 条目的"形状"必须先完全对上（SYMENT/RELAENT/PLTREL 全部要精确相等、
     * .rela.plt 大小要整除条目大小），再谈指针归一化：形状不对的话，
     * 后面按 sizeof(Elf64_Rela) 步进就是在按错误的步长扫内存。 */
    if (!terminated || string_table_size == 0u ||
            symbol_entry_size != sizeof(Elf64_Sym) ||
            relocation_kind != (Elf64_Sxword) DT_RELA ||
            relocation_entry_size != sizeof(Elf64_Rela) ||
            jump_relocations_size == 0u ||
            jump_relocations_size % sizeof(Elf64_Rela) != 0u ||
            !hsb_elf_normalize_dynamic_pointer(&view, string_table,
                                               &view.string_table) ||
            !hsb_elf_normalize_dynamic_pointer(&view, symbol_table,
                                               &view.symbol_table) ||
            !hsb_elf_normalize_dynamic_pointer(&view, jump_relocations,
                                               &view.jump_relocations)) {
        return 0;
    }
    view.string_table_size = string_table_size;
    view.symbol_entry_size = symbol_entry_size;
    view.jump_relocations_size = jump_relocations_size;
    view.relocation_entry_size = relocation_entry_size;
    /* 归一化之后**再**证明三块表各自落在只读段内：否则上面归一出的是
     * "视图内偏移"，但那个偏移可能落在不可读段或段外。 */
    if (!hsb_elf_contains(&view, view.string_table, view.string_table_size,
                          PF_R, 0u) ||
            !hsb_elf_contains(&view, view.symbol_table, sizeof(Elf64_Sym),
                              PF_R, 0u) ||
            !hsb_elf_contains(&view, view.jump_relocations,
                              view.jump_relocations_size, PF_R, 0u)) {
        return 0;
    }
    *output = view;
    return 1;
}
