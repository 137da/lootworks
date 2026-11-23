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
            double rareRecovery = rareCurveRecovery(p); // 稀有目标的满投补中率
            // 先比较满投曲线，让所有投入份数共用同一个交点。
            // 交点之后水晶曲线一定低于粉尘的直线段，取较大值即自动切换到直线段。
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
    /**
     * 满投必出门槛：原概率达到它时，粉尘 / 水晶满投直接拉到 100%。
     * 粉尘与水晶曲线交点（约 32.2%）之后，满投结果从交点沿直线升到 (该门槛, 100%)，
     * 避免原曲线在高概率段越来越平、投满仍有落空的手感。
     */
    private static final double LINEAR_GUARANTEE_CHANCE = 0.65;
    private static final double GUARANTEE_EPSILON = 1e-9; // 判定「已到 100%」的浮点容差

    private final String serializedName; // 数据包里写的名字

    FateCurve(String serializedName) {
        this.serializedName = serializedName;
    }

    /** 曲线本身的公式，调用方已保证 0 < p < 1、0 < count <= maxCount */
    abstract double apply(double p, double strength, int count, int maxCount);

    /**
     * 粉尘满投时，原本落空的结果中有多少比例可以补中。
     * 交点之前走平滑曲线；交点之后满投结果改为直线，到 {@link #LINEAR_GUARANTEE_CHANCE} 时为 100%。
     */
    private static double commonRecovery(double p) {
        if (p < LinearSegment.START_CHANCE) return smoothCommonRecovery(p);
        double fullChance = LinearSegment.START_RESULT
                + LinearSegment.SLOPE * (p - LinearSegment.START_CHANCE); // 满投后的概率（直线段）
        if (fullChance >= 1.0 - GUARANTEE_EPSILON) return 1.0; // 容差：正好落在门槛上时浮点误差不应让必出失效
        return (fullChance - p) / (1.0 - p); // 换算回补中率，少投时仍按份数线性折算
    }

    /** 粉尘原始平滑曲线（交点之前使用，也用于求交点） */
    private static double smoothCommonRecovery(double p) {
        double chanceSquared = p * p; // 平方让低概率目标几乎不受粉尘影响
        return COMMON_MAX_RECOVERY * chanceSquared
                / (chanceSquared + COMMON_HALF_RECOVERY_CHANCE * COMMON_HALF_RECOVERY_CHANCE);
    }

    /** 水晶低概率曲线的满投补中率 */
    private static double rareCurveRecovery(double p) {
        return RARE_MAX_RECOVERY * p / (p + RARE_HALF_RECOVERY_CHANCE);
    }

    /**
     * 直线段参数：由现有曲线常量推算，调整曲线常量后自动跟着变，不手写魔法数字。
     * 放在嵌套类里，避开枚举常量先于静态字段初始化的顺序问题。
     */
    private static final class LinearSegment {
        static final double START_CHANCE = findCrossover(); // 直线段起点：粉尘与水晶曲线的交点（原概率）
        static final double START_RESULT = START_CHANCE
                + (1.0 - START_CHANCE) * smoothCommonRecovery(START_CHANCE); // 起点处满投后的概率
        static final double SLOPE = (1.0 - START_RESULT)
                / (LINEAR_GUARANTEE_CHANCE - START_CHANCE); // 直线斜率：起点连到 (门槛, 100%)

        /** 二分求交点：交点之前水晶曲线更高，之后粉尘曲线更高 */
        private static double findCrossover() {
            double low = 0.01, high = LINEAR_GUARANTEE_CHANCE; // 搜索区间
            for (int i = 0; i < 60; i++) {
                double mid = (low + high) / 2;
                if (rareCurveRecovery(mid) > smoothCommonRecovery(mid)) low = mid;
                else high = mid;
            }
            return low;
        }
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
