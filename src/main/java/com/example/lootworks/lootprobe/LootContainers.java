package com.example.lootworks.lootprobe;

import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

/**
 * 本模组支持的奖励容器范围（2026-11-18 主人定：以 Lootr 的覆盖范围为准，再去掉陶罐）：
 * 箱子、陷阱箱、木桶、潜影盒，全是 27 格方块容器。
 * <p>
 * 发射器、投掷器、漏斗、合成器、陶罐、可疑方块、运输矿车等一律按原版处理。
 * 检测器、钥匙、索引预取都用这里判断，改范围只改这一处。
 */
public final class LootContainers {
    private LootContainers() {}

    /** 支持的容器都是 27 格；模拟开箱按这个格数填，放不下的物品和原版一样被丢掉 */
    public static final int SLOTS = 27;

    /** 是否为支持的奖励容器（只看类型，不看有没有战利品表） */
    public static boolean isSupported(BlockEntity blockEntity) {
        // 陷阱箱、Lootr 箱子与陷阱箱都继承 ChestBlockEntity；Lootr 的木桶、潜影盒不继承原版类，单独判断
        return blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof BarrelBlockEntity
                || blockEntity instanceof ShulkerBoxBlockEntity
                || LootrCompat.isBarrelOrShulker(blockEntity);
    }
}
