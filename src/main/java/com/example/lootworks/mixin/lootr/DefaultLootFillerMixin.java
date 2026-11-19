package com.example.lootworks.mixin.lootr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.example.lootworks.LootWorks;
import com.example.lootworks.lootprobe.FateRecord;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import noobanidus.mods.lootr.common.api.data.DefaultLootFiller;
import noobanidus.mods.lootr.common.api.data.ILootrInfoProvider;

/**
 * Lootr 改命注入：Lootr 默认开着 randomise_seed，每个玩家开箱时都用 ThreadLocalRandom 现掷种子，
 * 直接往箱子里写种子无效。这里改写 {@code LootrAPI.getLootSeed} 的返回值：
 * 开箱玩家在这个箱子上有改命记录时，换成他的命运种子；否则保持 Lootr 原样。
 * <p>
 * 只在装了 Lootr 时加载（见 {@link com.example.lootworks.mixin.LootWorksMixinPlugin}）。
 */
@Mixin(value = DefaultLootFiller.class, remap = false)
public abstract class DefaultLootFillerMixin {
    @ModifyExpressionValue(method = "unpackLootTable",
            at = @At(value = "INVOKE", target = "Lnoobanidus/mods/lootr/common/api/LootrAPI;getLootSeed(J)J"),
            require = 0) // Lootr 改版后注入点对不上时不崩游戏，只是 Lootr 箱子的改命失效（启动日志里会有 mixin 警告）
    private long lootworks$useFatedSeed(long original, ILootrInfoProvider provider, Player player, Container container) {
        try {
            Level level = provider.getInfoLevel();
            if (level == null || player == null) return original;
            BlockEntity chest = level.getBlockEntity(provider.getInfoPos()); // 改命记录挂在箱子方块实体上
            if (chest == null) return original;
            Long fated = FateRecord.of(chest).seedOf(player.getUUID());
            return fated != null ? fated : original;
        } catch (RuntimeException error) {
            // 出错时退回 Lootr 原本的种子：这次开箱不带改命，但箱子照常能开
            LootWorks.LOGGER.error("Lootr 开箱读取改命种子出错，本次按原种子开箱", error);
            return original;
        }
    }
}
