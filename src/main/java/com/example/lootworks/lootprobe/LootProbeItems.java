package com.example.lootworks.lootprobe;

import com.example.lootworks.Config;
import com.example.lootworks.LootWorks;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

/**
 * 宝箱检测功能的物品注册。
 * <p>
 * 物品循环：万能钥匙？拆未开过的宝箱 → 宝藏核心 → 合成宝箱检测器 / 幸运粉尘 → 用检测器 + 粉尘提升目标物品概率。
 * 交互逻辑分别在 ChestDetectorItem（检测）、SkeletonKeyItem（拆箱）里；改命在后续阶段实现。
 */
public final class LootProbeItems {
    private LootProbeItems() {}

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, LootWorks.MOD_ID); // 本功能的物品注册器

    public static final int SKELETON_KEY_STACK = 16; // 万能钥匙？的堆叠上限（一次性消耗品）

    /** 宝箱检测器：右键宝箱查看概率、投入幸运粉尘改命，工具类只能叠 1 个 */
    public static final RegistryObject<ChestDetectorItem> CHEST_DETECTOR = ITEMS.register("chest_detector",
            () -> new ChestDetectorItem(new Item.Properties().stacksTo(1)));

    /** 万能钥匙？：名为钥匙，实则拆箱，用一次就断 */
    public static final RegistryObject<SkeletonKeyItem> SKELETON_KEY = ITEMS.register("skeleton_key",
            () -> new SkeletonKeyItem(new Item.Properties().stacksTo(SKELETON_KEY_STACK)));

    /**
     * 宝藏核心：拆箱所得的核心材料，带耐久、防火。
     * durability() 只是设置默认耐久让它成为「有耐久物品」，实际上限由 {@link TreasureCoreItem#getMaxDamage} 读配置。
     */
    public static final RegistryObject<TreasureCoreItem> TREASURE_CORE = ITEMS.register("treasure_core",
            () -> new TreasureCoreItem(new Item.Properties().durability(Config.TREASURE_CORE_DURABILITY_DEFAULT).fireResistant()
                    .rarity(Rarity.UNCOMMON)));

    /** 幸运粉尘：改命时消耗的材料 */
    public static final RegistryObject<Item> LUCKY_DUST = ITEMS.register("lucky_dust",
            () -> new Item(new Item.Properties()));

    /** 命运水晶：可在改命槽中补强低概率目标，高概率段沿用粉尘曲线。 */
    public static final RegistryObject<Item> FATE_CRYSTAL = ITEMS.register("fate_crystal",
            () -> new Item(new Item.Properties().rarity(Rarity.UNCOMMON)));

    /** 命定水晶晶胚：充能阶段随单件物品保存。 */
    public static final RegistryObject<Item> UNCHARGED_DESTINED_CRYSTAL = ITEMS.register("uncharged_destined_crystal",
            () -> new Item(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    /** 命定水晶：晶胚充能后获得，走 guarantee 曲线把目标概率直接拉至 100%。 */
    public static final RegistryObject<Item> DESTINED_CRYSTAL = ITEMS.register("destined_crystal",
            () -> new Item(new Item.Properties().durability(1).rarity(Rarity.EPIC)));

    /** 遗物金属锭：钥匙拆箱、核心弹出时，附近地上的铜锭被遗物散逸的力量淬炼而成（见 CoreReveal） */
    public static final RegistryObject<Item> RELIC_METAL_INGOT = ITEMS.register("relic_metal_ingot",
            () -> new Item(new Item.Properties()));

    /** 遗物合金锭：4 遗物金属锭 + 4 铁锭合成（参考下界合金锭），用途待定，后续用于高阶改命材料等 */
    public static final RegistryObject<Item> RELIC_ALLOY_INGOT = ITEMS.register("relic_alloy_ingot",
            () -> new Item(new Item.Properties()));

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
