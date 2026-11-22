// SPDX-License-Identifier: LGPL-3.0
package com.example.lootworks;

import com.example.lootworks.lootprobe.FateHighlightPayload;
import com.example.lootworks.lootprobe.LootProbeMenus;
import com.example.lootworks.lootprobe.client.ChestDetectorScreen;
import com.example.lootworks.lootprobe.client.LootItemTooltips;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ContainerScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = LootWorks.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LootWorksClient {
    private LootWorksClient() {}

    @SubscribeEvent
    public static void onSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(LootProbeMenus.CHEST_DETECTOR.get(), ChestDetectorScreen::new));
        MinecraftForge.EVENT_BUS.addListener(LootItemTooltips::onTooltip);
        MinecraftForge.EVENT_BUS.addListener(LootWorksClient::renderFatedSlots);
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
}
