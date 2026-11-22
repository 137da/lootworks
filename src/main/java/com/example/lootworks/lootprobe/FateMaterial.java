package com.example.lootworks.lootprobe;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;

/**
 * 改命材料的属性，由 {@code data/<命名空间>/fate_materials/*.json} 定义。
 * <p>
 * 加新材料只需要写 JSON，整合包也能用数据包加自己的材料。数据映射会同步到客户端，界面据此实时预览新概率。
 *
 * @param curve    改命曲线
 * @param strength 每份的强度（赔率曲线是倍率，幂曲线是指数衰减系数）
 * @param maxCount 一次改命最多消耗几份，多放的留在槽里
 */
public record FateMaterial(FateCurve curve, double strength, int maxCount) {
    public static final Codec<FateMaterial> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            FateCurve.CODEC.fieldOf("curve").forGetter(FateMaterial::curve),
            Codec.doubleRange(0.0, 1000.0).fieldOf("strength").forGetter(FateMaterial::strength),
            ExtraCodecs.POSITIVE_INT.fieldOf("max_count").forGetter(FateMaterial::maxCount)
    ).apply(instance, FateMaterial::new));

    private static final ResourceLocation DEFAULTS = ResourceLocation.fromNamespaceAndPath(LootWorks.MOD_ID, "defaults");
    private static volatile Map<ResourceLocation, FateMaterial> serverMaterials = Map.of();
    private static volatile Map<ResourceLocation, FateMaterial> clientMaterials = Map.of();

    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ReloadListener());
    }

    public static void onDatapackSync(OnDatapackSyncEvent event) {
        var packet = new ChestDetectorResultPayload.Materials(serverMaterials);
        if (event.getPlayer() != null) {
            ChestDetectorResultPayload.send(event.getPlayer(), packet);
        } else {
            event.getPlayerList().getPlayers().forEach(player -> ChestDetectorResultPayload.send(player, packet));
        }
    }

    static void receive(Map<ResourceLocation, FateMaterial> materials) {
        clientMaterials = Map.copyOf(materials);
    }

    @Nullable
    public static FateMaterial of(ItemStack stack, boolean clientSide) {
        return stack.isEmpty() ? null : (clientSide ? clientMaterials : serverMaterials)
                .get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static final class ReloadListener extends SimpleJsonResourceReloadListener {
        private ReloadListener() {
            super(new Gson(), "fate_materials");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            Map<ResourceLocation, FateMaterial> materials = new HashMap<>();
            // 默认文件先加载，其余文件按 ID 排序，可覆盖默认材料。
            files.keySet().stream().sorted(Comparator.comparing((ResourceLocation id) -> !id.equals(DEFAULTS))
                    .thenComparing(id -> id)).forEach(id -> {
                JsonElement file = files.get(id);
                JsonElement values = file.isJsonObject() ? file.getAsJsonObject().get("values") : null;
                if (values == null || !values.isJsonObject()) {
                    LootWorks.LOGGER.warn("忽略改命材料文件 {}：缺少 values 对象", id);
                    return;
                }
                values.getAsJsonObject().entrySet().forEach(entry -> {
                    ResourceLocation item = ResourceLocation.tryParse(entry.getKey());
                    if (item == null || !BuiltInRegistries.ITEM.containsKey(item)) return;
                    CODEC.parse(JsonOps.INSTANCE, entry.getValue()).resultOrPartial(error ->
                            LootWorks.LOGGER.warn("改命材料 {}（{}）：{}", item, id, error))
                            .ifPresent(material -> materials.put(item, material));
                });
            });
            serverMaterials = Map.copyOf(materials);
        }
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
