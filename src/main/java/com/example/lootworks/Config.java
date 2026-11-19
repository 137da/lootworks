package com.example.lootworks;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 宝藏工坊通用配置。 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final int TREASURE_CORE_DURABILITY_DEFAULT = 8; // 宝藏核心耐久默认值

    // 宝藏核心耐久：每合成一次幸运粉尘扣 1 点（耐久附魔可减免）
    public static final ModConfigSpec.IntValue TREASURE_CORE_DURABILITY = BUILDER
            .comment("Treasure core durability. Each lucky dust craft costs 1 (reduced by Unbreaking).")
            .defineInRange("treasureCoreDurability", TREASURE_CORE_DURABILITY_DEFAULT, 1, 1024);

    static final ModConfigSpec SPEC = BUILDER.build();

    /** 读取宝藏核心耐久；配置尚未加载时（如注册阶段）返回默认值 */
    public static int treasureCoreDurability() {
        return SPEC.isLoaded() ? TREASURE_CORE_DURABILITY.get() : TREASURE_CORE_DURABILITY_DEFAULT;
    }
}
