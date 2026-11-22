// SPDX-License-Identifier: LGPL-3.0
package com.example.lootworks.lootprobe.client;

import com.example.lootworks.lootprobe.ChestDetectorMenu;
import com.example.lootworks.lootprobe.ChestDetectorResultPayload;
import net.minecraft.client.Minecraft;

public final class ChestDetectorClientPackets {
    private ChestDetectorClientPackets() {}

    public static void receive(ChestDetectorResultPayload payload) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof ChestDetectorMenu menu) menu.receive(payload);
    }
}
