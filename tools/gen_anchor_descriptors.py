#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 launcher_profiles.h 派生「形状描述符」表（app/src/main/cpp/anchor_descriptors.h）。

## 它解决什么问题
`launcher_profiles.h` 是**每构建一份偏移**：13 个官方桌面包 → 10 张表 → 157 条
锚点行。每次 launcher OTA 都要「抽 APK → --from-so 生成 → 镜像同步 → 校验 → 重编 →
装机 → 真机矩阵」。实测 13 个包（10 个不同 .so）后确认：**偏移每构建独有，形状不是**。

于是原生侧多一条「首次运行按形状枚举」的路（hsb_locate 的第三路），它需要的
**不是偏移**，而是每个锚点的形状语料：
  1) 掩码序言模板（32B，PC 相对立即数位放开）——同一锚点在各构建上高度重复；
  2) 函数长度集合（FDE 的 [start, 下一个起点) 距离）——用于在"同一段序言出现多处"
     时消歧（实测 handle_back_gesture 在 8602 上是 3 处同序言，长度唯一区分）。
本脚本就是把这套语料从**已有的表**里榨出来——所以**不需要维护新数据**：
表还在（偏移路仍是同构建的第一快路径），描述符只是它的派生视图。

## 用法
    python tools/gen_anchor_descriptors.py            # 读/写默认路径
    python tools/gen_anchor_descriptors.py --check    # 只校验产物是否与表同步（CI/测试用）

规则：
- 只收 WANT 里列出的锚点（显式白名单——`action_probe` 故意不收，见下）；
- 模板按掩码去重后按字节序排序（产物稳定，diff 可读）；
- 长度集合去重升序；长度 0（未知）不写入；
- 产物里写明生成命令与"不要手改"。

