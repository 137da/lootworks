package com.example.lootworks.lootprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;
import com.example.lootworks.lootprobe.client.ChestDetectorClientPackets;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public record ChestDetectorResultPayload(int containerId, int runs, List<Drop> drops, int unobserved) {
    public record Drop(Item item, int hits, double chance, boolean potionGroup) {}
    record Materials(Map<ResourceLocation, FateMaterial> values) {
        private static void encode(Materials packet, FriendlyByteBuf buffer) {
            buffer.writeMap(packet.values(), FriendlyByteBuf::writeResourceLocation, (out, material) -> {
                out.writeEnum(material.curve());
                out.writeDouble(material.strength());
                out.writeVarInt(material.maxCount());
            });
        }

        private static Materials decode(FriendlyByteBuf buffer) {
            return new Materials(buffer.readMap(FriendlyByteBuf::readResourceLocation,
                    in -> new FateMaterial(in.readEnum(FateCurve.class), in.readDouble(), in.readVarInt())));
        }
    }

    /** 原版按钮序号只有一个有符号字节；改命菜单用 VarInt 传完整的目标下标。 */
    private record MenuAction(int containerId, int buttonId) {
        static void encode(MenuAction action, FriendlyByteBuf buffer) {
            buffer.writeVarInt(action.containerId());
            buffer.writeVarInt(action.buttonId());
        }

        static MenuAction decode(FriendlyByteBuf buffer) {
            return new MenuAction(buffer.readVarInt(), buffer.readVarInt());
        }
    }

    private static final String PROTOCOL = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(LootWorks.MOD_ID, "loot_probe"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void register() {
        CHANNEL.messageBuilder(ChestDetectorResultPayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ChestDetectorResultPayload::encode)
                .decoder(ChestDetectorResultPayload::decode)
                .consumerMainThread((payload, context) -> ChestDetectorClientPackets.receive(payload))
                .add();
        CHANNEL.messageBuilder(Materials.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Materials::encode)
                .decoder(Materials::decode)
                .consumerMainThread((payload, context) -> FateMaterial.receive(payload.values()))
                .add();
        CHANNEL.messageBuilder(MenuAction.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MenuAction::encode)
                .decoder(MenuAction::decode)
                .consumerMainThread((action, context) -> {
                    ServerPlayer player = context.get().getSender();
                    if (player != null && player.containerMenu instanceof ChestDetectorMenu menu
                            && menu.containerId == action.containerId() && menu.stillValid(player)) {
                        menu.clickMenuButton(player, action.buttonId());
                    }
                })
                .add();
        CHANNEL.messageBuilder(FateHighlightPayload.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(FateHighlightPayload::encode)
                .decoder(FateHighlightPayload::decode)
                .consumerMainThread((payload, context) -> FateHighlightPayload.receive(payload))
                .add();
    }

    public static void sendAction(int containerId, int buttonId) {
        CHANNEL.sendToServer(new MenuAction(containerId, buttonId));
    }

    static void send(ServerPlayer player, Object payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    private static void encode(ChestDetectorResultPayload packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId());
        buffer.writeVarInt(packet.runs());
        buffer.writeCollection(packet.drops(), (out, drop) -> {
            out.writeId(BuiltInRegistries.ITEM, drop.item());
            out.writeVarInt(drop.hits());
            out.writeDouble(drop.chance());
            out.writeBoolean(drop.potionGroup());
        });
        buffer.writeVarInt(packet.unobserved());
    }

    private static ChestDetectorResultPayload decode(FriendlyByteBuf buffer) {
        return new ChestDetectorResultPayload(buffer.readVarInt(), buffer.readVarInt(),
                buffer.readList(in -> new Drop(in.readById(BuiltInRegistries.ITEM), in.readVarInt(),
                        in.readDouble(), in.readBoolean())), buffer.readVarInt());
    }

    /** 保留有效概率项，零概率仅作表中未命中候选计数。 */
    static ChestDetectorResultPayload fromEntry(int containerId, @Nullable LootIndex.Entry entry) {
        if (entry == null || entry.samplingFailed()) {
            return new ChestDetectorResultPayload(containerId, 0, List.of(), 0);
        }

        List<Drop> observed = new ArrayList<>(); // 按最终概率降序存放
        int unobserved = 0; // 只在表中发现、未能得出正概率的候选数
        for (LootIndex.Drop drop : entry.drops()) {
            if (drop.chance() > 0.0) {
                observed.add(new Drop(BuiltInRegistries.ITEM.get(drop.itemId()), drop.hits(), drop.chance(), drop.potionGroup()));
            } else {
                unobserved++;
            }
        }
        return new ChestDetectorResultPayload(containerId, entry.runs(), List.copyOf(observed), unobserved);
    }

}
