package com.example.lootworks;

import com.example.lootworks.lootprobe.FateHighlightPayload;
import com.example.lootworks.lootprobe.FateResonance;
import com.example.lootworks.lootprobe.LootProbeItems;
import com.example.lootworks.lootprobe.LootProbeMenus;
import com.example.lootworks.lootprobe.client.ChestDetectorScreen;
import com.example.lootworks.lootprobe.client.LootItemTooltips;

import net.minecraft.Util;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = LootWorks.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = LootWorks.MODID, value = Dist.CLIENT)
public class LootWorksClient {
    public LootWorksClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener(LootItemTooltips::onTooltip);
        NeoForge.EVENT_BUS.addListener(LootWorksClient::renderFatedSlots);
    }

    private static void renderFatedSlots(ContainerScreenEvent.Render.Foreground event) {
        var menu = event.getContainerScreen().getMenu();
        long slots = FateHighlightPayload.visibleSlots(menu);
        if (slots == 0L) return;

        GuiGraphics graphics = event.getGuiGraphics();
        int alpha = 193 + (int) (Math.sin(Util.getMillis() / 330.0) * 46); // 轻微呼吸光
        int color = (alpha << 24) | 0xDAA9FF;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 150);
        for (; slots != 0L; slots &= slots - 1) {
            int index = Long.numberOfTrailingZeros(slots);
            if (index >= menu.slots.size()) continue;
            Slot slot = menu.getSlot(index);
            if (slot.isActive() && slot.hasItem()) {
                graphics.renderOutline(slot.x - 1, slot.y - 1, 18, 18, color);
            }
        }
        graphics.pose().popPose();
    }

    @SubscribeEvent
    static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(LootProbeMenus.CHEST_DETECTOR.get(), ChestDetectorScreen::new);
    }

    @SubscribeEvent
    static void registerEffectIcons(RegisterClientExtensionsEvent event) {
        // 共鸣状态复用玩家正在充能的晶胚图标。
        event.registerMobEffect(new IClientMobEffectExtensions() {
            @Override
            public boolean renderGuiIcon(MobEffectInstance instance, Gui gui, GuiGraphics graphics, int x, int y, float z, float alpha) {
                graphics.renderFakeItem(LootProbeItems.UNCHARGED_DESTINED_CRYSTAL.get().getDefaultInstance(), x + 4, y + 4);
                return true;
            }

            @Override
            public boolean renderInventoryIcon(MobEffectInstance instance, EffectRenderingInventoryScreen<?> screen, GuiGraphics graphics, int x, int y, int blitOffset) {
                graphics.renderFakeItem(LootProbeItems.UNCHARGED_DESTINED_CRYSTAL.get().getDefaultInstance(), x, y + 7);
                return true;
            }
        }, FateResonance.EFFECT);
    }
}