## 为什么不收 action_probe
它的桩在抑制档下**会改系统判定**（`hsb_stub_actionprobe` 返回改写过的值）。
实测它的序言在 8602/7755 各有 87/85 处命中——形状枚举对它必然是歧义的，
而"猜错"的代价是改变用户的系统手势行为。所以它只走偏移表：表里没有就拒装。
"""
import os
import re
import struct
import sys

HEADER = os.path.join("app", "src", "main", "cpp", "launcher_profiles.h")
OUTPUT = os.path.join("app", "src", "main", "cpp", "anchor_descriptors.h")

# 显式白名单。新增锚点必须同时出现在这里的理由：能进描述符路的锚点必须满足
# "形状在构建间稳定 + 猜错只导致不装、不导致改行为"。当前 16 个锚点全部满足，
# action_probe 例外（见文件头）。
WANT = [
    "try_delegate_back_to_sidebar",
    "on_swipe_start",
    "on_swipe_process",
    "on_back_invoke",
    "on_back_cancelled",
    "handle_back_gesture",
    "on_swipe_stop_direct",
    "init_gesture_type",
    "is_in_bottom_gesture_region",
    "match_region_check",
    "pilfer_pointers",
    "cancel_home_gesture_to_engine",
    "nav_hot_zone",
    "rotate_size",
    "judge_probe",
    "pilfer_probe",
]

# 与 hsb_fn_word_mask / hsb_word_mask 逐条同源的掩码（两处都有测试守着）。
def word_mask(w):
    if (w & 0xFC000000) == 0x94000000: return 0xFC000000
    if (w & 0xFC000000) == 0x14000000: return 0xFC000000
    if (w & 0xFF000010) == 0x54000000: return 0xFF00001F
    if (w & 0x7E000000) == 0x34000000: return 0x7E00001F
    if (w & 0x7E000000) == 0x36000000: return 0x7E00001F
    if (w & 0x9F000000) == 0x90000000: return 0x9F00001F
    if (w & 0x9F000000) == 0x10000000: return 0x9F00001F
    if (w & 0x3B000000) == 0x18000000: return 0xC400001F
    if (w & 0x3B000000) == 0x38000000: return 0xFFC003FF
    return 0xFFFFFFFF

ROW = re.compile(r'\{"([a-z0-9_]+)", (0x[0-9a-f]+), (\d+),\s*\{([^}]*)\}')


def parse_tables(text):
    """header → [(build_label, {anchor: (offset,size,prologue_bytes)})].

    构建标签取 kHsbProfiles[] 里与该表对应的那一行（多标签用 == 合并的只取第一个）。
    """
    blocks = re.split(r"static const HsbLauncherAnchor ", text)[1:]
    tables = {}
    for block in blocks:
        name = re.match(r"([A-Za-z0-9_]+)\[", block).group(1)
        rows = {}
        for n, off, size, pro in ROW.findall(block):
            by = bytes(int(x, 16) for x in pro.replace(" ", "").split(","))
            if len(by) < 32:
                continue
            rows[n] = (int(off, 16), int(size), by)
        tables[name] = rows
    labels = {}
    m = re.search(r"static const HsbLauncherProfile kHsbProfiles\[\] = \{(.*?)\};",
                  text, re.S)
    if m:
        for line in re.findall(r'\{"([^"]+)", (kHsbAnchors_\d+)', m.group(1)):
            labels[line[1]] = line[0].split(" == ")[0].strip()
    return [(labels.get(name, name), rows) for name, rows in tables.items()]


def templates(by):
    words = [struct.unpack_from("<I", by, k)[0] for k in range(0, 32, 4)]
    return tuple(w & word_mask(w) for w in words)


def build(text):
    per = {name: {"templates": set(), "sizes": set()} for name in WANT}
    for label, rows in parse_tables(text):
        for name in WANT:
            if name not in rows:
                continue
            off, size, by = rows[name]
            per[name]["templates"].add(templates(by))
            if size:
                per[name]["sizes"].add(size)
    return per


def emit(per):
    lines = [
        "/* 自动生成，勿手改。",
        "   生成命令（改了 launcher_profiles.h 就重跑）：",
        "     python tools/gen_anchor_descriptors.py",
        "   校验（与 launcher_profiles.h 不同步就非零退出）：",
        "     python tools/gen_anchor_descriptors.py --check",
        "",
        "   这是什么：原生侧「首次运行按形状枚举」的语料（见 hsb_fn_enum.h 与",
        "   hypersidebar_native.c 的 hsb_descriptor_locate）。它**不是**偏移表——",
        "   每条只描述形状：掩码后的 32B 序言模板 + 该锚点出现过的函数长度集合。",
        "   换构建时不需要更新它；只有新出现的形状（save-set 变了）才需要加一行，",
        "   而那时 launcher_profiles.h 本来也要加一张表。",
        "",
        "   为什么不收 action_probe：它的桩会改系统判定（hsb_stub_actionprobe），",
        "   而它的序言在实测构建里有 85-88 处命中 ⇒ 形状枚举必然歧义。它只走偏移表。",
        "*/",
        "#pragma once",
        "",
        '#include "hsb_fn_enum.h"',
        "",
        "typedef struct {",
        "    const char *name;          /* 锚点名 = launcher_profiles.h 里的短名 */",
        "    const uint32_t *templates; /* 掩码序言模板，每模板 8 个 uint32，扁平存放 */",
        "    unsigned template_count;   /* 模板数（templates 数组长度 = 本值 * 8） */",
        "    const uint32_t *sizes;     /* 该锚点在各构建上出现过的函数长度，升序去重 */",
        "    unsigned size_count;",
        "} HsbAnchorDescriptor;",
        "",
    ]
    flat_t, flat_s, index = [], [], []
    for name in WANT:
        tpl = sorted(per[name]["templates"])
        sizes = sorted(per[name]["sizes"])
        if not tpl:
            raise SystemExit(f"锚点 {name} 在 launcher_profiles.h 里没有任何模板"
                             f"（表生成是否漏了它？）")
        tname = "kTemplates_" + name
        sname = "kSizes_" + name
        flat_t.append(f"static const uint32_t {tname}[] = {{")
        for t in tpl:
            vals = ", ".join(f"0x{v:08x}u" for v in t)
            flat_t.append(f"    {vals},  /* 模板：8 个掩码字 */")
        flat_t.append("};")
        flat_t.append("")
        flat_s.append(f"static const uint32_t {sname}[] = {{")
        if sizes:
            flat_s.append("    " + ", ".join(f"{v}u" for v in sizes) + ",")
        else:
            flat_s.append("    0u,  /* 占位：本锚点没有已知长度（C 侧按 size_count=0 跳过尺寸判据） */")
        flat_s.append("};")
        flat_s.append("")
        index.append((name, tname, len(tpl), sname, len(sizes)))
    lines += flat_t + flat_s
    lines.append("static const HsbAnchorDescriptor kAnchorDescriptors[] = {")
    for name, tname, tc, sname, sc in index:
        lines.append(f'    {{"{name}", {tname}, {tc}u, {sname}, {sc}u}},')
    lines.append("};")
    lines.append("")
    lines.append("#define HSB_DESCRIPTOR_COUNT "
                 f"{len(index)}u")
    lines.append("")
    return "\n".join(lines)


def main():
    check = "--check" in sys.argv
    text = open(HEADER, encoding="utf-8").read()
    out = emit(build(text))
    if check:
        if not os.path.isfile(OUTPUT):
            raise SystemExit(f"缺少产物 {OUTPUT}，先跑不带 --check 的生成")
        existing = open(OUTPUT, encoding="utf-8", newline="\n").read()
        if existing != out:
            raise SystemExit(
                f"{OUTPUT} 与 {HEADER} 不同步——重跑 python tools/gen_anchor_descriptors.py")
        print(f"OK: {OUTPUT} 与 {HEADER} 同步")
        return
    with open(OUTPUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print(f"wrote {OUTPUT}")


if __name__ == "__main__":
    main()
