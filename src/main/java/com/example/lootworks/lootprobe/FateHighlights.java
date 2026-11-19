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

import com.example.lootworks.LootWorks;
import com.example.lootworks.mixin.CompoundContainerAccess;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** 在真实开箱时标记改命目标所在的格子，记录跟随箱子存档。 */
public final class FateHighlights {
    private static final UUID SHARED = new UUID(0L, 0L); // 原版箱子的战利品由所有玩家共享
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, LootWorks.MODID);
    private static final DeferredHolder<AttachmentType<?>, AttachmentType<Map<UUID, Marking>>> TYPE =
            ATTACHMENTS.register("fate_highlights", () -> AttachmentType.<Map<UUID, Marking>>builder(() -> new HashMap<>())
                    .serialize(Codec.unboundedMap(UUIDUtil.STRING_CODEC, Marking.CODEC)
                            .xmap(HashMap::new, entries -> entries), entries -> !entries.isEmpty()).build());

    // 存档用快照，内存也比较原栈引用。交换两个外观与数量完全相同的物品时仍应清掉标记。
    private static final Map<Container, Origin> LIVE = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<AbstractContainerMenu, View> VIEWS = new WeakHashMap<>();

    private FateHighlights() {}

    private record Target(ResourceLocation itemId, boolean potionGroup) {
        static final Codec<Target> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("item").forGetter(Target::itemId),
                Codec.BOOL.fieldOf("potion_group").forGetter(Target::potionGroup)
        ).apply(instance, Target::new));

        boolean matches(ItemStack stack) {
            return !stack.isEmpty() && itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    && potionGroup == LootSimulator.Sampling.isPotionGroup(stack);
        }
    }

    private record MarkedSlot(int slot, ItemStack snapshot) {
        static final Codec<MarkedSlot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("slot").forGetter(MarkedSlot::slot),
                ItemStack.CODEC.fieldOf("stack").forGetter(MarkedSlot::snapshot)
        ).apply(instance, MarkedSlot::new));
    }

    private record Marking(Optional<Target> pending, List<MarkedSlot> slots) {
        static final Codec<Marking> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Target.CODEC.optionalFieldOf("pending").forGetter(Marking::pending),
                MarkedSlot.CODEC.listOf().optionalFieldOf("slots", List.of()).forGetter(Marking::slots)
        ).apply(instance, Marking::new));
    }

    private record Origin(WeakReference<BlockEntity> chest, UUID owner, Map<Integer, ItemStack> stacks) {}
    private record SlotView(int menuIndex, Container inventory, int inventoryIndex) {}
    private record View(List<SlotView> slots, List<Container> inventories, long mask) {}

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
    }

    static void rememberTarget(BlockEntity chest, UUID player, boolean personal, Item item, boolean potionGroup) {
        Target target = new Target(BuiltInRegistries.ITEM.getKey(item), potionGroup); // 用注册 ID 存放目标，随箱子保存
        chest.getData(TYPE).put(personal ? player : SHARED, new Marking(Optional.of(target), List.of()));
        chest.setChanged();
    }

    static void reveal(BlockEntity chest, Container inventory, ServerPlayer player) {
        if (!chest.hasData(TYPE)) return;
        UUID owner = chest == inventory ? SHARED : player.getUUID(); // Lootr 的个人箱库存与方块实体分开
        Marking marking = chest.getData(TYPE).get(owner);
        if (marking == null || marking.pending().isEmpty()) return;

        Target target = marking.pending().orElseThrow();
        List<MarkedSlot> slots = new ArrayList<>();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index);
            if (target.matches(stack)) slots.add(new MarkedSlot(index, stack.copy()));
        }
        if (slots.isEmpty()) {
            chest.getData(TYPE).remove(owner);
        } else {
            chest.getData(TYPE).put(owner, new Marking(Optional.empty(), List.copyOf(slots)));
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
        Marking marking = chest.getData(TYPE).get(origin.owner());
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
            chest.getData(TYPE).remove(origin.owner());
            LIVE.remove(inventory);
        } else {
            chest.getData(TYPE).put(origin.owner(), new Marking(Optional.empty(), remaining));
        }
        chest.setChanged();
    }

    @Nullable
    private static Origin bind(Container inventory, BlockEntity chest, UUID owner) {
        if (!chest.hasData(TYPE)) return null;
        Marking marking = chest.getData(TYPE).get(owner);
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
        return inventory instanceof RandomizableContainer loot && loot.getLootTable() != null;
    }

    public static void onOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !player.connection.hasChannel(FateHighlightPayload.TYPE)) return;
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

    public static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
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
        PacketDistributor.sendToPlayer(player, new FateHighlightPayload(menu.containerId, mask));
        if (mask == 0L) {
            VIEWS.remove(menu);
        } else {
            VIEWS.put(menu, new View(view.slots(), view.inventories(), mask));
        }
    }
}
