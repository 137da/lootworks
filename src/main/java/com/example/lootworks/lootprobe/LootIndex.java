package com.example.lootworks.lootprobe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端的战利品概率索引，按「维度 + 表」缓存，所有模拟都在服务端主线程推进。
 * <p>
 * 触发方式（不再进世界就全量模拟）：
 * <ul>
 *   <li>区块加载：扫描区块里未开过的奖励容器，把它的表排进预取队列（队尾）；</li>
 *   <li>检测器右键 / 调试命令：插到优先队列队首，最先处理。</li>
 * </ul>
 * 模拟走原版开箱流程，表里写明的物品和 GLM、脚本后加的物品一起统计；位置用箱子的真实坐标，
 * 所以按维度、群系判断的 GLM 也能算对。结果存进存档（{@link LootIndexData}），退出再进不用重算。
 */
public final class LootIndex {
    private static final int RESULT_FORMAT_VERSION = 3;   // v3：引入静态概率估算与 3.5σ 动静融合，旧缓存自动重建
    private static final int TARGET_RUNS = 1_000;            // 每张表的采样目标
    private static final long TICK_BUDGET_NANOS = 2_000_000; // 单 tick 的索引时间预算，检查点在每次模拟之间
    private static final long SLOW_STEP_NANOS = 50_000_000;  // 单步超过 50 ms（一整个 tick）就写日志
    private static final int CHUNK_SCANS_PER_TICK = 128;     // 每 tick 最多扫描的已加载区块数
    public static final int DEEP_STEP_RUNS = 10_000;         // 深度搜索每次追加的采样次数
    public static final int DEEP_MAX_RUNS = 100_000;         // 单张表深度搜索的采样上限

    @Nullable
    private static LootIndex active; // 单人存档切换和数据包重载都会丢弃当前实例

    /** 区块加载事件可能在区块完全加载前触发，事件里只记坐标，下一 tick 再读方块实体 */
    private static final Queue<ChunkRef> loadedChunks = new ConcurrentLinkedQueue<>();

    /** 缓存键：同一张表在不同维度分开算 */
    public record Key(ResourceKey<Level> dimension, ResourceLocation table) {}

    /**
     * 单个掉落项统计：chance > 0 表示最终采用概率（动静吻合时取精确静态值，否则取实测值）；
     * chance <= 0 表示只在静态结构里发现、采样未命中且未能可靠估出正概率的候选项。
     */
    public record Drop(ResourceLocation itemId, int hits, double chance, boolean potionGroup) {}

    /** 一张表的抽样结果。采样出错时保留静态候选项供排查。 */
    public record Entry(int runs, List<Drop> drops, boolean samplingFailed) {
        /** 只在静态结构里发现、采样未命中的候选数 */
        int unobserved() {
            return (int) drops.stream().filter(drop -> drop.chance() <= 0.0).count();
        }
    }

    /** 深度搜索进度：已采样次数 / 目标次数 */
    public record DeepProgress(int runs, int target) {}

    record Status(int ready, int failed, int restored, int urgent, int prefetch,
                  int busyTicks, long busyNanos, int skippedTicks, long peakTickNanos, String peakKey) {}

    private record ChunkRef(ResourceKey<Level> dimension, ChunkPos pos) {}

    private record Work(LootSimulator.Sampling sampling, LootStaticEstimator.TableEstimate staticEstimate) {}

    private final MinecraftServer server;
    private final ReloadableServerRegistries.Holder registries;
    private final Set<ResourceLocation> loadedTables;
    private final LootIndexData data;                                     // 存档里的持久化缓存
    private final LinkedHashSet<Key> urgent = new LinkedHashSet<>();      // 右键 / 命令请求，最新的在队首
    private final LinkedHashSet<Key> prefetch = new LinkedHashSet<>();    // 区块加载发现的，按发现顺序
    private final Map<Key, BlockPos> origins = new HashMap<>();           // 模拟时使用的箱子位置
    private final Map<Key, Work> working = new HashMap<>();               // 插队时保留原任务的样本
    private final Map<Key, Entry> results = new HashMap<>();              // 含失败结果；成功的同时写进 data
    private final Map<Key, Integer> deepTargets = new HashMap<>();        // 深度搜索中的表 → 目标采样次数
    private final Map<Key, Work> retained = new HashMap<>();              // 深度搜索完成后保留的样本，再次深度搜索时接着加（不存盘）
    private final int restored;   // 进入存档时从磁盘恢复、核对通过的条目数
    private int failed;
    private int busyTicks;        // 实际推进过模拟的 tick 数
    private long busyNanos;       // 这些 tick 的模拟总耗时
    private int skippedTicks;     // 有任务但服务器没空（hasTime 为 false）而跳过的 tick 数
    private long peakTickNanos;
    private String peakKey = "-"; // 峰值 tick 当时在算的表

