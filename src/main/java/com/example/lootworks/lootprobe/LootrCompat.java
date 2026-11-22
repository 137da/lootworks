// SPDX-License-Identifier: LGPL-3.0
package com.example.lootworks.lootprobe;

import java.util.function.Supplier;
import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.fml.ModList;
import noobanidus.mods.lootr.api.LootrAPI;
import noobanidus.mods.lootr.api.blockentity.ILootBlockEntity;
import noobanidus.mods.lootr.api.inventory.ILootrInventory;
import noobanidus.mods.lootr.block.entities.LootrBarrelBlockEntity;
import noobanidus.mods.lootr.block.entities.LootrShulkerBlockEntity;
import noobanidus.mods.lootr.config.ConfigManager;
import noobanidus.mods.lootr.event.HandleBreak;

/** Lootr 是可选依赖，只有静态子类引用其类型。 */
public final class LootrCompat {
    private static final String MOD_ID = "lootr";
    private static boolean disabled; // API 不兼容后停止访问 Lootr 容器

    private LootrCompat() {}

    enum BreakCheck {
        ALLOWED,
        ALREADY_OPENED,
        DENIED
    }

    public record LootrView(ResourceLocation table, boolean opened) {}

    public static String describe() {
        return ModList.get().getModContainerById(MOD_ID)
                .map(mod -> "开启（Lootr " + mod.getModInfo().getVersion() + "）")
                .orElse("未启用（没有安装 Lootr）");
    }

    private static <T> T guard(String action, Supplier<T> call, T fallback) {
        if (disabled) return fallback;
        try {
            return call.get();
        } catch (LinkageError error) {
            disabled = true;
            LootWorks.LOGGER.error("Lootr 兼容{}时发现 API 不兼容，已停用 Lootr 兼容", action, error);
        } catch (RuntimeException error) {
            LootWorks.LOGGER.error("Lootr 兼容{}出错", action, error);
        }
        return fallback;
    }

    public static boolean isLootr(BlockEntity blockEntity) {
        ResourceLocation type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        return type != null && MOD_ID.equals(type.getNamespace());
    }

    static boolean isBarrelOrShulker(BlockEntity blockEntity) {
        return ModList.get().isLoaded(MOD_ID)
                && guard("判断容器类型", () -> Impl.isBarrelOrShulker(blockEntity), false);
    }

    @Nullable
    static LootrView inspect(BlockEntity blockEntity, ServerPlayer player) {
        return isLootr(blockEntity) ? guard("读取开箱状态", () -> Impl.inspect(blockEntity, player), null) : null;
    }

    @Nullable
    static ResourceLocation lootTable(BlockEntity blockEntity) {
        return isLootr(blockEntity) ? guard("读取战利品表", () -> Impl.table(blockEntity), null) : null;
    }

    static BreakCheck checkBreak(RandomizableContainerBlockEntity container, ServerPlayer player) {
        return isLootr(container) ? guard("检查拆箱", () -> Impl.checkBreak(container, player), BreakCheck.DENIED)
                : BreakCheck.ALLOWED;
    }

    /** 将个人库存关联到当前维度里持久保存标记的箱子。 */
    @Nullable
    static BlockEntity inventoryChest(Container inventory, ServerPlayer player) {
        return ModList.get().isLoaded(MOD_ID)
                ? guard("定位个人库存", () -> Impl.inventoryChest(inventory, player), null)
                : null;
    }

    private static final class Impl {
        @Nullable
        static BlockEntity inventoryChest(Container inventory, ServerPlayer player) {
            if (!(inventory instanceof ILootrInventory lootr)) return null;
            return player.serverLevel().getBlockEntity(lootr.getPos());
        }
        static boolean isBarrelOrShulker(BlockEntity blockEntity) {
            return blockEntity instanceof LootrBarrelBlockEntity || blockEntity instanceof LootrShulkerBlockEntity;
        }

        static ResourceLocation table(BlockEntity blockEntity) {
            return blockEntity instanceof ILootBlockEntity lootr ? lootr.getTable() : null;
        }

        static LootrView inspect(BlockEntity blockEntity, ServerPlayer player) {
            if (!(blockEntity instanceof ILootBlockEntity lootr) || lootr.getTable() == null) return null;
            return new LootrView(lootr.getTable(), lootr.getOpeners().contains(player.getUUID()));
        }

        static BreakCheck checkBreak(RandomizableContainerBlockEntity container, ServerPlayer player) {
            LootrView view = inspect(container, player);
            if (view == null) return BreakCheck.DENIED;
            if (view.opened()) return BreakCheck.ALREADY_OPENED;
            if (!container.canOpen(player)) return BreakCheck.DENIED;
            boolean creative = player.getAbilities().instabuild;
            boolean allowed = ConfigManager.ENABLE_BREAK.get()
                    || (LootrAPI.isFakePlayer(player) && ConfigManager.ENABLE_FAKE_PLAYER_BREAK.get());
            if (!allowed && ConfigManager.DISABLE_BREAK.get() && !(creative && player.isShiftKeyDown())) {
                String message = creative ? "lootr.message.cannot_break_sneak" : "lootr.message.cannot_break";
                player.displayClientMessage(Component.translatable(message).setStyle(HandleBreak.getChatStyle()), false);
                return BreakCheck.DENIED;
            }
            return BreakCheck.ALLOWED;
        }
    }
}
