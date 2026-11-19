package com.example.lootworks.lootprobe;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * 改命材料的属性，用 NeoForge 数据映射配置：{@code data/<命名空间>/data_maps/item/fate_materials.json}。
 * <p>
 * 加新材料只需要写 JSON，整合包也能用数据包加自己的材料。数据映射会同步到客户端，界面据此实时预览新概率。
 *
 * @param curve    改命曲线
 * @param strength 曲线强度（补抽曲线为满投补中率的倍率，赔率和幂曲线仍按每份计）
 * @param maxCount 一次改命最多消耗几份，多放的留在槽里
 */
public record FateMaterial(FateCurve curve, double strength, int maxCount) {
    public static final Codec<FateMaterial> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            FateCurve.CODEC.fieldOf("curve").forGetter(FateMaterial::curve),
            Codec.doubleRange(0.0, 1000.0).fieldOf("strength").forGetter(FateMaterial::strength),
            ExtraCodecs.POSITIVE_INT.fieldOf("max_count").forGetter(FateMaterial::maxCount)
    ).apply(instance, FateMaterial::new));

    /** 数据映射类型 lootworks:fate_materials，客户端预览也要用，所以同步（非强制，客户端缺失时不拒绝进服） */
    public static final DataMapType<Item, FateMaterial> DATA_MAP = DataMapType.builder(
                    ResourceLocation.fromNamespaceAndPath(LootWorks.MODID, "fate_materials"), Registries.ITEM, CODEC)
            .synced(CODEC, false)
            .build();

    /** 在模组总线上注册数据映射类型 */
    public static void register(RegisterDataMapTypesEvent event) {
        event.register(DATA_MAP);
    }

    /** 读取物品的改命属性；不是改命材料返回 null */
    @Nullable
    public static FateMaterial of(ItemStack stack) {
        return stack.isEmpty() ? null : stack.getItemHolder().getData(DATA_MAP);
    }

    /** 这一叠材料实际会消耗的份数 */
    public int usedCount(ItemStack stack) {
        return Math.min(stack.getCount(), maxCount);
    }

    /** 用这一叠材料改命后的概率 */
    public double boost(double p, ItemStack stack) {
        return curve.boost(p, strength, usedCount(stack), maxCount);
    }
}
