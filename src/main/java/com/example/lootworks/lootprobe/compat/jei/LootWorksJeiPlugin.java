package com.example.lootworks.lootprobe.compat.jei;

import java.util.List;

import com.example.lootworks.LootWorks;
import com.example.lootworks.lootprobe.LootProbeItems;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * JEI 可选联动插件：仅当客户端安装 JEI 时由 JEI 反射加载，未安装时零依赖。
 */
@JeiPlugin
public final class LootWorksJeiPlugin implements IModPlugin {
    private static final ResourceLocation PLUGIN_UID =
            ResourceLocation.fromNamespaceAndPath(LootWorks.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new WorldInteractionCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(LootProbeItems.SKELETON_KEY.get(), WorldInteractionCategory.RECIPE_TYPE);
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        List<WorldInteractionRecipe> recipes = List.of(
                new WorldInteractionRecipe(
                        ResourceLocation.fromNamespaceAndPath(LootWorks.MODID, "world_interaction/treasure_core"),
                        new ItemStack(Items.CHEST),
                        new ItemStack(LootProbeItems.SKELETON_KEY.get()),
                        new ItemStack(LootProbeItems.TREASURE_CORE.get()),
                        "jei.lootworks.world_interaction.chest_tip",
                        "jei.lootworks.world_interaction.key_tip"),
                new WorldInteractionRecipe(
                        ResourceLocation.fromNamespaceAndPath(LootWorks.MODID, "world_interaction/relic_metal_ingot"),
                        new ItemStack(Items.COPPER_INGOT),
                        new ItemStack(LootProbeItems.TREASURE_CORE.get()),
                        new ItemStack(LootProbeItems.RELIC_METAL_INGOT.get()),
                        "jei.lootworks.world_interaction.copper_tip",
                        "jei.lootworks.world_interaction.core_tip"));
        registration.addRecipes(WorldInteractionCategory.RECIPE_TYPE, recipes);
    }
}
