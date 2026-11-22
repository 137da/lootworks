// SPDX-License-Identifier: LGPL-3.0
package com.example.lootworks;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    public static final int TREASURE_CORE_DURABILITY_DEFAULT = 8; // 宝藏核心的默认耐久
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final ForgeConfigSpec.IntValue TREASURE_CORE_DURABILITY = BUILDER
            .comment("Treasure core durability. Each lucky dust craft costs 1 (reduced by Unbreaking).")
            .defineInRange("treasureCoreDurability", TREASURE_CORE_DURABILITY_DEFAULT, 1, 1024);
    static final ForgeConfigSpec SPEC = BUILDER.build();

    private Config() {}

    public static int treasureCoreDurability() {
        return SPEC.isLoaded() ? TREASURE_CORE_DURABILITY.get() : TREASURE_CORE_DURABILITY_DEFAULT;
    }
}
