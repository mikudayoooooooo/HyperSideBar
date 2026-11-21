/* 自动生成，勿手改。
   生成命令（改了 launcher_profiles.h 就重跑）：
     python tools/gen_anchor_descriptors.py
   校验（与 launcher_profiles.h 不同步就非零退出）：
     python tools/gen_anchor_descriptors.py --check

   这是什么：原生侧「首次运行按形状枚举」的语料（见 hsb_fn_enum.h 与
   hypersidebar_native.c 的 hsb_descriptor_locate）。它**不是**偏移表——
   每条只描述形状：掩码后的 32B 序言模板 + 该锚点出现过的函数长度集合。
   换构建时不需要更新它；只有新出现的形状（save-set 变了）才需要加一行，
   而那时 launcher_profiles.h 本来也要加一张表。

   为什么不收 action_probe：它的桩会改系统判定（hsb_stub_actionprobe），
   而它的序言在实测构建里有 85-88 处命中 ⇒ 形状枚举必然歧义。它只走偏移表。
*/
#pragma once

#include "hsb_fn_enum.h"

typedef struct {
    const char *name;          /* 锚点名 = launcher_profiles.h 里的短名 */
    const uint32_t *templates; /* 掩码序言模板，每模板 8 个 uint32，扁平存放 */
    unsigned template_count;   /* 模板数（templates 数组长度 = 本值 * 8） */
    const uint32_t *sizes;     /* 该锚点在各构建上出现过的函数长度，升序去重 */
    unsigned size_count;
} HsbAnchorDescriptor;

