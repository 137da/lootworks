package com.example.lootworks.lootprobe;

import java.util.function.Supplier;

import com.example.lootworks.LootWorks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 测试用全局战利品修改器（GLM），模仿「小翔修仙」以 1% 概率往所有宝箱塞筑基丹的写法。
 * <p>
 * 它的概率写在自定义字段 chance 里、判断逻辑写在 Java 代码里，
 * 战利品表 JSON 中完全看不到——用来验证「硬读表读不到、模拟开箱能统计到」。
 */
public class TestAddItemModifier extends LootModifier {
    public static final MapCodec<TestAddItemModifier> CODEC = RecordCodecBuilder.mapCodec(inst -> codecStart(inst).and(inst.group(
            BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(m -> m.item),
            Codec.floatRange(0f, 1f).fieldOf("chance").forGetter(m -> m.chance),
            Codec.STRING.fieldOf("table_path_prefix").forGetter(m -> m.tablePathPrefix)
    )).apply(inst, TestAddItemModifier::new));

    public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, LootWorks.MODID);
    public static final Supplier<MapCodec<TestAddItemModifier>> TEST_ADD_ITEM =
            SERIALIZERS.register("test_add_item", () -> CODEC);

    private final Item item;               // 要追加的物品
    private final float chance;            // 追加概率（0~1）
    private final String tablePathPrefix;  // 只对路径以此开头的战利品表生效，如 "chests/"

    public TestAddItemModifier(LootItemCondition[] conditions, Item item, float chance, String tablePathPrefix) {
        super(conditions);
        this.item = item;
        this.chance = chance;
        this.tablePathPrefix = tablePathPrefix;
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        if (context.getQueriedLootTableId().getPath().startsWith(tablePathPrefix)
                && context.getRandom().nextFloat() < chance) {
            generatedLoot.add(new ItemStack(item));
        }
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
