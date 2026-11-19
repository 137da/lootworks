package com.example.lootworks.lootprobe.compat.jei;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * JEI 展示用的世界交互配方数据。
 *
 * @param id             配方唯一标识
 * @param input          左侧输入原料（箱子或铜锭）
 * @param catalyst       箭头上方展示的触发物（万能钥匙或宝藏核心）
 * @param output         右侧产物
 * @param inputTipKey    输入槽悬停提示的语言键
 * @param catalystTipKey 触发物与箭头悬停提示的语言键
 */
public record WorldInteractionRecipe(
        ResourceLocation id,
        ItemStack input,
        ItemStack catalyst,
        ItemStack output,
        String inputTipKey,
        String catalystTipKey) {
}
