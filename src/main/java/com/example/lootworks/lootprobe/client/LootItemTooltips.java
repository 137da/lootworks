package com.example.lootworks.lootprobe.client;

import com.example.lootworks.lootprobe.FateResonance;
import com.example.lootworks.lootprobe.LootProbeItems;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * 物品悬浮提示渲染：常驻状态与按 Shift 展开的分层主题配色说明。
 * <p>
 * 保持清晰原字号，通过 RGB 主题配色、斜体风味引言与亮金按键高亮拉开视觉层次。
 */
public final class LootItemTooltips {
    private static final String PREFIX = "tooltip.lootworks.";
    private static final String[] STAGES = {"I", "II", "III"}; // 对应晶胚的三个共鸣阶段
    private static final int[] STAGE_COLORS = {0x7EDCE2, 0xC79BF2, 0xF5C96B}; // 共鸣 I/II/III 渐变色

    private static final int COLOR_MUTED = 0x7A828E;        // 暗灰提示色（未展开引导）
    private static final int COLOR_SHIFT_KEY = 0xF2C96B;    // [Shift] 按键亮金色高亮
    private static final int COLOR_LORE_FATE = 0xC8A2F8;    // 命运系风味引言淡紫色
    private static final int COLOR_LORE_KEY = 0xE5C07B;     // 万能钥匙风味引言古金色
    private static final int COLOR_USAGE = 0xB8C7D9;        // 玩法说明柔和浅蓝灰

    private LootItemTooltips() {}

    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        String material = materialName(stack); // 对应命运材料的翻译键
        boolean key = stack.is(LootProbeItems.SKELETON_KEY.get());
        boolean detector = stack.is(LootProbeItems.CHEST_DETECTOR.get());
        if (material == null && !key && !detector) return;

        var lines = event.getToolTip();
        // 1. 常驻共鸣阶段（按阶段渐进变色）
        if (stack.is(LootProbeItems.UNCHARGED_DESTINED_CRYSTAL.get())) {
            int stageIdx = Math.clamp(FateResonance.stage(stack), 0, STAGES.length - 1);
            lines.add(Component.translatable(PREFIX + "resonance_stage", STAGES[stageIdx])
                    .withStyle(Style.EMPTY.withColor(STAGE_COLORS[stageIdx])));
        }

        // 2. 未按 Shift：暗灰引导 + 亮金 [Shift] 高亮
        if (!Screen.hasShiftDown()) {
            Component shiftBadge = Component.literal("[Shift]")
                    .withStyle(Style.EMPTY.withColor(COLOR_SHIFT_KEY));
            lines.add(Component.translatable(PREFIX + "shift", shiftBadge)
                    .withStyle(Style.EMPTY.withColor(COLOR_MUTED)));
            return;
        }

        // 3. 按住 Shift 展开：风味引言用主题色斜体，玩法说明用清晰正体
        if (key) {
            lines.add(lore(PREFIX + "skeleton_key.hint", COLOR_LORE_KEY));
            lines.add(usage(PREFIX + "skeleton_key.usage"));
        } else if (detector) {
            lines.add(usage(PREFIX + "chest_detector"));
        } else {
            lines.add(lore(PREFIX + material + ".lore", COLOR_LORE_FATE));
            if (!stack.is(LootProbeItems.DESTINED_CRYSTAL.get())) {
                lines.add(usage(PREFIX + material + ".use"));
            }
        }
    }

    private static Component lore(String key, int color) {
        return Component.translatable(key).withStyle(Style.EMPTY.withColor(color).withItalic(true));
    }

    private static Component usage(String key) {
        return Component.translatable(key).withStyle(Style.EMPTY.withColor(COLOR_USAGE));
    }

    private static String materialName(ItemStack stack) {
        if (stack.is(LootProbeItems.LUCKY_DUST.get())) return "fate_dust";
        if (stack.is(LootProbeItems.FATE_CRYSTAL.get())) return "fate_crystal";
        if (stack.is(LootProbeItems.UNCHARGED_DESTINED_CRYSTAL.get())) return "uncharged_destined_crystal";
        if (stack.is(LootProbeItems.DESTINED_CRYSTAL.get())) return "destined_crystal";
        return null;
    }
}
