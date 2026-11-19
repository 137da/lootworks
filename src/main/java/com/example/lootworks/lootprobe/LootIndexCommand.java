package com.example.lootworks.lootprobe;

import java.util.Locale;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** 查询服务端索引状态、表和已放置的奖励箱；clear 清空缓存（含存档）。 */
public final class LootIndexCommand {
    private static final int MAX_CHAT_LINES = 40; // 表中更多物品写到日志

    private LootIndexCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(LootWorks.MODID)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("lootindex")
                        .executes(LootIndexCommand::reportStatus)
                        .then(Commands.literal("clear").executes(LootIndexCommand::clear))
                        .then(Commands.literal("table")
                                .then(Commands.argument("table", ResourceLocationArgument.id())
                                        .executes(ctx -> reportTable(ctx.getSource(),
                                                ResourceLocationArgument.getId(ctx, "table"), null, null))
                                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                                .executes(ctx -> reportTable(ctx.getSource(),
                                                        ResourceLocationArgument.getId(ctx, "table"),
                                                        ResourceLocationArgument.getId(ctx, "item"), null)))))
                        .then(Commands.literal("block")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(LootIndexCommand::reportBlock)))));
    }

    private static int reportStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        LootIndex.Status status = LootIndex.get(source.getServer()).status();
        double average = status.busyTicks() == 0 ? 0 : status.busyNanos() / 1e6 / status.busyTicks();
        String message = String.format(Locale.ROOT,
                "[lootindex] 完成 %d（存档恢复 %d）| 失败 %d | 优先 %d | 预取 %d%n"
                        + "  工作 tick %d，平均 %.3f ms，跳过 %d | 峰值 %.2f ms（%s）",
                status.ready(), status.restored(), status.failed(), status.urgent(), status.prefetch(),
                status.busyTicks(), average, status.skippedTicks(), status.peakTickNanos() / 1e6, status.peakKey());
        source.sendSuccess(() -> Component.literal(message), false);
        LootWorks.LOGGER.info(message);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        LootIndex.get(ctx.getSource().getServer()).clear();
        ctx.getSource().sendSuccess(() -> Component.literal("[lootindex] 已清空缓存，正在重新扫描玩家周围的区块"), true);
        return 1;
    }

    private static int reportBlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        BlockEntity blockEntity = source.getLevel().getBlockEntity(pos);
        ResourceKey<LootTable> table = blockEntity == null ? null : LootIndex.chestTable(blockEntity);
        if (table != null) return reportTable(source, table.location(), null, pos);
        source.sendFailure(Component.literal("该容器没有可查询的战利品表"));
        return 0;
    }

    /** pos 为空时用命令执行者所在位置模拟 */
    private static int reportTable(CommandSourceStack source, ResourceLocation id, @Nullable ResourceLocation item,
                                   @Nullable BlockPos pos) {
        LootIndex index = LootIndex.get(source.getServer());
        if (!index.hasTable(id)) {
            source.sendFailure(Component.literal("找不到战利品表：" + id));
            return 0;
        }
        BlockPos origin = pos != null ? pos : BlockPos.containing(source.getPosition());
        LootIndex.Entry result = index.request(source.getLevel(), id, origin);
        if (result == null) {
            source.sendSuccess(() -> Component.literal("[lootindex] " + id + " 已加入优先队列，稍后可再次查询"), false);
            return 1;
        }

        String sampling = result.samplingFailed() ? "采样失败，详见服务端日志"
                : result.runs() + " 次模拟（" + source.getLevel().dimension().location() + "）";
        String header = "[lootindex] " + id + " | " + sampling + " | 共发现 " + result.drops().size() + " 种物品";
        source.sendSuccess(() -> Component.literal(header), false);
        LootWorks.LOGGER.info(header);

        int lines = 0;
        for (LootIndex.Drop drop : result.drops()) {
            if (item != null && !drop.itemId().equals(item)) continue;
            String chance;
            if (drop.chance() > 0.0) {
                double dynamic = result.runs() > 0 ? (double) drop.hits() / result.runs() : 0.0;
                String pct = drop.chance() < 0.001
                        ? String.format(Locale.ROOT, "%.4f%%", 100.0 * drop.chance())
                        : String.format(Locale.ROOT, "%.2f%%", 100.0 * drop.chance());
                String sourceNote = drop.hits() == 0 ? "静态理论，采样 0/" + result.runs()
                        : Math.abs(drop.chance() - dynamic) > 1e-6
                                ? String.format(Locale.ROOT, "静态校正，采样 %d/%d", drop.hits(), result.runs())
                                : String.format(Locale.ROOT, "采样 %d/%d", drop.hits(), result.runs());
                chance = pct + "（" + sourceNote + "）";
            } else {
                chance = result.samplingFailed() ? "表中候选，未取得样本" : "表中候选，采样未命中";
            }
            String line = "  " + drop.itemId() + (drop.potionGroup() ? " [药水归类]" : "") + " | " + chance;
            LootWorks.LOGGER.info(line);
            if (lines++ < MAX_CHAT_LINES) source.sendSuccess(() -> Component.literal(line), false);
        }
        if (item != null && lines == 0) source.sendFailure(Component.literal("索引未发现物品：" + item));
        return lines > 0 || item == null ? 1 : 0;
    }
}
