package com.example.lootworks.lootprobe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * 战利品模拟器：在服务端「暗中」反复生成战利品，统计每种物品的出现概率。
 * <p>
 * 关键点：统计用的是原版 {@link LootTable#fill}（内部走 getRandomItems），
 * Forge 在其中挂了全局战利品修改器（GLM）钩子，所以其他模组用 GLM 塞进来的物品也会被统计到；
 * 同时与真实开箱一样受 27 格限制，放不下的物品不计入。
 * 只填进临时容器，不碰任何真实箱子。
 */
public final class LootSimulator {
    private LootSimulator() {}

    /** 模拟嵌套深度；只在服务端主线程读写。大于 0 时，有副作用的战利品函数会被 mixin 短路 */
    private static int simulating;

    /** 当前是否处于模拟开箱中（供 mixin 判断，例如探险地图不搜索结构、不写存档） */
    public static boolean isSimulating() {
        return simulating > 0;
    }

    /** 在「模拟模式」下执行一段生成战利品的代码，真实开箱不要用 */
    public static <T> T sandboxed(Supplier<T> action) {
        simulating++;
        try {
            return action.get();
        } finally {
            simulating--;
        }
    }

    /** 三种药水容器共用水瓶与非水药水的分类规则。 */
    public static boolean isPotionBottle(ItemStack stack) {
        return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION);
    }

    /** 单个物品的统计结果 */
    public static final class ItemStat {
        public final ResourceLocation itemId; // 物品 ID
        public final boolean potionGroup;     // 同种瓶型的非水药水合并为一项
        public int runsAppeared;              // 在多少次模拟中至少出现过一次
        public long totalCount;               // 所有模拟累计的总数量

        ItemStat(ResourceLocation itemId, boolean potionGroup) {
            this.itemId = itemId;
            this.potionGroup = potionGroup;
        }
    }

    /** 一次完整模拟的结果 */
    public record Result(ResourceLocation tableId, int runs, long elapsedNanos, List<ItemStat> stats) {
        /** 平均每次模拟耗时（微秒） */
        public double microsPerRun() {
            return runs == 0 ? 0 : elapsedNanos / 1000.0 / runs;
        }
    }

    /**
     * 构造宝箱战利品参数，与原版 原版容器与 Lootr 的 unpackLootTable 保持一致，
     * 这样模拟结果才和真实开箱走同一套条件判断。
     */
    public static LootParams buildChestParams(ServerLevel level, BlockPos origin, @Nullable ServerPlayer player) {
        LootParams.Builder builder = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(origin));
        if (player != null) {
            builder.withLuck(player.getLuck()).withParameter(LootContextParams.THIS_ENTITY, player);
        }
        return builder.create(LootContextParamSets.CHEST);
    }

    /**
     * 对指定战利品表模拟开箱 runs 次。
     *
     * @param level  所在世界（必须在服务端主线程调用，战利品生成会读世界数据，不是线程安全的）
     * @param table  战利品表 key
     * @param origin 模拟的「箱子位置」，部分条件（如群系判断）依赖它
     * @param player 模拟的开箱玩家，可为空；传入后幸运值与玩家相关的 GLM 条件才会生效
     * @param runs   模拟次数
     */
    public static Result simulate(ServerLevel level, ResourceLocation table, BlockPos origin,
                                  @Nullable ServerPlayer player, int runs) {
        LootTable lootTable = level.getServer().getLootData().getLootTable(table);
        Sampling sampling = new Sampling(lootTable, buildChestParams(level, origin, player), false);
        sampling.advance(runs, System.nanoTime(), Long.MAX_VALUE);
        return sampling.result(table);
    }

    /** 保留累计统计和独立随机源，让索引可以在服务器的多个 tick 之间续算。 */
    static final class Sampling {
        private final LootTable lootTable; // 已加载的战利品表
        private final LootParams params; // 这一批样本共用的开箱环境
        private final boolean separateWater; // 检测器区分水瓶，按物品 ID 查询的模拟仍统计整类
        private final RandomSource random = RandomSource.create(); // 与箱子种子、世界随机源隔离
        private final SimpleContainer chest = new SimpleContainer(LootContainers.SLOTS); // 复用的临时箱子
        private final Map<ResourceLocation, ItemStat> statMap = new HashMap<>();
        private final Map<ResourceLocation, ItemStat> potionStats = new HashMap<>(); // 药水与同瓶型的水分别计数
        private final Set<ItemStat> seenThisRun = new HashSet<>(); // 每轮内按显示类别去重
        private int runs;
        private long elapsedNanos;

        Sampling(LootTable lootTable, LootParams params, boolean separateWater) {
            this.lootTable = lootTable;
            this.params = params;
            this.separateWater = separateWater;
        }

        boolean advance(int targetRuns, long tickStart, long budgetNanos) {
            long start = System.nanoTime();
            while (runs < targetRuns && System.nanoTime() - tickStart < budgetNanos) {
                // 真实填一次 27 格箱子再统计：产出超过 27 堆时原版会丢掉放不下的，只看生成列表会把概率算高
                long seed = random.nextLong();
                if (seed == 0L) continue; // 0 会改用世界随机数
                chest.clearContent();
                sandboxed(() -> {
                    lootTable.fill(chest, params, seed);
                    return null;
                });
                seenThisRun.clear();
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    ItemStack stack = chest.getItem(slot);
                    if (stack.isEmpty()) continue;
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    ItemStat stat = separateWater && isPotionGroup(stack)
                            ? potionStats.computeIfAbsent(id, itemId -> new ItemStat(itemId, true))
                            : statMap.computeIfAbsent(id, itemId -> new ItemStat(itemId, false));
                    stat.totalCount += stack.getCount();
                    if (seenThisRun.add(stat)) stat.runsAppeared++;
                }
                runs++;
            }
            elapsedNanos += System.nanoTime() - start;
            return runs >= targetRuns;
        }

        Result result(ResourceLocation tableId) {
            List<ItemStat> stats = new ArrayList<>(statMap.values());
            stats.addAll(potionStats.values());
            stats.sort((a, b) -> Integer.compare(b.runsAppeared, a.runsAppeared));
            return new Result(tableId, runs, elapsedNanos, stats);
        }

        /** 有自定义效果的水基药水也应归进药水栏。 */
        static boolean isPotionGroup(ItemStack stack) {
            return isPotionBottle(stack)
                    && (PotionUtils.getPotion(stack) != Potions.WATER || !PotionUtils.getCustomEffects(stack).isEmpty());
        }
    }
}
