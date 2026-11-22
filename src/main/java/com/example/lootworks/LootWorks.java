// SPDX-License-Identifier: LGPL-3.0
package com.example.lootworks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.lootworks.lootprobe.ChestDetectorResultPayload;
import com.example.lootworks.lootprobe.CoreReveal;
import com.example.lootworks.lootprobe.FateHighlights;
import com.example.lootworks.lootprobe.FateMaterial;
import com.example.lootworks.lootprobe.FateResonance;
import com.example.lootworks.lootprobe.LootIndex;
import com.example.lootworks.lootprobe.LootIndexCommand;
import com.example.lootworks.lootprobe.LootProbeItems;
import com.example.lootworks.lootprobe.LootProbeMenus;
import com.example.lootworks.lootprobe.LootProbeRecipes;
import com.example.lootworks.lootprobe.LootSimCommand;
import com.example.lootworks.lootprobe.LootrCompat;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

@Mod(LootWorks.MOD_ID)
public class LootWorks {
    public static final String MOD_ID = "lootworks";
    // 日志名用 mod_id，整合包日志里显示为 [lootworks/]，方便搜索
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    // 宝藏工坊创造模式物品栏
    public static final RegistryObject<CreativeModeTab> LOOTWORKS_TAB = CREATIVE_MODE_TABS.register("lootworks",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.lootworks"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> LootProbeItems.CHEST_DETECTOR.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(LootProbeItems.CHEST_DETECTOR.get());
                        output.accept(LootProbeItems.SKELETON_KEY.get());
                        output.accept(LootProbeItems.TREASURE_CORE.get());
                        output.accept(LootProbeItems.LUCKY_DUST.get());
                        output.accept(LootProbeItems.FATE_CRYSTAL.get());
                        output.accept(LootProbeItems.UNCHARGED_DESTINED_CRYSTAL.get());
                        output.accept(LootProbeItems.DESTINED_CRYSTAL.get());
                        output.accept(LootProbeItems.RELIC_METAL_INGOT.get());
                        // 遗物合金锭暂未实装后续仪器，先隐藏出创造栏（待后续版本开放）
                        // output.accept(LootProbeItems.RELIC_ALLOY_INGOT.get());
                    }).build());

    public LootWorks(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);

        CREATIVE_MODE_TABS.register(modEventBus);
        LootProbeItems.register(modEventBus);
        LootProbeRecipes.register(modEventBus); // 检测器用的「消耗整个核心」配方类型
        LootProbeMenus.register(modEventBus);
        FateResonance.register(modEventBus); // 晶胚携带时的命运共鸣状态
        ChestDetectorResultPayload.register();
        MinecraftForge.EVENT_BUS.addListener(FateMaterial::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(FateMaterial::onDatapackSync);
        MinecraftForge.EVENT_BUS.addListener(FateResonance::onPlayerTick); // 背包中的晶胚随机充能
        MinecraftForge.EVENT_BUS.addListener(FateHighlights::onOpen);
        MinecraftForge.EVENT_BUS.addListener(FateHighlights::onTick);
        MinecraftForge.EVENT_BUS.addListener(CoreReveal::onLevelTick);     // 宝藏核心出场特效推进
        MinecraftForge.EVENT_BUS.addListener(CoreReveal::onServerStopped); // 关服清理
        MinecraftForge.EVENT_BUS.addListener(CoreReveal::onEntityJoin);    // 淬炼被打断的铜锭重新加载时恢复重力
        MinecraftForge.EVENT_BUS.addListener(LootIndex::onServerTick); // 按时间预算分批建立索引
        MinecraftForge.EVENT_BUS.addListener(LootIndex::onChunkLoad); // 区块加载时预取其中宝箱的表
        MinecraftForge.EVENT_BUS.addListener(LootIndex::onDatapackSync); // 数据包重载后清空缓存
        MinecraftForge.EVENT_BUS.addListener(LootIndex::onServerStopped); // 关服时释放内存（缓存已随存档保存）

        // 仅开发环境注册调试命令，正式打包发行版自动跳过
        if (!FMLLoader.isProduction()) {
            MinecraftForge.EVENT_BUS.addListener(LootSimCommand::register);
            MinecraftForge.EVENT_BUS.addListener(LootIndexCommand::register);
        }

        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 启动时记录兼容状态，整合包里排查问题先看这一行
        LOGGER.info("宝箱检测已加载：Lootr 兼容{}", LootrCompat.describe());
    }
}
