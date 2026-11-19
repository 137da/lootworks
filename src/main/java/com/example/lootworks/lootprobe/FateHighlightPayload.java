package com.example.lootworks.lootprobe;

import java.util.Map;
import java.util.WeakHashMap;

import com.example.lootworks.LootWorks;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 只向已经打开箱子的客户端发送当前可见的高亮槽位。 */
public record FateHighlightPayload(int containerId, long mask) implements CustomPacketPayload {
    static final Type<FateHighlightPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LootWorks.MODID, "fate_highlights"));
    private static final StreamCodec<FriendlyByteBuf, FateHighlightPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FateHighlightPayload::containerId,
            ByteBufCodecs.VAR_LONG, FateHighlightPayload::mask,
            FateHighlightPayload::new);
    private static final Map<AbstractContainerMenu, Long> CLIENT_HIGHLIGHTS = new WeakHashMap<>();

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(TYPE, CODEC, (payload, context) -> {
            AbstractContainerMenu menu = context.player().containerMenu;
            if (menu.containerId != payload.containerId()) return;
            if (payload.mask() == 0L) {
                CLIENT_HIGHLIGHTS.remove(menu);
            } else {
                CLIENT_HIGHLIGHTS.put(menu, payload.mask());
            }
        });
    }

    public static long visibleSlots(AbstractContainerMenu menu) {
        return CLIENT_HIGHLIGHTS.getOrDefault(menu, 0L);
    }

    @Override
    public Type<FateHighlightPayload> type() {
        return TYPE;
    }
}
