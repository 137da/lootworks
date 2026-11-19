package com.example.lootworks.lootprobe;

import java.util.function.Predicate;

import com.example.lootworks.LootWorks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/**
 * 改命的服务端执行：按材料算出新概率 → 搜命运种子 → 写入箱子并锁定。
 * <p>
 * 调用方（检测器菜单）负责校验箱子身份、距离、开箱状态并扣除材料；这里只做和改命本身相关的检查。
 * 改命不改战利品表，只替这一个箱子挑种子：命中分支找「含目标」的种子，未命中分支找「不含目标」的种子，
 * 两边都对齐，所以开出目标的概率恰好是新概率（原理见 {@link LootBoost}）。
 */
public final class FateService {
    private FateService() {}

    private static final int PARTICLE_COUNT = 30; // 改命成功时的附魔粒子数量，保持克制

    /** 改命结果 */
    public enum Outcome {
        SUCCESS,
        LOCKED,        // 这个箱子（或这位玩家的那一份）已经改过命
        SEARCH_FAILED  // 搜不到满足判定的种子，不扣材料
    }

    /**
     * 执行一次改命。
     *
     * @param player   改命的玩家
     * @param chest    奖励箱方块实体
     * @param table    箱子的战利品表
     * @param target   目标物品
     * @param potionGroup 目标是否为「同瓶型的非水药水」整类
     * @param p        目标的原概率（检测器显示的估计值）
     * @param q        改命后的目标概率
     */
    public static Outcome fate(ServerPlayer player, BlockEntity chest, ResourceKey<LootTable> table,
                               Item target, boolean potionGroup, double p, double q) {
        if (FateRecord.isLocked(chest, player)) return Outcome.LOCKED;

        ServerLevel level = player.serverLevel();
        boolean personal = LootrCompat.inspect(chest, player) != null;
        BlockPos pos = chest.getBlockPos();
        LootTable lootTable = level.getServer().reloadableRegistries().getLootTable(table);
        // 精确补抽概率 r = (q - p) / (1 - p)：命中时换必出种子，未命中时不动原箱子种子，最终概率仍精确等于 q 且绝不反向改命
        boolean guaranteed = q >= 1.0;
        double extraHitChance = (guaranteed || p >= 1.0) ? 1.0 : Math.clamp((q - p) / (1.0 - p), 0.0, 1.0);
        RandomSource random = level.getRandom();
        boolean hit = guaranteed || random.nextDouble() < extraHitChance;

        long recordedSeed = FateRecord.UNCHANGED_SEED;
        int tries = 0;
        if (hit) {
            // 开箱参数与真实开箱一致：原版和 Lootr 都以开箱玩家为 THIS_ENTITY，这里假定改命者就是开箱者
            var params = LootSimulator.buildChestParams(level, pos, player);
            Predicate<ItemStack> matches = stack -> stack.is(target) && LootSimulator.Sampling.isPotionGroup(stack) == potionGroup;
            LootBoost.SeedSearch search = LootBoost.searchHitSeed(lootTable, params, matches, guaranteed, random);
            if (!search.found()) {
                LootWorks.LOGGER.warn("改命搜种子失败：表 {} 目标 {} 原概率 {} 新概率 {}", table.location(), target, p, q);
                return Outcome.SEARCH_FAILED;
            }
            recordedSeed = search.seed();
            tries = search.tries();
            // 原版箱子：种子直接写进箱子，开箱时原版按它生成；Lootr 箱子：只记录，开箱时由 mixin 取用
            if (!personal && chest instanceof RandomizableContainer container) {
                container.setLootTable(table, recordedSeed);
            }
        }
        FateRecord.put(chest, player.getUUID(), recordedSeed);
        FateHighlights.rememberTarget(chest, player.getUUID(), personal, target, potionGroup);
        LootWorks.LOGGER.info("改命成功：玩家 {} 坐标 {} 表 {} 目标 {} 概率 {} → {} 补抽={} 种子 {}（尝试 {} 次）",
                player.getScoreboardName(), pos.toShortString(), table.location(), target,
                String.format("%.4f", p), String.format("%.4f", q), hit ? "命中" : "保留原种子", recordedSeed, tries);
        playEffects(level, pos);
        LootProbeAdvancements.award(player, LootProbeAdvancements.REWRITE_FATE,
                LootProbeAdvancements.REWRITE_FATE_CRITERION);
        return Outcome.SUCCESS;
    }

    /** 箱子上方一圈附魔符文 + 附魔台音效，不透露命中与否 */
    private static void playEffects(ServerLevel level, BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        level.playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.ENCHANT, c.x, c.y + 0.8, c.z, PARTICLE_COUNT, 0.4, 0.3, 0.4, 0.5);
    }

}
