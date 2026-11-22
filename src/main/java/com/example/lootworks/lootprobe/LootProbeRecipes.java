package com.example.lootworks.lootprobe;

import com.example.lootworks.LootWorks;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

/** 宝箱检测功能的配方序列化器注册 */
public final class LootProbeRecipes {
    private LootProbeRecipes() {}

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, LootWorks.MOD_ID); // 配方序列化器注册器

    /** JSON 中写作 "type": "lootworks:core_consuming_shaped" */
    public static final RegistryObject<CoreConsumingShapedRecipe.Serializer> CORE_CONSUMING_SHAPED =
            SERIALIZERS.register("core_consuming_shaped", CoreConsumingShapedRecipe.Serializer::new);

    public static void register(IEventBus modEventBus) {
        SERIALIZERS.register(modEventBus);
    }
}