    private LootIndex(MinecraftServer server) {
        this.server = server;
        registries = server.reloadableRegistries();
        loadedTables = Set.copyOf(registries.getKeys(Registries.LOOT_TABLE));
        data = server.overworld().getDataStorage().computeIfAbsent(LootIndexData.factory(), LootIndexData.NAME);
        restored = restore();
    }

    public static LootIndex get(MinecraftServer server) {
        if (active == null || active.server != server) {
            active = new LootIndex(server);
        }
        return active;
    }

    /** 读出容器上未揭晓的战利品表：Lootr 容器读常驻表，原版容器开过后表会被清空 */
    @Nullable
    public static ResourceKey<LootTable> chestTable(BlockEntity blockEntity) {
        if (!LootContainers.isSupported(blockEntity)) return null; // 不支持的容器不预取
        ResourceKey<LootTable> lootr = LootrCompat.lootTable(blockEntity);
        if (lootr != null) return lootr;
        return blockEntity instanceof RandomizableContainer container ? container.getLootTable() : null;
    }

    public boolean hasTable(ResourceLocation id) {
        return loadedTables.contains(id);
    }

    /** 菜单等待采样时只查结果，避免每次查询都重新插队。 */
    @Nullable
    Entry cached(ResourceKey<Level> dimension, ResourceLocation table) {
        return results.get(new Key(dimension, table));
    }

    /** 命中缓存立即返回；未完成则插到队首，下一 tick 接着处理。origin 是模拟用的箱子位置。 */
    @Nullable
    public Entry request(ServerLevel level, ResourceLocation table, BlockPos origin) {
        Key key = new Key(level.dimension(), table);
        Entry cached = results.get(key);
        if (cached == null && hasTable(table)) {
            prefetch.remove(key);
            urgent.remove(key);
            urgent.addFirst(key);
            origins.putIfAbsent(key, origin.immutable());
        }
        return cached;
    }

    /**
     * 有结果且没到上限就能深度搜索（客户端按钮用同一条规则）。
     * 不看「未命中候选」数：GLM、脚本在代码里加的物品静态解析看不到，候选数为 0 不代表已经搜全。
     */
    public static boolean canDeepen(int runs) {
        return runs > 0 && runs < DEEP_MAX_RUNS;
    }

    /**
     * 深度搜索：在已有结果上再采样 {@link #DEEP_STEP_RUNS} 次，插到队首。
     * 本次运行里深度搜索过的表接着上次的样本加；其他情况（含读档恢复的结果）从头采到目标次数。
     *
     * @return 是否开始了新的深度搜索
     */
    public boolean deepen(ServerLevel level, ResourceLocation table, BlockPos origin) {
        Key key = new Key(level.dimension(), table);
        Entry entry = results.get(key);
        if (entry == null || entry.samplingFailed() || deepTargets.containsKey(key)
                || !canDeepen(entry.runs())) return false;
        deepTargets.put(key, Math.min(entry.runs() + DEEP_STEP_RUNS, DEEP_MAX_RUNS));
        Work kept = retained.remove(key);
        if (kept != null) working.put(key, kept);
        prefetch.remove(key);
        urgent.remove(key);
        urgent.addFirst(key);
        origins.putIfAbsent(key, origin.immutable());
        return true;
    }

