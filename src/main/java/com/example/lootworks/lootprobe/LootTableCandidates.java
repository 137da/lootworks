package com.example.lootworks.lootprobe;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.functions.EnchantRandomlyFunction;
import net.minecraft.world.level.storage.loot.functions.EnchantWithLevelsFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

/** 提取表中声明的候选物品，具体条件、函数和实际概率仍由模拟处理。 */
final class LootTableCandidates {
    private LootTableCandidates() {}

    /** 待展开的条目，enchanted 表示外层（表 / 池 / 组合）已经带了附魔函数 */
    private record Pending(LootPoolEntryContainer entry, boolean enchanted) {}

    static Set<ResourceLocation> collect(ReloadableServerRegistries.Holder registries, LootTable root) {
        Set<ResourceLocation> items = new TreeSet<>(); // 稳定排序，方便查询与比较
        Set<LootTable> visited = Collections.newSetFromMap(new IdentityHashMap<>()); // 同时防止引用表和内联表成环
        Deque<LootTable> tables = new ArrayDeque<>();
        Deque<Pending> entries = new ArrayDeque<>();
        tables.add(root);
        while (!tables.isEmpty()) {
            LootTable table = tables.removeFirst();
            if (!visited.add(table)) continue;
            boolean tableEnchants = enchants(table.functions);
            table.pools.forEach(pool -> {
                boolean poolEnchants = tableEnchants || enchants(pool.functions);
                pool.entries.forEach(entry -> entries.add(new Pending(entry, poolEnchants)));
            });
            while (!entries.isEmpty()) {
                Pending pending = entries.removeFirst();
                switch (pending.entry()) {
                    case LootItem item -> items.add(itemId(item.item.value(),
                            pending.enchanted() || enchants(item.functions)));
                    case TagEntry tag -> BuiltInRegistries.ITEM.getTagOrEmpty(tag.tag).forEach(
                            item -> items.add(itemId(item.value(), pending.enchanted() || enchants(tag.functions))));
                    // 嵌套表的物品只受嵌套表自己的函数影响时才算附魔；外层池函数会作用到嵌套表的产出上，
                    // 但这里按表展开会丢失外层信息，影响仅限「外层池附魔 + 嵌套表里有书」这种罕见写法。
                    case NestedLootTable nested -> tables.add(nested.contents.map(registries::getLootTable, inline -> inline));
                    case CompositeEntryBase composite -> composite.children.forEach(
                            child -> entries.add(new Pending(child, pending.enchanted())));
                    // 空条目、动态内容与其他模组自定义条目没有可直接读取的物品。
                    default -> {}
                }
            }
        }
        return items;
    }

    /**
     * 原版附魔函数作用在书上会把它换成附魔书（如地牢箱子的「书 + enchant_randomly」），
     * 模拟统计到的是 enchanted_book，这里同样换掉，避免把普通书误报成「极稀有物品」。
     */
    private static ResourceLocation itemId(Item item, boolean enchanted) {
        Item result = enchanted && item == Items.BOOK ? Items.ENCHANTED_BOOK : item;
        return BuiltInRegistries.ITEM.getKey(result);
    }

    private static boolean enchants(List<LootItemFunction> functions) {
        for (LootItemFunction function : functions) {
            if (function instanceof EnchantRandomlyFunction || function instanceof EnchantWithLevelsFunction) {
                return true;
            }
        }
        return false;
    }
}
