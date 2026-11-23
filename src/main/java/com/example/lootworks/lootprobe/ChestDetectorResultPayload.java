package com.example.lootworks.lootprobe;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** 同一份单向同步包用于缓存已就绪和打开菜单后才算完的情况。 */
public record ChestDetectorResultPayload(int containerId, int runs, List<Drop> drops, int unobserved)
        implements CustomPacketPayload {
    public record Drop(Item item, int hits, double chance, boolean potionGroup) {}

    /** 深度搜索进度；target 为 0 表示已结束（或被拒绝），客户端清掉进度显示。 */
    public record DeepSearchProgress(int containerId, int runs, int target) implements CustomPacketPayload {
        private static final Type<DeepSearchProgress> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(LootWorks.MODID, "deep_search_progress"));
        private static final StreamCodec<FriendlyByteBuf, DeepSearchProgress> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DeepSearchProgress::containerId,
                ByteBufCodecs.VAR_INT, DeepSearchProgress::runs,
                ByteBufCodecs.VAR_INT, DeepSearchProgress::target,
                DeepSearchProgress::new);

        @Override
        public Type<DeepSearchProgress> type() {
            return TYPE;
        }
    }

    private static final String PROTOCOL = "4"; // 4：新增深度搜索进度包

    private static final Type<ChestDetectorResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LootWorks.MODID, "chest_detector_result"));

    // 用注册表编号传物品，未命中的候选只传数量，避免大表用物品名称撑大包体。
    private static final StreamCodec<RegistryFriendlyByteBuf, Drop> DROP_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.ITEM), Drop::item,
            ByteBufCodecs.VAR_INT, Drop::hits,
            ByteBufCodecs.DOUBLE, Drop::chance,
            ByteBufCodecs.BOOL, Drop::potionGroup,
            Drop::new);

    private static final StreamCodec<RegistryFriendlyByteBuf, ChestDetectorResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChestDetectorResultPayload::containerId,
            ByteBufCodecs.VAR_INT, ChestDetectorResultPayload::runs,
            DROP_CODEC.apply(ByteBufCodecs.list()), ChestDetectorResultPayload::drops,
            ByteBufCodecs.VAR_INT, ChestDetectorResultPayload::unobserved,
            ChestDetectorResultPayload::new);

    /** 注册从服务端下发的结果，处理时只更新对应的菜单实例。 */
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToClient(TYPE, STREAM_CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof ChestDetectorMenu menu) menu.receive(payload);
        });
        registrar.playToClient(DeepSearchProgress.TYPE, DeepSearchProgress.STREAM_CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof ChestDetectorMenu menu) menu.receiveProgress(payload);
        });
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

    @Override
    public Type<ChestDetectorResultPayload> type() {
        return TYPE;
    }
}
