package com.example.lootworks.lootprobe;

import java.util.List;

import com.example.lootworks.LootWorks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 宝箱检测器：潜行 + 右键未开过的奖励箱，打开概率界面。
 * <p>
 * 与万能钥匙？一样用 onItemUseFirst：在箱子自身的右键逻辑之前执行，返回非 PASS 即可阻止开箱，
 * 因此不会触发战利品生成；未潜行时返回 PASS，让普通右键照常开箱。
 */
public class ChestDetectorItem extends Item {
    private static final int COOLDOWN_TICKS = 20; // 冷却 1 秒，防止连续触发

    public ChestDetectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS; // 只有潜行 + 右键才触发检测，普通右键照常开箱
        }
        BlockEntity blockEntity = level.getBlockEntity(context.getClickedPos());
        if (blockEntity == null || !LootContainers.isSupported(blockEntity)
                || !(blockEntity instanceof RandomizableContainerBlockEntity container)) {
            return InteractionResult.PASS; // 不在支持范围（箱子、陷阱箱、木桶、潜影盒）内，按原版处理
        }
        // 客户端不知道箱子有没有战利品表（不同步），统一交给服务端判断；这里只负责不在本地开箱
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResult.FAIL;
        }
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        try {
            return inspect(serverLevel, serverPlayer, blockEntity, container, context.getClickedPos());
        } catch (RuntimeException error) {
            LootProbeErrors.report(player, "宝箱检测", "玩家 " + player.getScoreboardName() + "，坐标 "
                    + context.getClickedPos().toShortString() + "，战利品表 " + container.lootTable, error);
            return InteractionResult.FAIL;
        }
    }

    /** 服务端：读出箱子的战利品表，校验后打开检测界面 */
    private InteractionResult inspect(ServerLevel serverLevel, ServerPlayer serverPlayer, BlockEntity blockEntity,
                                      RandomizableContainerBlockEntity container, BlockPos pos) {
        Player player = serverPlayer;
        ResourceLocation table;
        LootrCompat.LootrView lootr = LootrCompat.inspect(blockEntity, serverPlayer);
        if (lootr != null) {
            // Lootr：战利品表常驻，「开没开过」按玩家各自判断
            if (lootr.opened()) return tell(player, "message.lootworks.chest_detector.lootr_opened");
            table = lootr.table();
        } else {
            table = LootrCompat.isLootr(blockEntity) ? null : container.lootTable;
            if (table == null) return tell(player, "message.lootworks.chest_detector.no_loot");
        }

        if (FateRecord.isLocked(blockEntity, serverPlayer)) {
            return tell(player, "message.lootworks.chest_detector.locked"); // 改过命的箱子不能再看、再改
        }

        LootIndex index = LootIndex.get(serverLevel.getServer());
        if (!index.hasTable(table)) {
            return tell(player, "message.lootworks.chest_detector.unknown_table");
        }
        index.request(serverLevel, table, pos);
        LootWorks.LOGGER.debug("宝箱检测：玩家 {} 坐标 {} 表 {}", player.getScoreboardName(), pos.toShortString(), table);
        serverPlayer.openMenu(new SimpleMenuProvider(
                (containerId, inventory, viewer) -> new ChestDetectorMenu(containerId, serverPlayer, blockEntity, table),
                Component.translatable("screen.lootworks.chest_detector.title")));
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult tell(Player player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
        return InteractionResult.FAIL;
    }
}
