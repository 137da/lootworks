package com.example.lootworks.lootprobe;

import com.example.lootworks.LootWorks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 携带晶胚时推进命运共鸣，成功三次后在原格子充成命定水晶。 */
public final class FateResonance {
    private static final int INVENTORY_CHECK_TICKS = 20; // 每秒同步背包与状态图标
    private static final int ROLL_INTERVAL_TICKS = 5 * INVENTORY_CHECK_TICKS; // 每五秒掷一次骰子
    private static final int OPENING_BOOST_TICKS = 10 * 60 * INVENTORY_CHECK_TICKS; // 开箱加成持续十分钟
    private static final int NORMAL_ROLL_SIDES = 120; // 携带时平均十分钟推进一阶
    private static final int BOOSTED_ROLL_SIDES = 80; // 开箱窗口内平均六分四十秒推进一阶
    private static final int REQUIRED_SUCCESSES = 3; // 充满需要的成功次数
    private static final String PROGRESS_KEY = "lootworks:fate_resonance_progress"; // 存在晶胚上的成功次数
    private static final String BOOST_UNTIL_KEY = "lootworks:fate_resonance_boost_until"; // 玩家加成结束的游戏时间

    private static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT, LootWorks.MODID);
    public static final DeferredHolder<MobEffect, MobEffect> EFFECT = EFFECTS.register("fate_resonance",
            () -> new MobEffect(MobEffectCategory.NEUTRAL, 0x852E16) {}); // 只展示共鸣等级

    private FateResonance() {}

    public static void register(IEventBus modEventBus) {
        EFFECTS.register(modEventBus);
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % INVENTORY_CHECK_TICKS != 0) return;
        if (player.isSpectator() || !player.isAlive()) {
            player.removeEffect(EFFECT);
            return;
        }

        Inventory inventory = player.getInventory(); // 背包与副手都能携带晶胚
        int activeSlot = -1; // 一次只推进一个晶胚，优先继续充进度最高的那个
        int progress = -1; // 当前晶胚已经掷中的次数
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(LootProbeItems.UNCHARGED_DESTINED_CRYSTAL.get())) continue;
            int charge = stage(stack);
            if (charge > progress) {
                activeSlot = slot;
                progress = charge;
            }
        }
        if (activeSlot < 0) {
            player.removeEffect(EFFECT);
            return;
        }

        if (player.tickCount % ROLL_INTERVAL_TICKS == 0 && rollSucceeds(player)) {
            progress++;
            if (progress == REQUIRED_SUCCESSES) {
                inventory.setItem(activeSlot, withState(inventory.getItem(activeSlot), LootProbeItems.DESTINED_CRYSTAL.get()));
                player.removeEffect(EFFECT);
                player.playNotifySound(SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 1.2F);
                return;
            }
            int advancedProgress = progress; // 将新阶段和晶胚一起保存在存档中
            CustomData.update(DataComponents.CUSTOM_DATA, inventory.getItem(activeSlot),
                    tag -> tag.putInt(PROGRESS_KEY, advancedProgress));
            inventory.setChanged();
            player.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.0F + progress * 0.15F);
        }
        MobEffectInstance currentEffect = player.getEffect(EFFECT); // 已同步的状态等级
        if (currentEffect == null || currentEffect.getAmplifier() != progress) {
            player.removeEffect(EFFECT);
            player.addEffect(new MobEffectInstance(EFFECT, MobEffectInstance.INFINITE_DURATION, progress, true, false, true));
        }
    }

    /** 晶胚成功推进的次数，供共鸣状态和悬停提示共用。 */
    public static int stage(ItemStack stack) {
        int progress = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt(PROGRESS_KEY);
        return Math.clamp(progress, 0, REQUIRED_SUCCESSES - 1);
    }

    static ItemStack discharge(ItemStack stack) {
        return withState(stack, LootProbeItems.UNCHARGED_DESTINED_CRYSTAL.get());
    }

    private static ItemStack withState(ItemStack original, Item item) {
        // 切换充能态时以新物品的默认组件为准，仅保留玩家取的名字。
        ItemStack result = item.getDefaultInstance();
        var name = original.get(DataComponents.CUSTOM_NAME);
        if (name != null) result.set(DataComponents.CUSTOM_NAME, name);
        return result;
    }

    private static boolean rollSucceeds(ServerPlayer player) {
        long now = player.serverLevel().getServer().overworld().getGameTime(); // 统一时间基准，跨维度也能正常过期
        boolean boosted = now < player.getPersistentData().getLong(BOOST_UNTIL_KEY);
        return player.getRandom().nextInt(boosted ? BOOSTED_ROLL_SIDES : NORMAL_ROLL_SIDES) == 0;
    }

    /** 原版直接往方块容器填箱，Lootr 则把每位玩家的战利品填进独立容器。 */
    public static void onLootGenerated(Container container, LootParams params) {
        if (LootSimulator.isSimulating()
                || !(params.getOptionalParameter(LootContextParams.THIS_ENTITY) instanceof ServerPlayer player)) return;

        Vec3 origin = params.getOptionalParameter(LootContextParams.ORIGIN); // 奖励箱的世界坐标
        BlockEntity chest = origin == null ? null : player.serverLevel().getBlockEntity(BlockPos.containing(origin));
        if (chest == null || !LootContainers.isSupported(chest)
                || (chest != container && LootrCompat.inspect(chest, player) == null)) return;

        long now = player.serverLevel().getServer().overworld().getGameTime();
        player.getPersistentData().putLong(BOOST_UNTIL_KEY, now + OPENING_BOOST_TICKS);
        FateHighlights.reveal(chest, container, player);
    }
}
