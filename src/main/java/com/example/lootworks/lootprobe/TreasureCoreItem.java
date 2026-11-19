package com.example.lootworks.lootprobe;

import com.example.lootworks.Config;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * 宝藏核心：拆宝箱获得的核心材料。
 * <p>
 * - 合成幸运粉尘时只扣 1 点耐久并留在合成格（通过「合成剩余物品」实现，粉尘配方因此可以是普通 JSON，JEI/配方书自动显示）；
 * - 合成宝箱检测器时整个消耗（由 {@link CoreConsumingShapedRecipe} 覆盖剩余物品）；
 * - 可附魔「耐久」（及原版随之而来的「消失诅咒」），禁止「经验修补」；
 * - 允许两个核心合并：耐久上限仅 8，原版合并奖励（5% / 12%）取整为 0，只是凑整、不会凭空增加耐久。
 */
public class TreasureCoreItem extends Item {
    public static final int ENCHANTMENT_VALUE = 15; // 附魔能力值，介于铁(14)与金(22)之间

    private static final RandomSource RANDOM = RandomSource.createThreadSafe(); // 耐久附魔判定用；客户端预测与服务端各算一次，以服务端同步结果为准

    public TreasureCoreItem(Properties properties) {
        super(properties);
    }

    /** 耐久上限走配置，方便整合包调整 */
    @Override
    public int getMaxDamage(ItemStack stack) {
        return Config.treasureCoreDurability();
    }

    @Override
    public int getEnchantmentValue(ItemStack stack) {
        return ENCHANTMENT_VALUE;
    }

    /** 经验修补会让核心用经验无限回耐久，粉尘就不再稀缺，所以单独排除（原版里它和耐久共用 enchantable/durability 标签） */
    @Override
    public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> enchantment) {
        if (enchantment.is(Enchantments.MENDING)) return false;
        return super.supportsEnchantment(stack, enchantment);
    }

    @Override
    public boolean hasCraftingRemainingItem(ItemStack stack) {
        return true;
    }

    /** 合成后留下扣了耐久的核心；耐久耗尽则碎掉（返回空） */
    @Override
    public ItemStack getCraftingRemainingItem(ItemStack stack) {
        ItemStack remaining = stack.copyWithCount(1);
        if (shouldConsumeDurability(stack)) {
            int newDamage = remaining.getDamageValue() + 1; // 新的已损耗值
            if (newDamage >= remaining.getMaxDamage()) return ItemStack.EMPTY;
            remaining.setDamageValue(newDamage);
        }
        return remaining;
    }

    /**
     * 耐久附魔判定，与原版工具一致：每次有 1/(等级+1) 的概率真正扣耐久。
     * 合成格拿不到 ServerLevel，用不了原版 hurtAndBreak，所以这里手动按公式算。
     */
    private static boolean shouldConsumeDurability(ItemStack stack) {
        int unbreaking = 0; // 耐久附魔等级
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
            if (entry.getKey().is(Enchantments.UNBREAKING)) {
                unbreaking = entry.getIntValue();
                break;
            }
        }
        return unbreaking <= 0 || RANDOM.nextInt(unbreaking + 1) == 0;
    }

}
