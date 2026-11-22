// SPDX-License-Identifier: LGPL-3.0
package com.example.lootworks.lootprobe;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 原版整箱锁定，Lootr 按玩家锁定。两种种子均存入方块实体的 ForgeData。 */
public final class FateRecord {
    /** 哨兵值：表示该箱子已完成改命并锁定，但本次未命中、保留箱子原本的种子不覆盖 */
    public static final long UNCHANGED_SEED = 0L;
    private static final String DATA_KEY = "lootworks:fate"; // 独立的命运种子记录

    private FateRecord() {}

    public static boolean isLocked(BlockEntity chest, ServerPlayer player) {
        CompoundTag data = chest.getPersistentData().getCompound(DATA_KEY);
        return LootrCompat.isLootr(chest) ? data.contains(player.getUUID().toString(), Tag.TAG_LONG) : !data.isEmpty();
    }

    @Nullable
    public static Long seedOf(BlockEntity chest, UUID player) {
        CompoundTag data = chest.getPersistentData().getCompound(DATA_KEY);
        String key = player.toString();
        if (!data.contains(key, Tag.TAG_LONG)) return null;
        long seed = data.getLong(key);
        return seed == UNCHANGED_SEED ? null : seed;
    }

    static void put(BlockEntity chest, UUID player, long seed) {
        CompoundTag persistent = chest.getPersistentData();
        CompoundTag data = persistent.getCompound(DATA_KEY);
        data.putLong(player.toString(), seed);
        persistent.put(DATA_KEY, data);
        chest.setChanged();
    }
}
