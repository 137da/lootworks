package com.example.lootworks.lootprobe;

import com.example.lootworks.LootWorks;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 宝箱检测功能的配方序列化器注册 */
public final class LootProbeRecipes {
    private LootProbeRecipes() {}

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, LootWorks.MODID); // 配方序列化器注册器

    /** JSON 中写作 "type": "lootworks:core_consuming_shaped" */
    public static final DeferredHolder<RecipeSerializer<?>, CoreConsumingShapedRecipe.Serializer> CORE_CONSUMING_SHAPED =
            SERIALIZERS.register("core_consuming_shaped", CoreConsumingShapedRecipe.Serializer::new);

    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
