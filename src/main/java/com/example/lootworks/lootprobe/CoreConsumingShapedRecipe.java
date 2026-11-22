package com.example.lootworks.lootprobe;

import com.google.gson.JsonObject;

import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
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
        super(base.getId(), base.getGroup(), base.category(), base.getWidth(), base.getHeight(),
                base.getIngredients(), base.getResultItem(null), base.showNotification());
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingContainer input) {
        NonNullList<ItemStack> remaining = super.getRemainingItems(input);
        for (int i = 0; i < input.getContainerSize(); i++) {
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
        @Override
        public CoreConsumingShapedRecipe fromJson(ResourceLocation id, JsonObject json) {
            return new CoreConsumingShapedRecipe(RecipeSerializer.SHAPED_RECIPE.fromJson(id, json));
        }

        @Override
        public CoreConsumingShapedRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return new CoreConsumingShapedRecipe(RecipeSerializer.SHAPED_RECIPE.fromNetwork(id, buffer));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, CoreConsumingShapedRecipe recipe) {
            RecipeSerializer.SHAPED_RECIPE.toNetwork(buffer, recipe);
        }
    }
}
