package com.example.lootworks.lootprobe;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.fml.ModList;
import noobanidus.mods.lootr.common.api.LootrAPI;
import noobanidus.mods.lootr.common.api.data.blockentity.ILootrBlockEntity;
import noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory;
import noobanidus.mods.lootr.common.block.entity.LootrBarrelBlockEntity;
import noobanidus.mods.lootr.common.block.entity.LootrShulkerBlockEntity;

/**
 * Lootr 兼容：检测器读取玩家各自的开箱状态，钥匙拆除方块前也用这个状态检查。
 * <p>
 * 类加载隔离：外层类不碰任何 Lootr 类型，真正调用 Lootr 的代码放在嵌套类 {@link Impl} 里
 * （编译后是独立的 class 文件，首次调用时才加载），没装 Lootr 时只要不调用它就不会崩溃。
 */
public final class LootrCompat {
    private LootrCompat() {}

    private static final String MOD_ID = "lootr";
    private static Boolean loaded; // 缓存 Lootr 是否加载
    private static boolean disabled; // Lootr 的 API 和编译时不一致（缺类、缺方法）时关闭兼容，之后按原版处理

    /** Lootr 对这次拆箱的检查结果。 */
    enum BreakCheck {
        ALLOWED,
        ALREADY_OPENED,
        DENIED
    }

    public static boolean isLoaded() {
        if (loaded == null) loaded = ModList.get().isLoaded(MOD_ID);
        return loaded && !disabled;
    }

    /** 启动日志用：Lootr 兼容状态与版本 */
    public static String describe() {
        return ModList.get().getModContainerById(MOD_ID)
                .map(mod -> "开启（Lootr " + mod.getModInfo().getVersion() + "）")
                .orElse("未启用（没有安装 Lootr）");
    }

    /**
     * 调用 Lootr 的统一保护，避免整合包里的 Lootr 出问题导致游戏崩溃：
     * - LinkageError：整合包里的 Lootr 版本和编译时 API 不一致，以后每次调用都会失败，直接关闭兼容；
     * - RuntimeException：个别容器的偶发问题，记录后本次按 fallback 处理。
     */
    private static <T> T guard(String action, Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (LinkageError error) {
            disabled = true;
            LootWorks.LOGGER.error("Lootr 兼容{}时找不到对应的类或方法，可能是 Lootr 版本不兼容，已关闭 Lootr 兼容（{}）",
                    action, describe(), error);
            return fallback;
        } catch (RuntimeException error) {
            LootWorks.LOGGER.error("Lootr 兼容{}出错，本次按默认处理", action, error);
            return fallback;
        }
    }

    /** 只检查权限，钥匙接下来直接拆除容器，无需生成个人库存。 */
    static BreakCheck checkBreak(BlockEntity blockEntity, ServerPlayer player) {
        if (!isLoaded()) return BreakCheck.ALLOWED;
        // 出错时拒绝拆箱：宁可这次拆不了，也不误拆别人的 Lootr 箱子
        return guard("检查拆箱", () -> Impl.checkBreak(blockEntity, player), BreakCheck.DENIED);
    }

    /**
     * Lootr 容器在某个玩家眼里的状态（检测器用）
     *
     * @param table  战利品表（Lootr 常驻，不会因开箱清空）
     * @param opened 该玩家是否已开过自己那一份
     */
    public record LootrView(ResourceKey<LootTable> table, boolean opened) {}

    /** 是否为 Lootr 的木桶或潜影盒（它们不继承原版的木桶、潜影盒方块实体） */
    static boolean isBarrelOrShulker(BlockEntity blockEntity) {
        return isLoaded() && guard("判断容器类型", () -> Impl.isBarrelOrShulker(blockEntity), false);
    }

    /** 查询 Lootr 容器状态；不是 Lootr 容器（或没装 Lootr）返回 null，按原版处理 */
    @Nullable
    public static LootrView inspect(BlockEntity blockEntity, ServerPlayer player) {
        if (!isLoaded()) return null;
        return guard("读取开箱状态", () -> Impl.inspect(blockEntity, player), null);
    }

