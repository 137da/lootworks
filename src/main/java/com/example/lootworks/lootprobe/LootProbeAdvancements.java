package com.example.lootworks.lootprobe;

import com.example.lootworks.LootWorks;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * 宝箱探秘玩法的进度辅助类：集中管理服务端检查与手动授予，避免各处重复查找进度管理器。
 */
final class LootProbeAdvancements {
    private LootProbeAdvancements() {}

    static final ResourceLocation RELIC_METAL_INGOT = ResourceLocation.fromNamespaceAndPath(
            LootWorks.MODID, "loot_probe/relic_metal_ingot"); // 「以铜承泽」进度：记录是否曾拥有过遗物金属锭
    static final ResourceLocation REWRITE_FATE = ResourceLocation.fromNamespaceAndPath(
            LootWorks.MODID, "loot_probe/rewrite_fate");       // 「逆转命运」挑战进度
    static final String REWRITE_FATE_CRITERION = "rewrite_fate"; // rewrite_fate.json 中的 impossible 条件名
    static final ResourceLocation ANCHOR_FATE = ResourceLocation.fromNamespaceAndPath(
            LootWorks.MODID, "loot_probe/anchor_fate"); // 「锚定命运」挑战进度
    static final String ANCHOR_FATE_CRITERION = "anchor_fate"; // 首次用命定水晶成功改命

    /** 玩家是否已完成指定进度；进度被数据包移除时保守返回 false。 */
    static boolean isDone(ServerPlayer player, ResourceLocation id) {
        AdvancementHolder holder = player.server.getAdvancements().get(id);
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    /** 授予指定进度的条件。 */
    static void award(ServerPlayer player, ResourceLocation id, String criterion) {
        AdvancementHolder holder = player.server.getAdvancements().get(id);
        if (holder != null) {
            player.getAdvancements().award(holder, criterion);
        }
    }
}
