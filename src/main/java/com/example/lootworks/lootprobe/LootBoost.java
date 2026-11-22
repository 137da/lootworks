package com.example.lootworks.lootprobe;

import java.util.function.Predicate;

import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * 方案 B3「命中判定 + 重抽」的核心：通过搜索种子来精确控制目标物品的出现概率。
 * <p>
 * 原理：原版奖励箱的内容完全由「战利品表 + 种子」决定。
 * 1. 先按目标概率 q 掷骰，决定这次「命中」还是「不命中」；
 * 2. 不断尝试新种子并模拟，直到找到一个结果与判定一致的种子（命中 → 含目标；不命中 → 不含目标）；
 * 3. 把这个种子写回箱子，开箱时原版逻辑会自然生成同一份结果。
 * 因为两个分支都强制对齐，最终含目标的概率恰好是 q（要求 q ≥ 原概率，即只能提升不能降低）。
 */
public final class LootBoost {
    private LootBoost() {}

    public static final int MAX_TRIES = 20_000; // 常规改命单次搜索种子的最大尝试次数，防止卡死服务器
    public static final int GUARANTEED_MAX_TRIES = 50_000; // 100% 必出改命放宽搜索预算，兜住万分之几超稀有物

    /**
     * 一次搜索的结果。
     *
     * @param hit   掷骰判定是否命中
     * @param seed  找到的种子；搜索失败时为 0（0 在原版中表示「不指定种子」）
     * @param tries 尝试了多少个种子
     */
    public record SeedSearch(boolean hit, long seed, int tries) {
        public boolean found() {
            return seed != 0L;
        }
    }

    /**
     * 搜索一个满足判定的种子。
     *
     * @param table       战利品表
     * @param params      开箱参数（需与真实开箱时一致）
     * @param target      目标物品
     * @param targetProb  期望的目标出现概率 q（0~1）
     * @param random      用于掷骰和生成候选种子的随机源
     */
    public static SeedSearch searchSeed(LootTable table, LootParams params, Item target, double targetProb, RandomSource random) {
        return searchSeed(table, params, stack -> stack.is(target), targetProb, random);
    }

    /**
     * 同上，用判定条件描述目标（改命界面里药水按「同瓶型的非水药水」整类算一个目标）。
     *
     * @param target 判断一叠物品是否算作目标
     */
    public static SeedSearch searchSeed(LootTable table, LootParams params, Predicate<ItemStack> target,
                                        double targetProb, RandomSource random) {
        int maxTries = targetProb >= 1.0 ? GUARANTEED_MAX_TRIES : MAX_TRIES;
        return searchSeed(table, params, target, targetProb, random, maxTries);
    }

    /**
     * 允许自定义最大尝试次数的搜索入口。
     */
    public static SeedSearch searchSeed(LootTable table, LootParams params, Predicate<ItemStack> target,
                                        double targetProb, RandomSource random, int maxTries) {
        boolean hit = random.nextDouble() < targetProb;
        return searchForOutcome(table, params, target, hit, random, maxTries);
    }

    /**
     * 仅搜索「包含目标物品」的必出种子（未命中时保留箱子原种子不调用本方法）。
     *
     * @param guaranteed 是否为 100% 必出改命（放宽搜索预算以覆盖极稀有物品）
     */
    public static SeedSearch searchHitSeed(LootTable table, LootParams params, Predicate<ItemStack> target,
                                           boolean guaranteed, RandomSource random) {
        int maxTries = guaranteed ? GUARANTEED_MAX_TRIES : MAX_TRIES;
        return searchForOutcome(table, params, target, true, random, maxTries);
    }

    private static SeedSearch searchForOutcome(LootTable table, LootParams params, Predicate<ItemStack> target,
                                               boolean hit, RandomSource random, int maxTries) {
        Container temp = new SimpleContainer(LootContainers.SLOTS); // 复用的临时箱子，每次尝试前清空
        for (int i = 1; i <= maxTries; i++) {
            long seed = random.nextLong();
            if (seed == 0L) continue; // 0 会让原版改用世界随机数，结果不可复现
            if (fillContains(table, params, seed, temp, target) == hit) {
                return new SeedSearch(hit, seed, i);
            }
        }
        return new SeedSearch(hit, 0L, maxTries);
    }

    /**
     * 用原版 {@link LootTable#fill} 按种子填充一个临时容器，检查是否含目标。
     * 用于验证「搜索时的模拟结果」与「真实开箱的填充结果」一致。
     */
    public static boolean fillContainsTarget(LootTable table, LootParams params, long seed, Item target) {
        return fillContains(table, params, seed, new SimpleContainer(LootContainers.SLOTS), stack -> stack.is(target));
    }

    /**
     * 按种子真实填一次箱子再判断，而不是只看生成列表：
     * 产出超过 27 堆时原版会随机丢掉放不下的物品，目标可能生成了却进不了箱子（假命中）。
     */
    private static boolean fillContains(LootTable table, LootParams params, long seed, Container temp,
                                        Predicate<ItemStack> target) {
        temp.clearContent(); // fill 只往空格子里放，必须先清空
        LootSimulator.sandboxed(() -> {
            table.fill(temp, params, seed);
            return null;
        });
        for (int i = 0; i < temp.getContainerSize(); i++) {
            ItemStack stack = temp.getItem(i);
            if (!stack.isEmpty() && target.test(stack)) return true;
        }
        return false;
    }
}
