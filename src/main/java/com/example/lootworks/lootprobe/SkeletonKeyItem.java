package com.example.lootworks.lootprobe;

import java.util.List;

import com.example.lootworks.LootWorks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 万能钥匙？：名为钥匙，实则拆箱。对「未开过」的奖励箱使用 → 箱子碎掉、钥匙断掉、宝藏核心停顿一拍后弹出。
 * <p>
 * 用 onItemUseFirst 实现：它在方块自身的右键逻辑（开箱）之前执行，返回非 PASS 即可阻止开箱，
 * 因此不会触发战利品生成。
 * 原版与 Lootr 奖励容器都拆除方块，Lootr 仍检查使用者是否开过及服务器是否允许拆除。
 */
public class SkeletonKeyItem extends Item {
    private static final float BREAK_SOUND_VOLUME = 0.6F; // 撞门声原版很响，压低一些
    private static final int POOF_COUNT = 8;              // 烟雾粒子数量

    public SkeletonKeyItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (player == null || blockEntity == null || !LootContainers.isSupported(blockEntity)
                || !(blockEntity instanceof RandomizableContainerBlockEntity container)) {
            return InteractionResult.PASS; // 不在支持范围（箱子、陷阱箱、木桶、潜影盒）内，按原版处理
        }
        // 客户端不知道箱子有没有战利品表（不同步），统一交给服务端判断；这里只负责不在本地开箱
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        try {
            return unlock(stack, serverLevel, serverPlayer, blockEntity, container, pos, context.getClickedFace());
        } catch (RuntimeException error) {
            LootProbeErrors.report(player, "万能钥匙拆箱", "玩家 " + player.getScoreboardName() + "，坐标 "
                    + pos.toShortString() + "，战利品表 " + container.lootTable, error);
            return InteractionResult.FAIL;
        }
    }

    /** 服务端：检查能否拆箱，拆掉后扣钥匙并安排核心出场 */
    private static InteractionResult unlock(ItemStack stack, ServerLevel serverLevel, ServerPlayer serverPlayer,
                                            BlockEntity blockEntity, RandomizableContainerBlockEntity container, BlockPos pos, Direction face) {
        Player player = serverPlayer;
        var table = LootIndex.chestTable(blockEntity); // Lootr 的常驻表与原版字段分开
        if (table == null) {
            return showEmpty(player);
        }

        boolean lootr = LootrCompat.lootTable(blockEntity) != null; // 拆之前判断，写日志用
        LootrCompat.BreakCheck check = LootrCompat.checkBreak(container, serverPlayer);
        if (check == LootrCompat.BreakCheck.ALREADY_OPENED) {
            return showEmpty(player);
        }
        if (check == LootrCompat.BreakCheck.DENIED || !serverLevel.mayInteract(player, pos)
                || !player.mayUseItemAt(pos, face, stack)
                || !breakChest(serverLevel, pos, container, player)) {
            return InteractionResult.FAIL;
        }
        LootWorks.LOGGER.info("万能钥匙拆箱：玩家 {} 坐标 {} 表 {}（{}）", player.getScoreboardName(),
                pos.toShortString(), table, lootr ? "Lootr" : "原版");

        if (!player.getAbilities().instabuild) stack.shrink(1); // 一次性：用完就断（创造模式不消耗）
        CoreReveal.schedule(serverLevel, pos, player);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult showEmpty(Player player) {
        player.displayClientMessage(Component.translatable("message.lootworks.skeleton_key.empty"), true);
        return InteractionResult.FAIL;
    }

    /** 作废战利品并拆掉方块，成功后播放音效与粒子。 */
    private static boolean breakChest(ServerLevel level, BlockPos pos, RandomizableContainerBlockEntity container, Player player) {
        // 先清掉战利品表，防止原版容器被移除时读取内容并展开战利品。
        var table = container.lootTable;
        long seed = container.lootTableSeed;
        boolean vanilla = !LootrCompat.isLootr(container);
        if (vanilla) container.setLootTable(null, 0L);
        boolean destroyed; // 方块是否已被拆掉
        try {
            destroyed = level.destroyBlock(pos, false, player);
        } catch (RuntimeException error) {
            if (vanilla) container.setLootTable(table, seed); // 拆的过程中出错（其他模组的破坏事件等）：先还原战利品表，别让箱子白白变空
            throw error;
        }
        if (!destroyed) {
            if (vanilla) container.setLootTable(table, seed);
            return false;
        }

        Vec3 center = Vec3.atCenterOf(pos);
        level.playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 1.0F, 1.0F);                       // 插钥匙的咔哒声
        level.playSound(null, pos, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.BLOCKS, BREAK_SOUND_VOLUME, 1.0F); // 箱子被砸烂
        level.playSound(null, player.blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 1.0F, 1.0F);      // 钥匙断了
        level.sendParticles(ParticleTypes.POOF, center.x, center.y, center.z, POOF_COUNT, 0.3, 0.3, 0.3, 0.02);
        return true;
    }
}