    /** 深度搜索进度；这张表没在深度搜索时为空 */
    @Nullable
    DeepProgress deepProgress(ResourceKey<Level> dimension, ResourceLocation table) {
        Key key = new Key(dimension, table);
        Integer target = deepTargets.get(key);
        if (target == null) return null;
        Work work = working.get(key); // 还没轮到时没有任务，进度按 0 算
        return new DeepProgress(work == null ? 0 : work.sampling().runs(), target);
    }

    /** 区块里发现的容器：没缓存、没排队才放到预取队尾 */
    private void prefetch(ServerLevel level, ResourceLocation table, BlockPos origin) {
        Key key = new Key(level.dimension(), table);
        if (!hasTable(table) || results.containsKey(key) || urgent.contains(key) || !prefetch.add(key)) return;
        origins.putIfAbsent(key, origin.immutable());
    }

    /** 清空内存和存档里的缓存，再重新扫描玩家周围的区块（/reload 和 clear 命令用） */
    void clear() {
        data.clear();
        results.clear();
        urgent.clear();
        prefetch.clear();
        origins.clear();
        working.clear();
        deepTargets.clear();
        retained.clear();
        failed = 0;
        rescanAroundPlayers();
    }

    Status status() {
        return new Status(results.size() - failed, failed, restored, urgent.size(), prefetch.size(),
                busyTicks, busyNanos, skippedTicks, peakTickNanos, peakKey);
    }

