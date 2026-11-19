package com.example.lootworks.lootprobe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.joml.Vector3f;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 拆箱后的演出，分三幕，全部在服务端按 tick 推进：
 * <ol>
 *   <li>核心出场（参考 1.21 宝库吐奖励的节奏）：0~8 tick 原位置冒少量金色微光（停顿一拍）；
 *       第 8 tick 核心向上抛出 + 宝库吐物音效 + 金色爆散与火花；落地后发光轮廓 3 秒，避免找不到。</li>
 *   <li>铜锭淬炼（仅当核心弹出时 3 格内地上有铜锭）：2 秒内铜锭缓缓升起、悬停，
 *       箱子位置周期性向每堆铜锭拉出金色能量线，铜锭周围电火花越来越密；期间铜锭不能被捡起。</li>
 *   <li>淬炼爆炸：一次只有效果的小爆炸（不破坏方块、不伤人、不销毁掉落物），
 *       铜锭整堆变成遗物金属锭，轻轻弹起后恢复下落，数量不设上限。</li>
 * </ol>
 * 悬浮用 setNoGravity，不用「每 tick 补速度抵消重力」：客户端也会自己模拟掉落物，
 * 服务端速度包晚到一拍时客户端拿旧速度减重力，铜锭会肉眼可见地「降一下再升」（/tick rate 调低时很明显）。
 * NoGravity 会存进存档，所以淬炼开始时在持久数据里打 {@link #RITUAL_TAG} 标记；
 * 演出被区块卸载 / 关服打断时，掉落物重新进入世界会由 {@link #onEntityJoin} 恢复重力、清掉标记。
 */
public final class CoreReveal {
    private CoreReveal() {}

    // ---- 第一幕：核心出场 ----
    private static final int DELAY_TICKS = 8;           // 停顿一拍的时长（0.4 秒）
    private static final int GLOW_TICKS = 60;           // 核心发光轮廓持续时间（3 秒）
    private static final double EJECT_SPEED_Y = 0.35;   // 核心向上弹出的初速度
    private static final double EJECT_SPREAD = 0.06;    // 水平方向随机偏移幅度
    private static final int BURST_DUST_COUNT = 12;     // 弹出时金色尘埃数量
    private static final int BURST_SPARK_COUNT = 4;     // 弹出时火花数量
    private static final DustParticleOptions GOLD_DUST =
            new DustParticleOptions(new Vector3f(1.0F, 0.78F, 0.2F), 1.0F); // 金色尘埃

    // ---- 第二、三幕：铜锭淬炼 ----
    private static final double TRANSMUTE_RADIUS = 3.0;  // 淬炼范围（格，以箱子中心为球心）
    private static final int RITUAL_TICKS = 40;          // 淬炼动画时长（2 秒），结束时爆炸
    private static final int LIFT_TICKS = 20;            // 前 1 秒升起，之后悬停
    private static final double LIFT_HEIGHT = 0.6;       // 升起的总高度（格）
    private static final String RITUAL_TAG = "lootworks_ritual"; // 持久数据标记：这颗掉落物正处于淬炼悬浮中
    private static final int BEAM_INTERVAL = 5;          // 每隔几 tick 拉一次能量线
    private static final double BEAM_STEP = 0.25;        // 能量线上粒子的间距（格）
    private static final int SPARK_MAX = 4;              // 临近爆炸时每 tick 每堆铜锭的电火花数
    private static final double POP_SPEED_Y = 0.25;      // 爆炸后遗物金属锭弹起的速度
    private static final double POP_SPREAD = 0.1;        // 爆炸后水平方向随机偏移幅度
    private static final int TRANSMUTE_DUST_COUNT = 8;   // 每堆被淬炼的铜锭冒出的暗色尘埃数量
    private static final DustParticleOptions RELIC_DUST =
            new DustParticleOptions(new Vector3f(0.22F, 0.24F, 0.27F), 1.2F); // 遗物金属的黑灰色尘埃

    /** 等待从容器原位置弹出的核心。 */
    private record Pending(ResourceKey<Level> dimension, Vec3 center, UUID playerId, long revealTick) {}

    /** 正在发光的核心掉落物 */
    private record Glowing(ItemEntity entity, long endTick) {}

    /** 正在淬炼的一组铜锭：source 是能量线的起点（原箱子中心） */
    private record Ritual(ResourceKey<Level> dimension, Vec3 source, List<ItemEntity> coppers, long startTick) {}

    private static final List<Pending> PENDING = new ArrayList<>(); // 只在服务端主线程读写
    private static final List<Glowing> GLOWING = new ArrayList<>();
    private static final List<Ritual> RITUALS = new ArrayList<>();

    public static void schedule(ServerLevel level, BlockPos pos, Player player) {
        PENDING.add(new Pending(level.dimension(), Vec3.atCenterOf(pos), player.getUUID(),
                level.getGameTime() + DELAY_TICKS));
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();

        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            if (pending.dimension() != level.dimension()) continue;
            if (now < pending.revealTick()) {
                // 停顿期间：缓慢上升的金色微光，暗示「有东西要出来」
                Vec3 center = pending.center();
                level.sendParticles(GOLD_DUST, center.x, center.y, center.z, 1, 0.15, 0.1, 0.15, 0.0);
                continue;
            }
            it.remove(); // 先移出列表：即使出场出错也不会每 tick 重试、反复报错
            try {
                reveal(level, pending);
            } catch (RuntimeException error) {
                LootProbeErrors.report(null, "宝藏核心出场", "坐标 " + pending.center(), error);
            }
        }

        for (Iterator<Glowing> it = GLOWING.iterator(); it.hasNext(); ) {
            Glowing glowing = it.next();
            if (glowing.entity().level() != level) continue;
            if (now >= glowing.endTick() || glowing.entity().isRemoved()) {
                glowing.entity().setGlowingTag(false);
                it.remove();
            }
        }

        for (Iterator<Ritual> it = RITUALS.iterator(); it.hasNext(); ) {
            Ritual ritual = it.next();
            if (ritual.dimension() != level.dimension()) continue;
            long elapsed = now - ritual.startTick(); // 淬炼已进行的 tick 数
            // 铜锭全被合并 / 卸载 / 移除了，演出没有对象，直接结束
            ritual.coppers().removeIf(ItemEntity::isRemoved);
            if (ritual.coppers().isEmpty()) {
                it.remove();
                continue;
            }
            boolean finished = elapsed >= RITUAL_TICKS; // 本 tick 是否到了爆炸那一刻
            if (finished) it.remove();
            try {
                if (finished) {
                    finishRitual(level, ritual);
                } else {
                    animateRitual(level, ritual, elapsed);
                }
            } catch (RuntimeException error) {
                if (!finished) it.remove();
                ritual.coppers().forEach(CoreReveal::endHover); // 演出中断：铜锭恢复重力，不让它们一直浮在半空
                LootProbeErrors.report(null, "铜锭淬炼", "坐标 " + ritual.source(), error);
            }
        }
    }

    private static void reveal(ServerLevel level, Pending pending) {
        ItemStack core = new ItemStack(LootProbeItems.TREASURE_CORE.get());
        Vec3 c = pending.center();

        // 区块已卸载（玩家跑远了等）：直接把核心交给玩家，保证不丢；铜锭不在加载范围内，也不淬炼
        if (!level.isLoaded(BlockPos.containing(c))) {
            giveToPlayer(level, pending.playerId(), core);
            return;
        }

        ItemEntity entity = new ItemEntity(level, c.x, c.y, c.z, core);
        entity.setDeltaMovement(
                (level.random.nextDouble() - 0.5) * EJECT_SPREAD,
                EJECT_SPEED_Y,
                (level.random.nextDouble() - 0.5) * EJECT_SPREAD);
        entity.setDefaultPickUpDelay();
        entity.setGlowingTag(true);
        level.addFreshEntity(entity);
        GLOWING.add(new Glowing(entity, level.getGameTime() + GLOW_TICKS));

        level.playSound(null, BlockPos.containing(c), SoundEvents.VAULT_EJECT_ITEM, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(GOLD_DUST, c.x, c.y, c.z, BURST_DUST_COUNT, 0.35, 0.25, 0.35, 0.0);
        level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 0.2, c.z, BURST_SPARK_COUNT, 0.1, 0.1, 0.1, 0.05);
        startRitual(level, c, pending.playerId());
    }

    /** 选定此刻范围内的铜锭开始淬炼；之后才丢进范围的铜锭不参与这一次。 */
    private static void startRitual(ServerLevel level, Vec3 c, UUID playerId) {
        double radiusSqr = TRANSMUTE_RADIUS * TRANSMUTE_RADIUS; // 球形范围的半径平方
        AABB area = AABB.ofSize(c, TRANSMUTE_RADIUS * 2, TRANSMUTE_RADIUS * 2, TRANSMUTE_RADIUS * 2);
        List<ItemEntity> coppers = new ArrayList<>(level.getEntitiesOfClass(ItemEntity.class, area,
                e -> e.getItem().is(Items.COPPER_INGOT) && e.position().distanceToSqr(c) <= radiusSqr));
        if (coppers.isEmpty()) {
            hintCopper(level, playerId);
            return;
        }
        for (ItemEntity copper : coppers) {
            copper.setPickUpDelay(RITUAL_TICKS + 1); // 淬炼期间不能被捡走；结束时会重设
            copper.setNoGravity(true);               // 两端都不算重力，悬浮才不会抖
            copper.getPersistentData().putBoolean(RITUAL_TAG, true); // 万一被打断，重新加载时据此恢复重力
        }
        RITUALS.add(new Ritual(level.dimension(), c, coppers, level.getGameTime()));
    }

    /** 拆箱时附近没有铜锭，且玩家尚未获得过遗物金属锭时，发送一次轻量环境低语提示 */
    private static void hintCopper(ServerLevel level, UUID playerId) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null || LootProbeAdvancements.isDone(player, LootProbeAdvancements.RELIC_METAL_INGOT)) {
            return;
        }
        player.displayClientMessage(
                Component.translatable("message.lootworks.core_reveal.copper_hint")
                        .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
                true);
    }

    /** 第二幕：升起、悬停、能量线、渐密的电火花。 */
    private static void animateRitual(ServerLevel level, Ritual ritual, long elapsed) {
        double progress = (double) elapsed / RITUAL_TICKS; // 0→1，越接近爆炸火花越密
        double liftPerTick = elapsed < LIFT_TICKS ? LIFT_HEIGHT / LIFT_TICKS : 0.0; // 本 tick 上升量
        int sparks = 1 + (int) (progress * (SPARK_MAX - 1)); // 本 tick 每堆铜锭的电火花数
        boolean beam = elapsed % BEAM_INTERVAL == 0;          // 本 tick 是否拉能量线

        for (ItemEntity copper : ritual.coppers()) {
            // 已关重力：上升阶段给匀速，悬停阶段归零；hasImpulse 让服务端本 tick 就把速度同步给客户端
            copper.setDeltaMovement(0.0, liftPerTick, 0.0);
            copper.hasImpulse = true;

            Vec3 pos = copper.position().add(0.0, 0.25, 0.0);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y, pos.z, sparks, 0.2, 0.2, 0.2, 0.0);
            if (beam) drawBeam(level, ritual.source(), pos);
        }
    }

    /** 从起点到终点画一条金色尘埃线 */
    private static void drawBeam(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        int steps = Math.max(1, (int) (delta.length() / BEAM_STEP)); // 线上粒子数
        for (int i = 1; i <= steps; i++) {
            Vec3 p = from.add(delta.scale((double) i / steps));
            level.sendParticles(GOLD_DUST, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * 第三幕：小爆炸，铜锭变遗物金属锭。
     * 不用原版 Explosion，因为它会破坏方块并直接销毁范围内的掉落物（铜锭会先被炸没）。
     */
    private static void finishRitual(ServerLevel level, Ritual ritual) {
        Vec3 middle = Vec3.ZERO; // 所有铜锭的中心点，爆炸放在这里
        for (ItemEntity copper : ritual.coppers()) middle = middle.add(copper.position());
        middle = middle.scale(1.0 / ritual.coppers().size());

        level.playSound(null, BlockPos.containing(middle), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.6F, 1.3F);
        level.sendParticles(ParticleTypes.EXPLOSION, middle.x, middle.y + 0.3, middle.z, 1, 0.0, 0.0, 0.0, 0.0);

        for (ItemEntity copper : ritual.coppers()) {
            endHover(copper); // 无论是否转换，都先恢复重力
            if (!copper.getItem().is(Items.COPPER_INGOT)) continue; // 期间被别的逻辑改了物品，不动它
            int count = copper.getItem().getCount(); // 整堆转换，不设上限
            copper.setItem(new ItemStack(LootProbeItems.RELIC_METAL_INGOT.get(), count));
            copper.setDeltaMovement(
                    (level.random.nextDouble() - 0.5) * POP_SPREAD,
                    POP_SPEED_Y,
                    (level.random.nextDouble() - 0.5) * POP_SPREAD);
            copper.hasImpulse = true;
            copper.setDefaultPickUpDelay();
            level.sendParticles(RELIC_DUST, copper.getX(), copper.getY() + 0.2, copper.getZ(),
                    TRANSMUTE_DUST_COUNT, 0.15, 0.15, 0.15, 0.0);
            level.sendParticles(ParticleTypes.SMOKE, copper.getX(), copper.getY() + 0.2, copper.getZ(),
                    2, 0.1, 0.1, 0.1, 0.01);
        }
    }

    /** 结束悬浮：恢复重力、清掉标记 */
    private static void endHover(ItemEntity entity) {
        entity.setNoGravity(false);
        entity.getPersistentData().remove(RITUAL_TAG);
    }

    /** 淬炼被打断（区块卸载、关服）的铜锭重新进入世界时，恢复重力，避免永远浮在半空 */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof ItemEntity item && item.getPersistentData().getBoolean(RITUAL_TAG)) {
            endHover(item);
        }
    }

    private static void giveToPlayer(ServerLevel level, UUID playerId, ItemStack core) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) return; // 玩家已下线：极端情况，放弃（0.4 秒内下线）
        if (!player.getInventory().add(core)) {
            player.drop(core, false); // 背包满了就掉在脚下
        }
    }

    /** 关服时清空，避免单人游戏切换存档后残留旧世界的引用 */
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        GLOWING.clear();
        RITUALS.clear();
    }
}
