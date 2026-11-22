package com.example.lootworks.lootprobe;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;

import net.minecraft.world.entity.player.Player;

/**
 * 统一的出错处理：整合包里其他模组的战利品表、修改器、方块实体都可能抛异常，
 * 各个入口（检测器、钥匙、改命、演出）捕获后交给这里——仅在后台记完整日志，绝不让异常冒到游戏里导致崩溃。
 */
public final class LootProbeErrors {
    private LootProbeErrors() {}

    /**
     * 记录错误到日志，不向玩家发送聊天提示。
     *
     * @param player  出错时操作的玩家，可为空（服务端 tick 等没有玩家的场景）
     * @param action  正在做什么，写进日志，如「改命」
     * @param context 现场信息（坐标、战利品表等），写进日志方便定位
     * @param error   捕获到的异常
     */
    public static void report(@Nullable Player player, String action, String context, Throwable error) {
        LootWorks.LOGGER.error("{}出错（{}），已中止本次操作", action, context, error);
    }
}
