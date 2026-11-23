package com.example.lootworks.lootprobe;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 检测与改命菜单：概率列表 + 1 个改命材料槽 + 玩家背包。箱子的身份和有效性由服务端校验。
 * <p>
 * 「选目标」和「改命」通过 Forge 通道发给服务端，由 {@link #clickMenuButton} 处理：
 * 按钮 0 = 改命，1 = 深度搜索，2 = 取消选择，3+i = 选中结果列表里第 i 个物品。两端的列表来自同一份同步包，顺序一致。
 * <p>
 * 深度搜索完成后服务端换上新结果并清空选择；客户端收到后按物品重新定位选中项，再把选择发回服务端，
 * 这样即使玩家在换结果的瞬间点了旧列表，最终两端的选择也一致。
 */
public final class ChestDetectorMenu extends AbstractContainerMenu {
    public static final int BUTTON_FATE = 0;         // 按钮编号：执行改命
    public static final int BUTTON_DEEP_SEARCH = 1;  // 按钮编号：深度搜索
    public static final int BUTTON_DESELECT = 2;     // 按钮编号：取消选择（换结果后目标不在新列表里时用）
    public static final int BUTTON_SELECT_BASE = 3;  // 按钮编号：选目标，编号 - 3 = 列表下标
    private static final int PROGRESS_INTERVAL_TICKS = 10; // 深度搜索进度每隔多少 tick 同步一次

    // 槽位坐标，界面按同样的数值画槽位框
    public static final int MATERIAL_X = 9;
    public static final int MATERIAL_Y = 124;
    public static final int INVENTORY_X = 71;
    public static final int INVENTORY_Y = 156;
    public static final int HOTBAR_Y = INVENTORY_Y + 58;

    private static final int MATERIAL_SLOT = 0;                       // 材料槽下标
    private static final int PLAYER_SLOTS_START = 1;                  // 背包槽起始下标
    private static final int PLAYER_SLOTS_END = PLAYER_SLOTS_START + 36;

    @Nullable
    private final ServerTarget target; // 客户端只需要显示服务端返回的结果
    @Nullable
    private ChestDetectorResultPayload result; // 为空表示正在分析
    private final Container material = new SimpleContainer(1); // 改命材料槽，关窗时退回背包
    private int selected = -1; // 选中的目标在结果列表里的下标，-1 = 未选
    @Nullable
    private LootIndex.DeepProgress deepProgress; // 服务端：上次发出的深度搜索进度；客户端：收到的进度
    private int progressTicks; // 距上次同步进度的 tick 数
    private boolean deepSyncBroken; // 深度搜索同步出过错：之后不再同步，避免每 tick 重复报错

    private record ServerTarget(ServerPlayer player, ServerLevel level, BlockEntity chest, ResourceLocation table) {}

    /** 客户端开窗时创建空菜单，随后接收概率结果。 */
    static ChestDetectorMenu client(int containerId, Inventory inventory) {
        return new ChestDetectorMenu(containerId, inventory, null);
    }

    /** 服务端创建与实际箱子绑定的菜单。 */
    ChestDetectorMenu(int containerId, ServerPlayer player, BlockEntity chest, ResourceLocation table) {
        this(containerId, player.getInventory(), new ServerTarget(player, player.serverLevel(), chest, table));
    }

    private ChestDetectorMenu(int containerId, Inventory inventory, @Nullable ServerTarget target) {
        super(LootProbeMenus.CHEST_DETECTOR.get(), containerId);
        this.target = target;

        addSlot(new Slot(material, 0, MATERIAL_X, MATERIAL_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return FateMaterial.of(stack, target == null) != null; // 只收数据包注册的改命材料
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INVENTORY_X + column * 18, HOTBAR_Y));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (target == null) return true;
        BlockPos position = target.chest().getBlockPos(); // 按原版容器的交互距离校验
        ServerLevel level = target.level();
        if (player.level() != level || !level.mayInteract(player, position)
                || player.distanceToSqr(Vec3.atCenterOf(position)) > 64.0
                || !level.isLoaded(position) || level.getBlockEntity(position) != target.chest()) {
            return false;
        }
        LootrCompat.LootrView lootr = LootrCompat.inspect(target.chest(), target.player());
        if (lootr != null) {
            return !lootr.opened() && target.table().equals(lootr.table());
        }
        return !LootrCompat.isLootr(target.chest())
                && target.chest() instanceof RandomizableContainerBlockEntity container && target.table().equals(container.lootTable);
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (target == null) return;
        try {
            if (!stillValid(target.player())) return;
            LootIndex index = LootIndex.get(target.level().getServer());
            var tableId = target.table(); // 查询时不改变预取和优先队列的顺序
            LootIndex.Entry entry = index.cached(target.level().dimension(), tableId);
            if (result == null) {
                if (entry != null || !index.hasTable(tableId)) {
                    result = ChestDetectorResultPayload.fromEntry(containerId, entry);
                    ChestDetectorResultPayload.send(target.player(), result);
                }
                return;
            }
            if (!deepSyncBroken) syncDeepSearch(index, entry);
        } catch (RuntimeException error) {
            if (result != null) {
                // 已有结果时只是深度搜索同步出错：保留结果，停掉进度显示
                deepProgress = null;
                deepSyncBroken = true;
                LootProbeErrors.report(target.player(), "深度搜索同步", describe(), error);
                return;
            }
            // 每 tick 都会调用，出错时发一个「采样失败」的空结果，避免下个 tick 重复报错
            result = ChestDetectorResultPayload.fromEntry(containerId, null);
            ChestDetectorResultPayload.send(target.player(), result);
            LootProbeErrors.report(target.player(), "检测结果同步", describe(), error);
        }
    }

    /**
     * 同步深度搜索：进行中按间隔发进度（别人发起的同表搜索也会显示）；
     * 缓存里的结果比当前的新（采样次数不同）就换上并发给客户端。
     */
    private void syncDeepSearch(LootIndex index, @Nullable LootIndex.Entry entry) {
        ServerPlayer player = target.player();
        LootIndex.DeepProgress progress = index.deepProgress(target.level().dimension(), target.table());
        if (progress != null) {
            if (deepProgress == null || ++progressTicks >= PROGRESS_INTERVAL_TICKS) {
                progressTicks = 0;
                deepProgress = progress;
                ChestDetectorResultPayload.send(player,
                        new ChestDetectorResultPayload.DeepSearchProgress(containerId, progress.runs(), progress.target()));
            }
            return;
        }
        if (deepProgress != null) {
            deepProgress = null;
            ChestDetectorResultPayload.send(player, new ChestDetectorResultPayload.DeepSearchProgress(containerId, 0, 0));
        }
        if (entry != null && !entry.samplingFailed() && entry.runs() != result.runs()) {
            result = ChestDetectorResultPayload.fromEntry(containerId, entry);
            selected = -1; // 客户端按物品重新定位后会把选择发回来
            ChestDetectorResultPayload.send(player, result);
        }
    }

    /** 日志用的现场信息 */
    private String describe() {
        return "玩家 " + target.player().getScoreboardName() + "，坐标 " + target.chest().getBlockPos().toShortString()
                + "，战利品表 " + target.table();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id >= BUTTON_SELECT_BASE) return select(id - BUTTON_SELECT_BASE);
        if (id == BUTTON_DESELECT) {
            selected = -1;
            return true;
        }
        if (id == BUTTON_DEEP_SEARCH && target != null) {
            try {
                deepSearch();
            } catch (RuntimeException error) {
                LootProbeErrors.report(player, "深度搜索", describe(), error);
            }
            return true;
        }
        if (id == BUTTON_FATE && target != null) {
            try {
                fate();
            } catch (RuntimeException error) {
                LootProbeErrors.report(player, "改命", describe(), error); // 材料在扣除前出错时原样留在槽里
            }
            return true;
        }
        return false;
    }

    /** 选中目标；客户端界面也直接调用它更新本地显示，再发包同步给服务端 */
    public boolean select(int index) {
        if (result == null || index < 0 || index >= result.drops().size()) return false;
        selected = index;
        return true;
    }

    /** 服务端发起深度搜索；条件不满足（已到上限、正在搜索）时什么也不做 */
    private void deepSearch() {
        ServerPlayer player = target.player();
        if (result == null || result.runs() == 0 || !stillValid(player)) return;
        boolean started = LootIndex.get(target.level().getServer())
                .deepen(target.level(), target.table(), target.chest().getBlockPos());
        if (started) LootWorks.LOGGER.info("深度搜索开始：{}", describe());
    }

    /** 服务端执行改命：校验 → 算新概率 → 搜种子 → 扣材料，成功后关窗 */
    private void fate() {
        ServerPlayer player = target.player();
        if (result == null || result.runs() == 0 || selected < 0 || !stillValid(player)) return;
        ItemStack stack = material.getItem(0);
        FateMaterial fateMaterial = FateMaterial.of(stack, false);
        if (fateMaterial == null) return;

        ChestDetectorResultPayload.Drop drop = result.drops().get(selected);
        double p = drop.chance();
        if (p <= 0.0 || p >= 1.0) return; // 未得出有效概率或已必出的物品不再消耗材料改命
        double q = fateMaterial.boost(p, stack);
        FateService.Outcome outcome = FateService.fate(player, target.chest(), target.table(),
                drop.item(), drop.potionGroup(), p, q);
        switch (outcome) {
            case SUCCESS -> {
                if (stack.is(LootProbeItems.DESTINED_CRYSTAL.get())) {
                    ItemStack crystal = FateResonance.discharge(stack);
                    stack.shrink(1);
                    // 旧存档允许存在成品堆叠，只转换本次用掉的那一颗。
                    if (stack.isEmpty()) {
                        material.setItem(0, crystal);
                    } else {
                        player.getInventory().placeItemBackInInventory(crystal);
                    }
                    LootProbeAdvancements.award(player, LootProbeAdvancements.ANCHOR_FATE,
                            LootProbeAdvancements.ANCHOR_FATE_CRITERION);
                } else {
                    stack.shrink(fateMaterial.usedCount(stack));
                }
                material.setChanged();
                player.displayClientMessage(Component.translatable("message.lootworks.chest_detector.fated"), true);
                player.closeContainer();
            }
            case LOCKED -> {
                player.displayClientMessage(Component.translatable("message.lootworks.chest_detector.locked"), true);
                player.closeContainer();
            }
            case SEARCH_FAILED ->
                    player.displayClientMessage(Component.translatable("message.lootworks.chest_detector.fate_failed"), true);
        }
    }

    /** 返回当前概率结果，尚未收到则为空。 */
    @Nullable
    public ChestDetectorResultPayload result() {
        return result;
    }

    /** 选中目标的下标，-1 = 未选 */
    public int selected() {
        return selected;
    }

    /** 材料槽里的物品（界面预览用） */
    public ItemStack materialStack() {
        return material.getItem(0);
    }

    /** 只把结果交给与同步包匹配的客户端菜单。 */
    public void receive(ChestDetectorResultPayload update) {
        if (target != null || containerId != update.containerId()) return;
        ChestDetectorResultPayload previous = result;
        result = update;
        if (previous == null || selected < 0) return; // 没选目标时服务端换结果后本来就是未选
        // 深度搜索换了结果：按物品找回原来选中的目标，并告诉服务端（服务端换结果时已清空选择）
        int remapped = remapSelection(previous, update, selected);
        selected = remapped;
        ChestDetectorResultPayload.sendAction(containerId, remapped >= 0 ? BUTTON_SELECT_BASE + remapped : BUTTON_DESELECT);
    }

    /** 客户端收到深度搜索进度；target 为 0 表示结束 */
    public void receiveProgress(ChestDetectorResultPayload.DeepSearchProgress update) {
        if (target != null || containerId != update.containerId()) return;
        deepProgress = update.target() > 0 ? new LootIndex.DeepProgress(update.runs(), update.target()) : null;
    }

    /** 深度搜索进度，没有进行中的搜索时为空（界面显示用） */
    @Nullable
    public LootIndex.DeepProgress deepProgress() {
        return deepProgress;
    }

    /** 在新结果里找同一个物品（药水栏单独算），找不到返回 -1 */
    private static int remapSelection(ChestDetectorResultPayload previous, ChestDetectorResultPayload update, int index) {
        if (index < 0 || index >= previous.drops().size()) return -1;
        ChestDetectorResultPayload.Drop old = previous.drops().get(index);
        for (int i = 0; i < update.drops().size(); i++) {
            ChestDetectorResultPayload.Drop drop = update.drops().get(i);
            if (drop.item() == old.item() && drop.potionGroup() == old.potionGroup()) return i;
        }
        return -1;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        clearContainer(player, material); // 和铁砧一样，关窗时材料退回背包，背包满了掉在脚下
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index == MATERIAL_SLOT) {
            if (!moveItemStackTo(stack, PLAYER_SLOTS_START, PLAYER_SLOTS_END, true)) return ItemStack.EMPTY;
        } else if (FateMaterial.of(stack, target == null) != null) {
            if (!moveItemStackTo(stack, MATERIAL_SLOT, MATERIAL_SLOT + 1, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY; // 其他物品在背包内不做快速移动
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }
}
