package com.example.lootworks.lootprobe;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;
import com.mojang.serialization.Codec;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 改命记录：挂在奖励箱方块实体上的数据附件，随存档保存，记录「改命玩家 → 命运种子」。
 * <p>
 * - 原版箱子所有人共用一份战利品，只要有记录就整箱锁定；种子同时直接写进箱子。
 * - Lootr 箱子每人一份战利品，按玩家分别锁定；开箱时由 mixin 把这位玩家的种子交给 Lootr。
 *
 * @param seeds 玩家 UUID → 命运种子
 */
public record FateRecord(Map<UUID, Long> seeds) {
    /** 哨兵值：表示该箱子已完成改命并锁定，但本次未命中、保留箱子原本的种子不覆盖 */
    public static final long UNCHANGED_SEED = 0L;

    public static final FateRecord EMPTY = new FateRecord(Map.of());

    public static final Codec<FateRecord> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG)
            .xmap(FateRecord::new, FateRecord::seeds);

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, LootWorks.MODID);

    /** 附件类型 lootworks:fate；空记录不写进存档 */
    public static final Supplier<AttachmentType<FateRecord>> TYPE = ATTACHMENTS.register("fate",
            () -> AttachmentType.builder(() -> EMPTY).serialize(CODEC, record -> !record.seeds().isEmpty()).build());

    public FateRecord {
        seeds = Map.copyOf(seeds);
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    /** 读取箱子上的改命记录 */
    public static FateRecord of(BlockEntity chest) {
        return chest.getData(TYPE);
    }

    /** 这个箱子对这位玩家来说是否已经改过命（Lootr 按玩家，原版按整箱） */
    public static boolean isLocked(BlockEntity chest, ServerPlayer player) {
        FateRecord record = of(chest);
        if (record.seeds().isEmpty()) return false;
        return LootrCompat.inspect(chest, player) == null || record.seeds().containsKey(player.getUUID());
    }

    /** 这位玩家的命运种子；没有改过命或本次未命中（保留原种子）返回 null */
    @Nullable
    public Long seedOf(UUID player) {
        Long seed = seeds.get(player);
        return (seed == null || seed == UNCHANGED_SEED) ? null : seed;
    }

    /** 追加一条记录并写回箱子 */
    static void put(BlockEntity chest, UUID player, long seed) {
        Map<UUID, Long> updated = new HashMap<>(of(chest).seeds());
        updated.put(player, seed);
        chest.setData(TYPE, new FateRecord(updated));
        chest.setChanged();
    }
}
