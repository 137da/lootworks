package com.example.lootworks.mixin;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.lootworks.lootprobe.FateResonance;
import com.example.lootworks.lootprobe.LootSimulator;

import net.minecraft.world.Container;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * 模拟开箱时屏蔽原版「Tried to over-fill a container」警告，并在真实奖励箱填充后通知共鸣系统。
 * <p>
 * 检测器和改命为了与真实开箱一致，用原版 {@link LootTable#fill} 往 27 格临时容器里填；
 * 整合包里产出超过 27 堆的表每模拟一次就会打一条警告，索引一次模拟成百上千次会刷爆日志。
 * 真实开箱不受影响，照常警告。require = 0：找不到注入点只是多几条日志，不让游戏崩溃。
 */
@Mixin(LootTable.class)
public abstract class LootTableFillMixin {
    @Inject(method = "fill", at = @At("RETURN"))
    private void lootworks$onChestFilled(Container container, LootParams params, long seed, CallbackInfo callback) {
        FateResonance.onLootGenerated(container, params);
    }

    @Redirect(method = "fill",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;)V"),
            require = 0)
    private void lootworks$muteOverfillWhileSimulating(Logger logger, String message) {
        if (!LootSimulator.isSimulating()) logger.warn(message);
    }
}