static const uint32_t kTemplates_try_delegate_back_to_sidebar[] = {
    0xd10183ffu, 0xa9037bfdu, 0xf90023f5u, 0xa9054ff4u, 0x9100c3fdu, 0x90000008u, 0xf940f108u, 0xf244051fu,  /* 模板：8 个掩码字 */
    0xd10183ffu, 0xa9037bfdu, 0xf90023f5u, 0xa9054ff4u, 0x9100c3fdu, 0x90000008u, 0xf941b108u, 0xf244051fu,  /* 模板：8 个掩码字 */
    0xd10183ffu, 0xa9037bfdu, 0xf90023f5u, 0xa9054ff4u, 0x9100c3fdu, 0x90000008u, 0xf942a908u, 0xf244051fu,  /* 模板：8 个掩码字 */
    0xd10183ffu, 0xa9037bfdu, 0xf90023f5u, 0xa9054ff4u, 0x9100c3fdu, 0x90000008u, 0xf943f108u, 0xf244051fu,  /* 模板：8 个掩码字 */
    0xd10183ffu, 0xa9037bfdu, 0xf90023f5u, 0xa9054ff4u, 0x9100c3fdu, 0x90000008u, 0xf945b108u, 0xf244051fu,  /* 模板：8 个掩码字 */
    0xd10183ffu, 0xa9037bfdu, 0xf90023f5u, 0xa9054ff4u, 0x9100c3fdu, 0x94000000u, 0xaa0003f3u, 0xaa0003f5u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_on_swipe_start[] = {
    0xd10343ffu, 0xfd0043e8u, 0xa9097bfdu, 0xa90a5ff8u, 0xa90b57f6u, 0xa90c4ff4u, 0x910243fdu, 0x90000017u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_on_swipe_process[] = {
    0xd10483ffu, 0x6d0a2bebu, 0x6d0b23e9u, 0xa90c7bfdu, 0xf9006bfcu, 0xa90e67fau, 0xa90f5ff8u, 0xa91057f6u,  /* 模板：8 个掩码字 */
    0xd104c3ffu, 0xfd005becu, 0x6d0babebu, 0x6d0ca3e9u, 0xa90dfbfdu, 0xf90077fcu, 0xa90f67fau, 0xa9105ff8u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_on_back_invoke[] = {
    0xd10403ffu, 0xa90a7bfdu, 0xa90b6ffcu, 0xa90c67fau, 0xa90d5ff8u, 0xa90e57f6u, 0xa90f4ff4u, 0x910283fdu,  /* 模板：8 个掩码字 */
    0xd10483ffu, 0xa90c7bfdu, 0xa90d6ffcu, 0xa90e67fau, 0xa90f5ff8u, 0xa91057f6u, 0xa9114ff4u, 0x910303fdu,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_on_back_cancelled[] = {
    0xd102c3ffu, 0xa9087bfdu, 0xa90957f6u, 0xa90a4ff4u, 0x910203fdu, 0xf9400414u, 0xaa0003f3u, 0xaa1403e0u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_handle_back_gesture[] = {
    0x6db82bebu, 0x6d0123e9u, 0xa9027bfdu, 0xa9036ffcu, 0xa90467fau, 0xa9055ff8u, 0xa90657f6u, 0xa9074ff4u,  /* 模板：8 个掩码字 */
    0xfc0003eeu, 0x6d0133edu, 0x6d022bebu, 0x6d0323e9u, 0xa9047bfdu, 0xa9056ffcu, 0xa90667fau, 0xa9075ff8u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_on_swipe_stop_direct[] = {
    0xd101c3ffu, 0xfd0023e8u, 0xa904fbfdu, 0xf9002ff5u, 0xa9064ff4u, 0x910123fdu, 0x1e204008u, 0x90000008u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_init_gesture_type[] = {
    0xd10303ffu, 0xfd0033e8u, 0xa9077bfdu, 0xa90867fau, 0xa9095ff8u, 0xa90a57f6u, 0xa90b4ff4u, 0x9101c3fdu,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_is_in_bottom_gesture_region[] = {
    0xd100c3ffu, 0xa9017bfdu, 0xf90013f3u, 0x910043fdu, 0x91040013u, 0x085ffe68u, 0x34000008u, 0x52800028u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_match_region_check[] = {
    0x1e380008u, 0x36000001u, 0xb9403009u, 0x6b08013fu, 0x5400000cu, 0xb9403809u, 0x6b09011fu, 0x5400000au,  /* 模板：8 个掩码字 */
    0x6dbc23e9u, 0xa9017bfdu, 0xf90013f5u, 0xa9034ff4u, 0x910043fdu, 0x1e204028u, 0x1e204009u, 0xaa0003f3u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_pilfer_pointers[] = {
    0x34000000u, 0xd10183ffu, 0xa9047bfdu, 0xf9002bf3u, 0x910103fdu, 0x90000008u, 0xaa0003f3u, 0xf947b908u,  /* 模板：8 个掩码字 */
    0xd10143ffu, 0xa9037bfdu, 0xa9044ff4u, 0x9100c3fdu, 0xf9406014u, 0x91004288u, 0x08dffd08u, 0x34000008u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_cancel_home_gesture_to_engine[] = {
    0xd10383ffu, 0xa90b7bfdu, 0xa90c57f6u, 0xa90d4ff4u, 0x9102c3fdu, 0x3945f409u, 0x528003e8u, 0xa90023e1u,  /* 模板：8 个掩码字 */
    0xd10383ffu, 0xa90b7bfdu, 0xa90c57f6u, 0xa90d4ff4u, 0x9102c3fdu, 0x39461009u, 0x528003e8u, 0xa90023e1u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_nav_hot_zone[] = {
    0xd103c3ffu, 0x6d0933edu, 0x6d0a2bebu, 0x6d0b23e9u, 0xa90c7bfdu, 0xf9006bf5u, 0xa90e4ff4u, 0x910303fdu,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_rotate_size[] = {
    0xd102c3ffu, 0xa9067bfdu, 0xa90767fau, 0xa9085ff8u, 0xa90957f6u, 0xa90a4ff4u, 0x910183fdu, 0xaa0003f3u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_judge_probe[] = {
    0xd10483ffu, 0xfd0053ecu, 0x6d0b2bebu, 0x6d0c23e9u, 0xa90d7bfdu, 0xa90e67fcu, 0xa90f5ff8u, 0xa91057f6u,  /* 模板：8 个掩码字 */
};

static const uint32_t kTemplates_pilfer_probe[] = {
    0xd101c3ffu, 0xa9047bfdu, 0xa90557f6u, 0xa9064ff4u, 0x910103fdu, 0xf9403408u, 0x90000016u, 0x91004113u,  /* 模板：8 个掩码字 */
    0xd101c3ffu, 0xa9047bfdu, 0xa90557f6u, 0xa9064ff4u, 0x910103fdu, 0xf9403414u, 0x91004288u, 0x08dffd08u,  /* 模板：8 个掩码字 */
    0xd101c3ffu, 0xa9047bfdu, 0xf9002bf5u, 0xa9064ff4u, 0x910103fdu, 0x90000014u, 0x91330294u, 0xaa0003f3u,  /* 模板：8 个掩码字 */
};

static const uint32_t kSizes_try_delegate_back_to_sidebar[] = {
    188u, 284u,
};

static const uint32_t kSizes_on_swipe_start[] = {
    1500u, 1504u,
};

static const uint32_t kSizes_on_swipe_process[] = {
    3308u, 3380u,
};

static const uint32_t kSizes_on_back_invoke[] = {
    1256u, 1476u,
};

static const uint32_t kSizes_on_back_cancelled[] = {
    456u,
};

static const uint32_t kSizes_handle_back_gesture[] = {
    8364u, 8448u, 8484u, 8776u,
};

static const uint32_t kSizes_on_swipe_stop_direct[] = {
    276u,
};

static const uint32_t kSizes_init_gesture_type[] = {
    1012u, 1076u,
};

static const uint32_t kSizes_is_in_bottom_gesture_region[] = {
    168u,
};

static const uint32_t kSizes_match_region_check[] = {
    344u, 504u,
};

static const uint32_t kSizes_pilfer_pointers[] = {
    140u, 224u,
};

static const uint32_t kSizes_cancel_home_gesture_to_engine[] = {
    532u,
};

static const uint32_t kSizes_nav_hot_zone[] = {
    1024u,
};

static const uint32_t kSizes_rotate_size[] = {
    1276u,
};

static const uint32_t kSizes_judge_probe[] = {
    892u,
};

static const uint32_t kSizes_pilfer_probe[] = {
    364u, 456u, 460u,
};

static const HsbAnchorDescriptor kAnchorDescriptors[] = {
    {"try_delegate_back_to_sidebar", kTemplates_try_delegate_back_to_sidebar, 6u, kSizes_try_delegate_back_to_sidebar, 2u},
    {"on_swipe_start", kTemplates_on_swipe_start, 1u, kSizes_on_swipe_start, 2u},
    {"on_swipe_process", kTemplates_on_swipe_process, 2u, kSizes_on_swipe_process, 2u},
    {"on_back_invoke", kTemplates_on_back_invoke, 2u, kSizes_on_back_invoke, 2u},
    {"on_back_cancelled", kTemplates_on_back_cancelled, 1u, kSizes_on_back_cancelled, 1u},
    {"handle_back_gesture", kTemplates_handle_back_gesture, 2u, kSizes_handle_back_gesture, 4u},
    {"on_swipe_stop_direct", kTemplates_on_swipe_stop_direct, 1u, kSizes_on_swipe_stop_direct, 1u},
    {"init_gesture_type", kTemplates_init_gesture_type, 1u, kSizes_init_gesture_type, 2u},
    {"is_in_bottom_gesture_region", kTemplates_is_in_bottom_gesture_region, 1u, kSizes_is_in_bottom_gesture_region, 1u},
    {"match_region_check", kTemplates_match_region_check, 2u, kSizes_match_region_check, 2u},
    {"pilfer_pointers", kTemplates_pilfer_pointers, 2u, kSizes_pilfer_pointers, 2u},
    {"cancel_home_gesture_to_engine", kTemplates_cancel_home_gesture_to_engine, 2u, kSizes_cancel_home_gesture_to_engine, 1u},
    {"nav_hot_zone", kTemplates_nav_hot_zone, 1u, kSizes_nav_hot_zone, 1u},
    {"rotate_size", kTemplates_rotate_size, 1u, kSizes_rotate_size, 1u},
    {"judge_probe", kTemplates_judge_probe, 1u, kSizes_judge_probe, 1u},
    {"pilfer_probe", kTemplates_pilfer_probe, 3u, kSizes_pilfer_probe, 3u},
};

#define HSB_DESCRIPTOR_COUNT 16u
