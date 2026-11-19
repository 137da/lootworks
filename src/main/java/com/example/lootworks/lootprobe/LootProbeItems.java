package com.example.lootworks.lootprobe;

import com.example.lootworks.Config;
import com.example.lootworks.LootWorks;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 宝箱检测功能的物品注册。
 * <p>
 * 物品循环：万能钥匙？拆未开过的宝箱 → 宝藏核心 → 合成宝箱检测器 / 命运粉尘 → 用检测器 + 粉尘提升目标物品概率。
 */
public final class LootProbeItems {
    private LootProbeItems() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LootWorks.MODID); // 本功能的物品注册器

    public static final int SKELETON_KEY_STACK = 16; // 万能钥匙？的堆叠上限（一次性消耗品）

    /** 宝箱检测器：右键宝箱查看概率、投入命运材料改命，工具类只能叠 1 个 */
    public static final DeferredItem<ChestDetectorItem> CHEST_DETECTOR = ITEMS.registerItem("chest_detector",
            ChestDetectorItem::new, new Item.Properties().stacksTo(1));

    /** 万能钥匙？：名为钥匙，实则拆箱，用一次就断 */
    public static final DeferredItem<SkeletonKeyItem> SKELETON_KEY = ITEMS.registerItem("skeleton_key",
            SkeletonKeyItem::new, new Item.Properties().stacksTo(SKELETON_KEY_STACK));

    /**
     * 宝藏核心：拆箱所得的核心材料，带耐久、防火。
     * durability() 只是写入默认耐久组件让它成为「有耐久物品」，实际上限由 {@link TreasureCoreItem#getMaxDamage} 读配置。
     */
    public static final DeferredItem<TreasureCoreItem> TREASURE_CORE = ITEMS.registerItem("treasure_core",
            TreasureCoreItem::new,
            new Item.Properties().durability(Config.TREASURE_CORE_DURABILITY_DEFAULT).fireResistant()
                    .rarity(Rarity.UNCOMMON));

    /** 命运粉尘：保留原物品 ID，以兼容已有的物品、配方和数据包。 */
    public static final DeferredItem<Item> LUCKY_DUST = ITEMS.registerSimpleItem("lucky_dust",
            new Item.Properties());

    /**
     * 命运水晶：可在改命槽中补强低概率目标，高概率段沿用粉尘曲线。
     */
    public static final DeferredItem<Item> FATE_CRYSTAL = ITEMS.registerSimpleItem("fate_crystal",
            new Item.Properties().rarity(Rarity.UNCOMMON));

    /** 命定水晶晶胚：充能阶段随单件物品保存。 */
    public static final DeferredItem<Item> UNCHARGED_DESTINED_CRYSTAL = ITEMS.registerSimpleItem("uncharged_destined_crystal",
            new Item.Properties().stacksTo(1).rarity(Rarity.RARE));

    /**
     * 命定水晶：晶胚充能后获得，走 guarantee 曲线把目标概率直接拉至 100%。
     */
    public static final DeferredItem<Item> DESTINED_CRYSTAL = ITEMS.registerSimpleItem("destined_crystal",
            new Item.Properties().durability(1).rarity(Rarity.EPIC));

    /** 遗物金属锭：钥匙拆箱、核心弹出时，附近地上的铜锭被遗物散逸的力量淬炼而成（见 CoreReveal） */
    public static final DeferredItem<Item> RELIC_METAL_INGOT = ITEMS.registerSimpleItem("relic_metal_ingot",
            new Item.Properties());

    /** 遗物合金锭：4 遗物金属锭 + 4 铁锭合成，预留给仪器。 */
    public static final DeferredItem<Item> RELIC_ALLOY_INGOT = ITEMS.registerSimpleItem("relic_alloy_ingot",
            new Item.Properties());

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
