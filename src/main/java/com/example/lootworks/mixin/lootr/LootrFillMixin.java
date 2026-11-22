// SPDX-License-Identifier: LGPL-3.0
package com.example.lootworks.mixin.lootr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.example.lootworks.LootWorks;
import com.example.lootworks.lootprobe.FateRecord;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import noobanidus.mods.lootr.api.LootrAPI;
import noobanidus.mods.lootr.block.entities.LootrBarrelBlockEntity;
import noobanidus.mods.lootr.block.entities.LootrChestBlockEntity;
import noobanidus.mods.lootr.block.entities.LootrShulkerBlockEntity;

/** Lootr 0.7 的三个容器分别实现填箱，陷阱箱继承普通箱子的实现。 */
@Mixin(value = {LootrChestBlockEntity.class, LootrBarrelBlockEntity.class, LootrShulkerBlockEntity.class}, remap = false)
abstract class LootrFillMixin {
    @Redirect(method = "unpackLootTable(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/Container;Lnet/minecraft/resources/ResourceLocation;J)V",
            at = @At(value = "INVOKE", target = "Lnoobanidus/mods/lootr/api/LootrAPI;getLootSeed(J)J"), require = 0)
    private long lootworks$useFatedSeed(long original, Player player, Container contents, ResourceLocation table, long seed) {
        if (player != null && (Object) this instanceof BlockEntity chest) {
            try {
                Long fated = FateRecord.seedOf(chest, player.getUUID());
                if (fated != null) return fated;
            } catch (RuntimeException error) {
                LootWorks.LOGGER.error("Lootr 开箱读取改命种子出错，本次按原种子开箱", error);
            }
        }
        return LootrAPI.getLootSeed(original);
    }
}