    // ---------------------------------------------------------------- 事件

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            loadedChunks.add(new ChunkRef(level.dimension(), event.getChunk().getPos()));
        }
    }

    public static void onDatapackSync(OnDatapackSyncEvent event) {
        // 有具体玩家时是加入服务器，全体同步才对应服务端数据包重载。
        // 数据包或脚本可能改了表，旧缓存（含存档里的）全部作废；新实例会拿到重载后的表。
        if (event.getPlayer() != null) return;
        MinecraftServer server = event.getPlayerList().getServer();
        get(server).data.clear(); // 存档数据对象由 DataStorage 持有，新实例拿到的是同一个
        active = null;
        get(server).rescanAroundPlayers();
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        if (active != null && active.server == event.getServer()) active = null;
        loadedChunks.clear();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        try {
            tick(event);
        } catch (RuntimeException error) {
            // 服务端 tick 里的异常会直接让服务器崩溃，这里兜底；同样的问题每 tick 都会出现，只记第一次的完整堆栈
            if (!tickErrorLogged) {
                tickErrorLogged = true;
                LootWorks.LOGGER.error("战利品索引 tick 出错，之后同类错误不再重复记录", error);
            }
        }
    }

    private static boolean tickErrorLogged; // 是否已记过 tick 出错的日志

    private static void tick(ServerTickEvent.Post event) {
        LootIndex index = get(event.getServer());
        index.scanLoadedChunks();
        if (index.urgent.isEmpty() && index.prefetch.isEmpty()) return;
        if (!event.hasTime()) {
            index.skippedTicks++;
            return;
        }

        long start = System.nanoTime();
        Key last = null; // 本 tick 最后处理的表，用于峰值记录
        while (System.nanoTime() - start < TICK_BUDGET_NANOS) {
            Key key = index.next();
            if (key == null) break;
            last = key;
            long stepStart = System.nanoTime();
            boolean done = index.advance(key, start);
            long step = System.nanoTime() - stepStart;
            if (step > SLOW_STEP_NANOS) {
                LootWorks.LOGGER.warn("战利品索引单步耗时 {} ms：{} @ {}", String.format("%.1f", step / 1e6),
                        key.table(), key.dimension().location());
            }
            if (!done) break;
        }
        long elapsed = System.nanoTime() - start;
        index.busyTicks++;
        index.busyNanos += elapsed;
        if (elapsed > index.peakTickNanos) {
            index.peakTickNanos = elapsed;
            index.peakKey = last == null ? "-" : last.table() + " @ " + last.dimension().location();
        }
    }

    // ---------------------------------------------------------------- 内部

    @Nullable
    private Key next() {
        if (!urgent.isEmpty()) return urgent.getFirst();
        return prefetch.isEmpty() ? null : prefetch.getFirst();
    }

    private void scanLoadedChunks() {
        for (int i = 0; i < CHUNK_SCANS_PER_TICK; i++) {
            ChunkRef ref = loadedChunks.poll();
            if (ref == null) return;
            ServerLevel level = server.getLevel(ref.dimension());
            if (level == null) continue;
            // getChunkNow 只取已加载完的区块，不会触发加载；排队期间被卸载的区块直接跳过
            LevelChunk chunk = level.getChunkSource().getChunkNow(ref.pos().x, ref.pos().z);
            if (chunk == null) continue;
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                try {
                    ResourceKey<LootTable> table = chestTable(blockEntity);
                    if (table != null) prefetch(level, table.location(), blockEntity.getBlockPos());
                } catch (RuntimeException error) {
                    // 个别模组的方块实体读表出错：跳过这一个，不影响同区块其他箱子；每个箱子只在区块加载时扫一次，不会刷屏
                    LootWorks.LOGGER.warn("战利品索引预取跳过 {} @ {}", blockEntity.getType(),
                            blockEntity.getBlockPos().toShortString(), error);
                }
            }
        }
    }

    /** 已加载的区块不会再触发加载事件，清空后要主动把玩家视距内的区块重新排队 */
    private void rescanAroundPlayers() {
        int radius = server.getPlayerList().getViewDistance();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ChunkPos center = player.chunkPosition();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    loadedChunks.add(new ChunkRef(player.level().dimension(),
                            new ChunkPos(center.x + dx, center.z + dz)));
                }
            }
        }
    }

    /** 核对存档里的缓存：指纹不符整体作废，单表内容变了只丢这一张。返回保留下来的条数。 */
    private int restore() {
        String fingerprint = fingerprint();
        if (!fingerprint.equals(data.fingerprint)) {
            if (!data.entries.isEmpty()) LootWorks.LOGGER.info("战利品索引格式、模组或数据包有变化，缓存作废");
            data.entries.clear();
            data.fingerprint = fingerprint;
            data.setDirty();
            return 0;
        }
        Map<ResourceLocation, Integer> hashes = new HashMap<>(); // 同一张表在多个维度只算一次哈希
        boolean changed = data.entries.entrySet().removeIf(stored -> {
            ResourceLocation table = stored.getKey().table();
            return !hasTable(table) || hashes.computeIfAbsent(table, this::tableHash) != stored.getValue().tableHash();
        });
        if (changed) data.setDirty();
        data.entries.forEach((key, stored) -> results.put(key, stored.entry()));
        return data.entries.size();
    }

    /** 结果格式、模组列表（含版本）和已启用的数据包。/reload 仍需清缓存，因为脚本改动不会改变指纹。 */
    private String fingerprint() {
        String mods = ModList.get().getMods().stream()
                .map(mod -> mod.getModId() + "@" + mod.getVersion())
                .sorted().collect(Collectors.joining(";"));
        String packs = server.getPackRepository().getSelectedIds().stream()
                .sorted().collect(Collectors.joining(";"));
        String all = mods + "|" + packs;
        return RESULT_FORMAT_VERSION + "/" + Integer.toHexString(all.hashCode()) + "/" + all.length();
    }

    /** 表内容的哈希：序列化成 JSON 再取哈希；个别模组的表无法序列化时返回 0（视为不变） */
    private int tableHash(ResourceLocation id) {
        try {
            var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
            return LootTable.DIRECT_CODEC.encodeStart(ops, getTable(id)).getOrThrow().toString().hashCode();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private LootTable getTable(ResourceLocation id) {
        return registries.getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
    }

    private Work start(Key key) {
        LootTable table = getTable(key.table());
        ServerLevel level = server.getLevel(key.dimension());
        if (level == null) level = server.overworld(); // 维度被卸载时退回主世界
        BlockPos origin = origins.getOrDefault(key, level.getSharedSpawnPos());
        // 不带玩家：预取时不知道谁会来开，幸运等玩家相关条件按无玩家处理
        LootParams params = LootSimulator.buildChestParams(level, origin, null);
        return new Work(new LootSimulator.Sampling(table, params, true),
                LootStaticEstimator.estimate(server.registryAccess(), registries, table));
    }

    /** 返回当前表是否完成，供本 tick 在预算内继续处理下一张表。 */
    private boolean advance(Key key, long tickStart) {
        Work work = null;
        try {
            work = working.computeIfAbsent(key, this::start);
            int targetRuns = deepTargets.getOrDefault(key, TARGET_RUNS); // 深度搜索时采到更高的目标
            if (!work.sampling().advance(targetRuns, tickStart, TICK_BUDGET_NANOS)) return false;
            LootSimulator.Result result = work.sampling().result(key.table());
            List<Drop> drops = mergeStaticAndDynamic(result, work.staticEstimate());
            Entry entry = new Entry(result.runs(), List.copyOf(drops), false);
            results.put(key, entry);
            data.entries.put(key, new LootIndexData.Stored(entry, tableHash(key.table())));
            data.setDirty();
            if (deepTargets.remove(key) != null) {
                retained.put(key, work); // 留着样本，下次深度搜索接着加
                LootWorks.LOGGER.info("深度搜索完成：{} @ {}，共 {} 次，未命中候选 {} 种", key.table(),
                        key.dimension().location(), entry.runs(), entry.unobserved());
            }
        } catch (RuntimeException exception) {
            if (deepTargets.remove(key) != null && results.containsKey(key)) {
                // 深度搜索出错时保留原来的结果，不把已有的检测结果变成失败
                LootWorks.LOGGER.warn("深度搜索失败，保留原结果：{}", key.table(), exception);
                urgent.remove(key);
                working.remove(key);
                origins.remove(key);
                return true;
            }
            // 单个模组的条件或修改器可能拒绝无玩家环境，记录失败后继续建其他表；失败结果不存盘，下次进存档重试。
            LootWorks.LOGGER.warn("战利品索引无法采样 {}", key.table(), exception);
            List<Drop> candidates = work == null ? List.of()
                    : work.staticEstimate().items().keySet().stream()
                            .map(item -> new Drop(item, 0, 0.0, false)).toList();
            results.put(key, new Entry(0, candidates, true));
            failed++;
        }
        urgent.remove(key);
        prefetch.remove(key);
        working.remove(key);
        origins.remove(key);
        return true;
    }

    /** 融合动态采样与静态理论概率：3.5σ 一致性区间内取精确静态值，否则信动态实测 */
    static List<Drop> mergeStaticAndDynamic(LootSimulator.Result result, LootStaticEstimator.TableEstimate staticEst) {
        int runs = result.runs();
        Set<ResourceLocation> seenAny = new HashSet<>();
        Set<ResourceLocation> mixedPotionItems = new HashSet<>(); // 同表混出真药水与水瓶时不套静态合并
        for (LootSimulator.ItemStat stat : result.stats()) {
            if (!seenAny.add(stat.itemId)) mixedPotionItems.add(stat.itemId);
        }

        List<Drop> drops = new ArrayList<>();
        for (LootSimulator.ItemStat stat : result.stats()) {
            double dynamicChance = runs > 0 ? (double) stat.runsAppeared / runs : 0.0;
            LootStaticEstimator.Estimate est = staticEst.items().get(stat.itemId);
            boolean useStatic = est != null && est.reliable() && est.probability() > 0.0
                    && !mixedPotionItems.contains(stat.itemId)
                    && LootStaticEstimator.isConsistent(stat.runsAppeared, runs, est.probability());
            double finalChance = useStatic ? est.probability() : dynamicChance;
            drops.add(new Drop(stat.itemId, stat.runsAppeared, finalChance, stat.potionGroup));
        }

        for (var entry : staticEst.items().entrySet()) {
            ResourceLocation itemId = entry.getKey();
            if (seenAny.contains(itemId)) continue;
            LootStaticEstimator.Estimate est = entry.getValue();
            boolean showStatic = est.reliable() && est.probability() > 0.0
                    && LootStaticEstimator.isConsistent(0, runs, est.probability());
            drops.add(new Drop(itemId, 0, showStatic ? est.probability() : 0.0, false));
        }

        drops.sort((a, b) -> {
            int byChance = Double.compare(b.chance(), a.chance());
            if (byChance != 0) return byChance;
            int byHits = Integer.compare(b.hits(), a.hits());
            if (byHits != 0) return byHits;
            return a.itemId().compareTo(b.itemId());
        });
        return drops;
    }
}
