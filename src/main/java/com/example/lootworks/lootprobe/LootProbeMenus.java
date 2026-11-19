package com.example.lootworks.lootprobe;

import com.example.lootworks.LootWorks;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class LootProbeMenus {
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, LootWorks.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<ChestDetectorMenu>> CHEST_DETECTOR =
            MENUS.register("chest_detector", () -> new MenuType<>(ChestDetectorMenu::client, FeatureFlags.DEFAULT_FLAGS));

    private LootProbeMenus() {}

    /** 注册宝箱检测器菜单。 */
    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
