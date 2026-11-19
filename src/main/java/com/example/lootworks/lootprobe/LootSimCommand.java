package com.example.lootworks.lootprobe;

import com.example.lootworks.LootWorks;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 原型测试命令：
 * <pre>
 * /lootworks lootsim block &lt;x y z&gt; &lt;次数&gt;   读取该位置奖励箱的战利品表并模拟
 * /lootworks lootsim table &lt;表ID&gt; &lt;次数&gt;    直接按表 ID 模拟（用于性能基准）
 * </pre>
 */
public final class LootSimCommand {
    private LootSimCommand() {}

    private static final int MAX_RUNS = 100_000;  // 单次命令允许的最大模拟次数，防止误输入卡死服务器
    private static final int MAX_CHAT_LINES = 40;  // 聊天栏最多显示的物品行数，完整结果写入日志

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal(LootWorks.MODID)
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("lootsim")
                        .then(Commands.literal("block")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("runs", IntegerArgumentType.integer(1, MAX_RUNS))
                                                .executes(LootSimCommand::simulateBlock))))
                        .then(Commands.literal("table")
                                .then(Commands.argument("table", ResourceLocationArgument.id())
                                        .then(Commands.argument("runs", IntegerArgumentType.integer(1, MAX_RUNS))
                                                .executes(LootSimCommand::simulateTable)))))
                .then(Commands.literal("lootboost")
                        // 统计验证：对某张表反复执行 B3 搜索，检查实际命中率是否等于目标概率
                        .then(Commands.literal("test")
                                .then(Commands.argument("table", ResourceLocationArgument.id())
                                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                                .then(Commands.argument("prob", DoubleArgumentType.doubleArg(0, 1))
                                                        .then(Commands.argument("trials", IntegerArgumentType.integer(1, MAX_TRIALS))
                                                                .executes(LootSimCommand::boostTest))))))
                        // 实际改命：给某个箱子写入搜索到的种子
                        .then(Commands.literal("apply")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                                .then(Commands.argument("prob", DoubleArgumentType.doubleArg(0, 1))
                                                        .executes(LootSimCommand::boostApply)))))));
    }

    private static final int MAX_TRIALS = 10_000;     // lootboost test 的最大试验次数
    private static final int BASE_PROB_RUNS = 5_000;  // 估算原概率时的模拟次数

    private static int boostTest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ResourceKey<LootTable> tableKey = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocationArgument.getId(ctx, "table"));
        Item target = lookupItem(src, ResourceLocationArgument.getId(ctx, "item"));
        if (target == null) return 0;
        double prob = DoubleArgumentType.getDouble(ctx, "prob");
        int trials = IntegerArgumentType.getInteger(ctx, "trials");

        BlockPos origin = BlockPos.containing(src.getPosition());
        double baseProb = estimateBaseProb(level, tableKey, origin, src, target);

        LootTable table = level.getServer().reloadableRegistries().getLootTable(tableKey);
        LootParams params = LootSimulator.buildChestParams(level, origin, src.getPlayer());
        RandomSource random = RandomSource.create();

        int finalHas = 0;    // 最终（按种子真实填充）含目标的次数
        int mismatches = 0;  // 搜索判定与真实填充结果不一致的次数（应为 0）
        int failures = 0;    // 超过最大尝试次数仍未找到种子的次数
        long totalTries = 0;
        int maxTries = 0;
        long start = System.nanoTime();
        for (int i = 0; i < trials; i++) {
            LootBoost.SeedSearch s = LootBoost.searchSeed(table, params, target, prob, random);
            totalTries += s.tries();
            maxTries = Math.max(maxTries, s.tries());
            if (!s.found()) { failures++; continue; }
            boolean has = LootBoost.fillContainsTarget(table, params, s.seed(), target);
            if (has) finalHas++;
            if (has != s.hit()) mismatches++;
        }
        double elapsedMs = (System.nanoTime() - start) / 1e6;
        int valid = trials - failures;

        String msg = String.format(
                "[lootboost] %s 目标=%s\n  原概率≈%.2f%%  目标概率=%.2f%%  实测=%.2f%%（%d/%d）\n"
                        + "  不一致=%d  失败=%d  平均尝试=%.1f  最多尝试=%d\n  总耗时=%.1f ms  平均每次搜索=%.3f ms",
                tableKey.location(), BuiltInRegistries.ITEM.getKey(target), baseProb * 100, prob * 100,
                valid == 0 ? 0 : 100.0 * finalHas / valid, finalHas, valid,
                mismatches, failures, (double) totalTries / trials, maxTries, elapsedMs, elapsedMs / trials);
        src.sendSuccess(() -> Component.literal(msg), false);
        LootWorks.LOGGER.info(msg);
        return 1;
    }

    private static int boostApply(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        Item target = lookupItem(src, ResourceLocationArgument.getId(ctx, "item"));
        if (target == null) return 0;
        double prob = DoubleArgumentType.getDouble(ctx, "prob");

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RandomizableContainer container) || container.getLootTable() == null) {
            src.sendFailure(Component.literal("该位置不是未开启的奖励箱"));
            return 0;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(container.getLootTable());
        // 注意：参数里的玩家用的是执行命令的人，实际开箱者不同时，依赖玩家的 GLM 条件可能不一致（原型阶段忽略）
        LootParams params = LootSimulator.buildChestParams(level, pos, src.getPlayer());
        LootBoost.SeedSearch s = LootBoost.searchSeed(table, params, target, prob, RandomSource.create());
        if (!s.found()) {
            src.sendFailure(Component.literal("尝试 " + s.tries() + " 次仍未找到合适的种子（目标可能过于稀有）"));
            return 0;
        }
        long oldSeed = container.getLootTableSeed();
        container.setLootTableSeed(s.seed());
        be.setChanged(); // 标记方块实体需要保存
        src.sendSuccess(() -> Component.literal(String.format("[lootboost] 判定=%s，尝试 %d 次，种子 %d → %d",
                s.hit() ? "命中" : "未命中", s.tries(), oldSeed, s.seed())), false);
        return 1;
    }

    /** 用普通模拟估算目标物品的原始出现概率，便于和提升后的概率对比 */
    private static double estimateBaseProb(ServerLevel level, ResourceKey<LootTable> table, BlockPos origin,
                                           CommandSourceStack src, Item target) {
        ResourceLocation targetId = BuiltInRegistries.ITEM.getKey(target);
        LootSimulator.Result r = LootSimulator.simulate(level, table, origin, src.getPlayer(), BASE_PROB_RUNS);
        for (LootSimulator.ItemStat s : r.stats()) {
            if (s.itemId.equals(targetId)) return (double) s.runsAppeared / r.runs();
        }
        return 0;
    }

    private static Item lookupItem(CommandSourceStack src, ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) src.sendFailure(Component.literal("找不到物品：" + id));
        return item;
    }

    private static int simulateBlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        int runs = IntegerArgumentType.getInteger(ctx, "runs");

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RandomizableContainer container)) {
            src.sendFailure(Component.literal("该位置不是可随机战利品的容器"));
            return 0;
        }
        ResourceKey<LootTable> table = container.getLootTable();
        if (table == null) {
            // 原版箱子被打开过后会把战利品表清空（setLootTable(null)），此时已无可查询的内容
            src.sendFailure(Component.literal("该容器没有战利品表（可能已被打开过）"));
            return 0;
        }
        long seedBefore = container.getLootTableSeed(); // 记录种子，用于验证模拟不会改动箱子

        LootSimulator.Result result = LootSimulator.simulate(level, table, pos, src.getPlayer(), runs);
        report(src, result);

        boolean untouched = container.getLootTable() != null && container.getLootTableSeed() == seedBefore;
        src.sendSuccess(() -> Component.literal(String.format("箱子种子=%d，模拟后箱子%s",
                seedBefore, untouched ? "未被改动 ✔" : "被改动了 ✘")), false);
        return 1;
    }

    private static int simulateTable(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "table");
        int runs = IntegerArgumentType.getInteger(ctx, "runs");
        ResourceKey<LootTable> table = ResourceKey.create(Registries.LOOT_TABLE, id);

        BlockPos origin = BlockPos.containing(src.getPosition());
        LootSimulator.Result result = LootSimulator.simulate(src.getLevel(), table, origin, src.getPlayer(), runs);
        report(src, result);
        return 1;
    }

    /** 把结果发到聊天栏（截断）并完整写入日志 */
    private static void report(CommandSourceStack src, LootSimulator.Result r) {
        String header = String.format("[lootsim] %s ×%d 次，耗时 %.2f ms（平均 %.1f µs/次），共 %d 种物品",
                r.tableId(), r.runs(), r.elapsedNanos() / 1e6, r.microsPerRun(), r.stats().size());
        src.sendSuccess(() -> Component.literal(header), false);
        LootWorks.LOGGER.info(header);

        int line = 0;
        for (LootSimulator.ItemStat s : r.stats()) {
            String text = String.format("  %6.2f%%  平均%.2f个  %s",
                    100.0 * s.runsAppeared / r.runs(), (double) s.totalCount / r.runs(), s.itemId);
            LootWorks.LOGGER.info(text);
            if (line++ < MAX_CHAT_LINES) {
                src.sendSuccess(() -> Component.literal(text), false);
            }
        }
        if (r.stats().isEmpty()) {
            src.sendSuccess(() -> Component.literal("  （没有生成任何物品，检查表 ID 是否正确）"), false);
        }
    }
}
