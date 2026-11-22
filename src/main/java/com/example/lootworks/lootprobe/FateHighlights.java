package com.example.lootworks.lootprobe;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.example.lootworks.mixin.CompoundContainerAccess;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;

/** 在真实开箱时标记改命目标所在的格子，记录跟随箱子存档（存于 ForgeData）。 */
public final class FateHighlights {
    private static final String DATA_KEY = "lootworks:fate_highlights"; // 独立的改命目标高亮记录
    private static final UUID SHARED = new UUID(0L, 0L); // 原版箱子的战利品由所有玩家共享

    // 存档用快照，内存也比较原栈引用。交换两个外观与数量完全相同的物品时仍应清掉标记。
    private static final Map<Container, Origin> LIVE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<AbstractContainerMenu, View> VIEWS = new WeakHashMap<>();

    private FateHighlights() {}

    private record Target(ResourceLocation itemId, boolean potionGroup) {
        boolean matches(ItemStack stack) {
            return !stack.isEmpty() && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    && potionGroup == LootSimulator.Sampling.isPotionGroup(stack);
        }
    }

    private record MarkedSlot(int slot, ItemStack snapshot) {}
    private record Marking(Optional<Target> pending, List<MarkedSlot> slots) {}
    private record Origin(WeakReference<BlockEntity> chest, UUID owner, Map<Integer, ItemStack> stacks) {}
    private record SlotView(int menuIndex, Container inventory, int inventoryIndex) {}
    private record View(List<SlotView> slots, List<Container> inventories, long mask) {}

    @Nullable
    private static Marking getMarking(BlockEntity chest, UUID owner) {
        CompoundTag persistent = chest.getPersistentData();
        if (!persistent.contains(DATA_KEY, Tag.TAG_COMPOUND)) return null;
        CompoundTag all = persistent.getCompound(DATA_KEY);
        String key = owner.toString();
        if (!all.contains(key, Tag.TAG_COMPOUND)) return null;
        CompoundTag tag = all.getCompound(key);

        Optional<Target> pending = Optional.empty();
        if (tag.contains("pending", Tag.TAG_COMPOUND)) {
            CompoundTag pTag = tag.getCompound("pending");
            ResourceLocation itemId = ResourceLocation.tryParse(pTag.getString("item"));
            if (itemId != null) {
                pending = Optional.of(new Target(itemId, pTag.getBoolean("potion_group")));
            }
        }
        List<MarkedSlot> slots = new ArrayList<>();
        if (tag.contains("slots", Tag.TAG_LIST)) {
            ListTag list = tag.getList("slots", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag sTag = list.getCompound(i);
                slots.add(new MarkedSlot(sTag.getInt("slot"), ItemStack.of(sTag.getCompound("stack"))));
            }
        }
        return new Marking(pending, List.copyOf(slots));
    }

    private static void putMarking(BlockEntity chest, UUID owner, Marking marking) {
        CompoundTag persistent = chest.getPersistentData();
        CompoundTag all = persistent.getCompound(DATA_KEY);
        CompoundTag tag = new CompoundTag();
        marking.pending().ifPresent(t -> {
            CompoundTag pTag = new CompoundTag();
            pTag.putString("item", t.itemId().toString());
            pTag.putBoolean("potion_group", t.potionGroup());
            tag.put("pending", pTag);
        });
        if (!marking.slots().isEmpty()) {
            ListTag list = new ListTag();
            for (MarkedSlot ms : marking.slots()) {
                CompoundTag sTag = new CompoundTag();
                sTag.putInt("slot", ms.slot());
                sTag.put("stack", ms.snapshot().save(new CompoundTag()));
                list.add(sTag);
            }
            tag.put("slots", list);
        }
        all.put(owner.toString(), tag);
        persistent.put(DATA_KEY, all);
    }

    private static void removeMarking(BlockEntity chest, UUID owner) {
        CompoundTag persistent = chest.getPersistentData();
        if (!persistent.contains(DATA_KEY, Tag.TAG_COMPOUND)) return;
        CompoundTag all = persistent.getCompound(DATA_KEY);
        all.remove(owner.toString());
        if (all.isEmpty()) {
            persistent.remove(DATA_KEY);
        } else {
            persistent.put(DATA_KEY, all);
        }
    }

    static void rememberTarget(BlockEntity chest, UUID player, boolean personal, Item item, boolean potionGroup) {
        Target target = new Target(BuiltInRegistries.ITEM.getKey(item), potionGroup); // 用注册 ID 存放目标，随箱子保存
        putMarking(chest, personal ? player : SHARED, new Marking(Optional.of(target), List.of()));
        chest.setChanged();
    }

