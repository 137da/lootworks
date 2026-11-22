package com.example.lootworks.lootprobe;

import com.example.lootworks.LootWorks;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

public final class LootProbeMenus {
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, LootWorks.MOD_ID);

    public static final RegistryObject<MenuType<ChestDetectorMenu>> CHEST_DETECTOR =
            MENUS.register("chest_detector", () -> new MenuType<>(ChestDetectorMenu::client, FeatureFlags.DEFAULT_FLAGS));

    private LootProbeMenus() {}

    /** 注册宝箱检测器菜单。 */
    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
