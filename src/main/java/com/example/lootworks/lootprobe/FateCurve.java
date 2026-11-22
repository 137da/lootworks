package com.example.lootworks.lootprobe;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * 改命材料能用的概率曲线。每种材料在 JSON 里选一条曲线和强度，这里只管把原概率 {@code p} 变成新概率 {@code q}。
 * <p>
 * 0%（箱子里不会出的物品）和 100%（已经必出的物品）不会改变。
 */
public enum FateCurve implements StringRepresentable {
    /**
     * 赔率乘法：「出现 : 不出现」的赔率乘以 strength^份数，保留给已有数据包。
     */
    ODDS("odds") {
        @Override
        double apply(double p, double strength, int count, int maxCount) {
            double factor = Math.pow(strength, count); // 赔率总倍数
            return p * factor / (1.0 - p + p * factor);
        }
    },
    /**
     * 幂曲线：q = p^(1 / (1 + strength × 份数))，保留给已有数据包。
     */
    POWER("power") {
        @Override
        double apply(double p, double strength, int count, int maxCount) {
            return Math.pow(p, 1.0 / (1.0 + strength * count));
        }
    },
    /** 幸运粉尘：原概率越高，能补中的落空结果比例越大。 */
    COMMON_RECOVERY("common_recovery") {
        @Override
        double apply(double p, double strength, int count, int maxCount) {
            return applyRecovery(p, commonRecovery(p) * strength, count, maxCount);
        }
    },
    /** 命运水晶：在低概率段额外增益，高概率段与幸运粉尘共用曲线。 */
    RARE_RECOVERY("rare_recovery") {
        @Override
        double apply(double p, double strength, int count, int maxCount) {
            double rareRecovery = RARE_MAX_RECOVERY * p / (p + RARE_HALF_RECOVERY_CHANCE); // 稀有目标的满投补中率
            // 先比较满投曲线，让所有投入份数共用同一个交点。
            double fullRecovery = Math.max(rareRecovery, commonRecovery(p)); // 满投补中率
            return applyRecovery(p, fullRecovery * strength, count, maxCount);
        }
    },
    /**
     * 必出保底：直接把目标概率设为 100%（1.0），供命定水晶等终极改命材料使用。
     */
    GUARANTEE("guarantee") {
        @Override
        double apply(double p, double strength, int count, int maxCount) {
            return 1.0;
        }
    };

    public static final Codec<FateCurve> CODEC = StringRepresentable.fromEnum(FateCurve::values);

    private static final double COMMON_MAX_RECOVERY = 0.90; // 粉尘曲线的理论补中率上限
    private static final double COMMON_HALF_RECOVERY_CHANCE = 0.33; // 粉尘达到该上限一半时的原概率
    private static final double RARE_MAX_RECOVERY = 0.45; // 水晶低概率曲线的理论补中率上限
    private static final double RARE_HALF_RECOVERY_CHANCE = 0.008; // 水晶低概率曲线达到该上限一半时的原概率

    private final String serializedName; // 数据包里写的名字

    FateCurve(String serializedName) {
        this.serializedName = serializedName;
    }

    /** 曲线本身的公式，调用方已保证 0 < p < 1、0 < count <= maxCount */
    abstract double apply(double p, double strength, int count, int maxCount);

    /** 粉尘满投时，原本落空的结果中有多少比例可以补中 */
    private static double commonRecovery(double p) {
        double chanceSquared = p * p; // 平方让低概率目标几乎不受粉尘影响
        return COMMON_MAX_RECOVERY * chanceSquared
                / (chanceSquared + COMMON_HALF_RECOVERY_CHANCE * COMMON_HALF_RECOVERY_CHANCE);
    }

    /** 补抽只作用于原本落空的部分，按投入量线性折算 */
    private static double applyRecovery(double p, double fullRecovery, int count, int maxCount) {
        double recovery = Math.min(1.0, fullRecovery) * count / maxCount; // 本次补中率
        return p + (1.0 - p) * recovery;
    }

    /**
     * 计算改命后的概率。
     *
     * @param p        原概率（0~1）
     * @param strength 材料强度（含义见各曲线）
     * @param count    实际消耗的份数，已按上限截断
     * @param maxCount 满效果需要的份数
     */
    public double boost(double p, double strength, int count, int maxCount) {
        if (p <= 0.0 || p >= 1.0 || count <= 0) return p; // 不出现的物品没法改，必出的物品不用改
        return Math.max(p, Math.min(1.0, apply(p, strength, count, maxCount)));
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
