package com.example.lootworks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.lootworks.lootprobe.LootSimulator;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;

/**
 * 探险地图函数（沉船、海底废墟的藏宝图）在模拟时短路。
 * <p>
 * 原版每次执行都会真实搜索最近的结构（单次可达上百毫秒），并往存档写一张新地图；
 * 模拟开箱成百上千次就会卡服、刷出大量 map_*.dat。模拟只关心「出不出地图」，
 * 所以直接给一张已填充地图，不搜索、不落盘。玩家真实开箱时标记为假，行为不变。
 */
@Mixin(ExplorationMapFunction.class)
public abstract class ExplorationMapFunctionMixin {
    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void lootworks$skipWhileSimulating(ItemStack stack, LootContext context,
                                             CallbackInfoReturnable<ItemStack> cir) {
        if (LootSimulator.isSimulating() && stack.is(Items.MAP)) {
            cir.setReturnValue(new ItemStack(Items.FILLED_MAP, stack.getCount()));
        }
    }
}
