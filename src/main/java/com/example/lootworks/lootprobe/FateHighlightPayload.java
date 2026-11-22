package com.example.lootworks.lootprobe;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** 只向已经打开箱子的客户端发送当前可见的高亮槽位。 */
public record FateHighlightPayload(int containerId, long mask) {
    private static final Map<AbstractContainerMenu, Long> CLIENT_HIGHLIGHTS = new WeakHashMap<>();

    static void encode(FateHighlightPayload payload, FriendlyByteBuf buffer) {
        buffer.writeVarInt(payload.containerId());
        buffer.writeVarLong(payload.mask());
    }

    static FateHighlightPayload decode(FriendlyByteBuf buffer) {
        return new FateHighlightPayload(buffer.readVarInt(), buffer.readVarLong());
    }

    static void receive(FateHighlightPayload payload) {
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != payload.containerId()) return;
        if (payload.mask() == 0L) {
            CLIENT_HIGHLIGHTS.remove(menu);
        } else {
            CLIENT_HIGHLIGHTS.put(menu, payload.mask());
        }
    }

    static void send(ServerPlayer player, int containerId, long mask) {
        ChestDetectorResultPayload.send(player, new FateHighlightPayload(containerId, mask));
    }

    public static long visibleSlots(AbstractContainerMenu menu) {
        return CLIENT_HIGHLIGHTS.getOrDefault(menu, 0L);
    }
}