    /** 读取 Lootr 容器的常驻战利品表（不区分玩家，索引预取用）；不是 Lootr 容器返回 null */
    @Nullable
    public static ResourceKey<LootTable> lootTable(BlockEntity blockEntity) {
        if (!isLoaded()) return null;
        return guard("读取战利品表", () -> Impl.lootTable(blockEntity), null);
    }

    /** 将个人库存关联到当前维度里持久保存标记的箱子。 */
    @Nullable
    static BlockEntity inventoryChest(Container inventory, ServerPlayer player) {
        return isLoaded() ? guard("定位个人库存", () -> Impl.inventoryChest(inventory, player), null) : null;
    }

    /** 只有这里引用 Lootr 的类 */
    private static final class Impl {
        @Nullable
        static BlockEntity inventoryChest(Container inventory, ServerPlayer player) {
            if (!(inventory instanceof ILootrInventory lootr)) return null;
            var info = lootr.getInfo();
            return player.serverLevel().dimension().equals(info.getInfoDimension())
                    ? player.serverLevel().getBlockEntity(info.getInfoPos()) : null;
        }

        static boolean isBarrelOrShulker(BlockEntity blockEntity) {
            return blockEntity instanceof LootrBarrelBlockEntity || blockEntity instanceof LootrShulkerBlockEntity;
        }

        @Nullable
        static ResourceKey<LootTable> lootTable(BlockEntity blockEntity) {
            ILootrBlockEntity lootr = LootrAPI.resolveBlockEntity(blockEntity);
            return lootr == null || !lootr.hasLootTable() ? null : lootr.getInfoLootTable();
        }

        /** 用 1.10 与 1.11 共有的 getActualOpeners 判断玩家是否已开过此箱，兼容 Lootr 1.10.x */
        private static boolean hasPlayerOpened(ILootrBlockEntity lootr, ServerPlayer player) {
            Set<UUID> openers = lootr.getActualOpeners();
            return openers != null && openers.contains(player.getUUID());
        }

        @Nullable
        static LootrView inspect(BlockEntity blockEntity, ServerPlayer player) {
            ILootrBlockEntity lootr = LootrAPI.resolveBlockEntity(blockEntity);
            if (lootr == null || !lootr.hasLootTable()) return null;
            return new LootrView(lootr.getInfoLootTable(), hasPlayerOpened(lootr, player));
        }

        static BreakCheck checkBreak(BlockEntity blockEntity, ServerPlayer player) {
            ILootrBlockEntity lootr = LootrAPI.resolveBlockEntity(blockEntity);
            if (lootr == null || !lootr.hasLootTable()) return BreakCheck.ALLOWED;
            if (hasPlayerOpened(lootr, player)) return BreakCheck.ALREADY_OPENED;
            try {
                // canPlayerOpen / informPlayerCannotOpen 为 Lootr 1.11+ 新增方法，1.10.x 上优雅跳过
                if (!lootr.canPlayerOpen(player)) {
                    lootr.informPlayerCannotOpen(player);
                    return BreakCheck.DENIED;
                }
            } catch (NoSuchMethodError ignored) {
                // Lootr 1.10.x 无此方法，跳过权限细检
            }

            // 代码拆除绕过挖掘事件，单独保留 Lootr 的服务器禁拆设置及其例外。
            boolean creative = player.getAbilities().instabuild;
            if (LootrAPI.isBreakDisabled() && !LootrAPI.canDestroyOrBreak(player)
                    && !(creative && player.isShiftKeyDown())) {
                String message = creative ? "lootr.message.cannot_break_sneak" : "lootr.message.cannot_break";
                player.displayClientMessage(Component.translatable(message).setStyle(LootrAPI.getChatStyle()), false);
                return BreakCheck.DENIED;
            }
            return BreakCheck.ALLOWED;
        }
    }
}
