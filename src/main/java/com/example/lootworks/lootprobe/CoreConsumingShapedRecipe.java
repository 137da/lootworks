package com.example.lootworks.lootprobe;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * 「消耗整个核心」的有序配方：用于宝箱检测器。
 * <p>
 * 宝藏核心默认会作为剩余物品留在合成格（为了幸运粉尘配方），这里把它改回「直接消耗」。
 * 继承原版 {@link ShapedRecipe}，JSON 格式与原版有序配方完全相同，JEI 和配方书也能照常显示。
 */
public class CoreConsumingShapedRecipe extends ShapedRecipe {

    public CoreConsumingShapedRecipe(ShapedRecipe base) {
        super(base.getGroup(), base.category(), base.pattern, base.getResultItem(null), base.showNotification());
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = super.getRemainingItems(input);
        for (int i = 0; i < input.size(); i++) {
            if (input.getItem(i).is(LootProbeItems.TREASURE_CORE.get())) {
                remaining.set(i, ItemStack.EMPTY); // 核心整个消耗，不归还
            }
        }
        return remaining;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return LootProbeRecipes.CORE_CONSUMING_SHAPED.get();
    }

    /** 序列化器：直接复用原版有序配方的编解码，再包一层 */
    public static class Serializer implements RecipeSerializer<CoreConsumingShapedRecipe> {
        private static final MapCodec<CoreConsumingShapedRecipe> CODEC =
                ShapedRecipe.Serializer.CODEC.xmap(CoreConsumingShapedRecipe::new, recipe -> recipe);
        private static final StreamCodec<RegistryFriendlyByteBuf, CoreConsumingShapedRecipe> STREAM_CODEC =
                ShapedRecipe.Serializer.STREAM_CODEC.map(CoreConsumingShapedRecipe::new, recipe -> recipe);

        @Override
        public MapCodec<CoreConsumingShapedRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CoreConsumingShapedRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