    static void reveal(BlockEntity chest, Container inventory, ServerPlayer player) {
        UUID owner = chest == inventory ? SHARED : player.getUUID(); // Lootr 的个人箱库存与方块实体分开
        Marking marking = getMarking(chest, owner);
        if (marking == null || marking.pending().isEmpty()) return;

        Target target = marking.pending().orElseThrow();
        List<MarkedSlot> slots = new ArrayList<>();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index);
            if (target.matches(stack)) slots.add(new MarkedSlot(index, stack.copy()));
        }
        if (slots.isEmpty()) {
            removeMarking(chest, owner);
        } else {
            putMarking(chest, owner, new Marking(Optional.empty(), List.copyOf(slots)));
            bind(inventory, chest, owner);
        }
        chest.setChanged();
    }

    /** 在普通箱子与 Lootr 个人库存变动时清掉受影响的格子。 */
    public static void onInventoryChanged(Container inventory) {
        if (inventory instanceof BlockEntity chest && !(chest.getLevel() instanceof ServerLevel)) return;
        Origin origin = LIVE.get(inventory);
        if (origin == null && inventory instanceof BlockEntity chest) origin = bind(inventory, chest, SHARED);
        if (origin == null) return;

        BlockEntity chest = origin.chest().get();
        if (chest == null || chest.isRemoved()) {
            LIVE.remove(inventory);
            return;
        }
        Marking marking = getMarking(chest, origin.owner());
        if (marking == null) {
            LIVE.remove(inventory);
            return;
        }
        Map<Integer, ItemStack> originalStacks = origin.stacks();
        List<MarkedSlot> remaining = isUnopened(inventory) ? List.of() : marking.slots().stream().filter(mark -> {
            ItemStack original = originalStacks.get(mark.slot());
            if (original == null) return false;
            ItemStack actual = inventory.getItem(mark.slot());
            return actual == original && ItemStack.matches(actual, mark.snapshot());
        }).toList();
        if (remaining.size() == marking.slots().size()) return;

        // 先更新标记再写脏，避免方块写脏时回到本方法重复清理。
        originalStacks.keySet().retainAll(remaining.stream().map(MarkedSlot::slot).toList());
        if (remaining.isEmpty()) {
            removeMarking(chest, origin.owner());
            LIVE.remove(inventory);
        } else {
            putMarking(chest, origin.owner(), new Marking(Optional.empty(), remaining));
        }
        chest.setChanged();
    }

    @Nullable
    private static Origin bind(Container inventory, BlockEntity chest, UUID owner) {
        Marking marking = getMarking(chest, owner);
        if (marking == null || marking.slots().isEmpty()) return null;
        Map<Integer, ItemStack> stacks = new HashMap<>();
        if (!isUnopened(inventory)) {
            for (MarkedSlot mark : marking.slots()) {
                if (mark.slot() >= 0 && mark.slot() < inventory.getContainerSize()) {
                    stacks.put(mark.slot(), inventory.getItem(mark.slot()));
                }
            }
        }
        Origin origin = new Origin(new WeakReference<>(chest), owner, stacks);
        LIVE.put(inventory, origin);
        return origin;
    }

    private static boolean isUnopened(Container inventory) {
        return inventory instanceof RandomizableContainerBlockEntity loot && loot.lootTable != null;
    }

    public static void onOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AbstractContainerMenu menu = event.getContainer();
        var checked = Collections.newSetFromMap(new IdentityHashMap<Container, Boolean>());
        List<SlotView> slots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.index >= Long.SIZE) break; // 双箱也只有 54 个箱子槽位
            Container inventory = slot.container;
            int index = slot.getContainerSlot();
            if (inventory instanceof CompoundContainer compound) {
                CompoundContainerAccess parts = (CompoundContainerAccess) compound;
                Container first = parts.lootworks$first();
                if (index < first.getContainerSize()) {
                    inventory = first;
                } else {
                    index -= first.getContainerSize();
                    inventory = parts.lootworks$second();
                }
            }
            if (checked.add(inventory)) {
                onInventoryChanged(inventory);
                if (!LIVE.containsKey(inventory) && !(inventory instanceof BlockEntity)) {
                    BlockEntity chest = LootrCompat.inventoryChest(inventory, player);
                    if (chest != null) {
                        bind(inventory, chest, player.getUUID());
                        onInventoryChanged(inventory);
                    }
                }
            }
            Origin origin = LIVE.get(inventory);
            if (origin != null && (origin.owner().equals(SHARED) || origin.owner().equals(player.getUUID()))
                    && origin.stacks().containsKey(index)) {
                slots.add(new SlotView(slot.index, inventory, index));
            }
        }
        if (!slots.isEmpty()) {
            List<Container> inventories = slots.stream().map(SlotView::inventory).distinct().toList();
            sendView(player, menu, new View(List.copyOf(slots), inventories, 0L));
        }
    }

    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        AbstractContainerMenu menu = player.containerMenu;
        View view = VIEWS.get(menu);
        if (view != null) {
            // 补查未通过原版写脏方法修改物品的容器。
            view.inventories().forEach(FateHighlights::onInventoryChanged);
            sendView(player, menu, view);
        }
    }

    private static void sendView(ServerPlayer player, AbstractContainerMenu menu, View view) {
        long mask = 0L;
        for (SlotView slot : view.slots()) {
            Origin origin = LIVE.get(slot.inventory());
            if (origin != null && origin.stacks().containsKey(slot.inventoryIndex())) mask |= 1L << slot.menuIndex();
        }
        if (mask == view.mask()) return;
        FateHighlightPayload.send(player, menu.containerId, mask);
        if (mask == 0L) {
            VIEWS.remove(menu);
        } else {
            VIEWS.put(menu, new View(view.slots(), view.inventories(), mask));
        }
    }
}
