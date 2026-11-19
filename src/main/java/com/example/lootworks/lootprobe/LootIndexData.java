package com.example.lootworks.lootprobe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 战利品索引的存档持久化：存在 {@code <存档>/data/lootworks_loot_index.dat}，退出存档不丢。
 * <p>
 * 只存采样成功的结果。是否还能用由 {@link LootIndex} 负责核对：
 * 整体指纹（模组列表 + 数据包）变了全部作废，单张表内容的哈希变了只作废这张。
 */
final class LootIndexData extends SavedData {
    static final String NAME = "lootworks_loot_index"; // 存档里的文件名

    /** 一条缓存：采样结果 + 采样时这张表内容的哈希 */
    record Stored(LootIndex.Entry entry, int tableHash) {}

    String fingerprint = "";                                     // 写入时的模组与数据包指纹
    final Map<LootIndex.Key, Stored> entries = new HashMap<>(); // 「维度 + 表」→ 缓存

    static Factory<LootIndexData> factory() {
        return new Factory<>(LootIndexData::new, LootIndexData::load);
    }

    void clear() {
        entries.clear();
        setDirty();
    }

    private static LootIndexData load(CompoundTag tag, HolderLookup.Provider registries) {
        LootIndexData data = new LootIndexData();
        data.fingerprint = tag.getString("fingerprint");
        for (Tag element : tag.getList("entries", Tag.TAG_COMPOUND)) {
            CompoundTag stored = (CompoundTag) element;
            ResourceLocation dimension = ResourceLocation.tryParse(stored.getString("dimension"));
            ResourceLocation table = ResourceLocation.tryParse(stored.getString("table"));
            if (dimension == null || table == null) continue; // 损坏的条目直接丢弃，之后会重新采样

            int runs = stored.getInt("runs");
            List<LootIndex.Drop> drops = new ArrayList<>();
            for (Tag dropElement : stored.getList("drops", Tag.TAG_COMPOUND)) {
                CompoundTag drop = (CompoundTag) dropElement;
                ResourceLocation item = ResourceLocation.tryParse(drop.getString("item"));
                if (item != null) {
                    int hits = drop.getInt("hits");
                    double chance = drop.contains("chance") ? drop.getDouble("chance")
                            : (runs > 0 ? (double) hits / runs : 0.0);
                    drops.add(new LootIndex.Drop(item, hits, chance, drop.getBoolean("potion_group")));
                }
            }
            LootIndex.Key key = new LootIndex.Key(ResourceKey.create(Registries.DIMENSION, dimension), table);
            LootIndex.Entry entry = new LootIndex.Entry(runs, List.copyOf(drops), false);
            data.entries.put(key, new Stored(entry, stored.getInt("hash")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("fingerprint", fingerprint);
        ListTag list = new ListTag();
        entries.forEach((key, stored) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("dimension", key.dimension().location().toString());
            entry.putString("table", key.table().toString());
            entry.putInt("hash", stored.tableHash());
            entry.putInt("runs", stored.entry().runs());
            ListTag drops = new ListTag();
            for (LootIndex.Drop drop : stored.entry().drops()) {
                CompoundTag dropTag = new CompoundTag();
                dropTag.putString("item", drop.itemId().toString());
                dropTag.putInt("hits", drop.hits());
                dropTag.putDouble("chance", drop.chance());
                if (drop.potionGroup()) dropTag.putBoolean("potion_group", true);
                drops.add(dropTag);
            }
            entry.put("drops", drops);
            list.add(entry);
        });
        tag.put("entries", list);
        return tag;
    }
}
