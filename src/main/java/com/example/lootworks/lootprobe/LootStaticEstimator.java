package com.example.lootworks.lootprobe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootDataManager;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * 战利品表静态理论概率估算器。
 * <p>
 * 仅对结构纯正、无位置/玩家等未知条件的路径计算单次完整开箱（未受 27 格上限截断）理论出现概率；
 * 含 GLM、复杂条件或不可序列化结构的表自动标记为 {@code reliable = false}，
 * 交给 {@link LootIndex} 通过 3.5σ 检验与 1000 次动态模拟结果安全融合。
 */
final class LootStaticEstimator {
    private LootStaticEstimator() {}

    private static final int MAX_DISCRETE_ROLLS = 64; // 离散 rolls 展开的最大跨度/试验数，超出转动态兜底
    private static final double CONSISTENCY_SIGMA = 3.5; // 动静一致性校验标准差倍数（约 99.95% 覆盖）
    private static final ResourceLocation BOOK_ID = ResourceLocation.withDefaultNamespace("book");
    private static final ResourceLocation ENCHANTED_BOOK_ID = ResourceLocation.withDefaultNamespace("enchanted_book");

    /** 单个物品的静态估算结果 */
    record Estimate(double probability, boolean reliable) {}

    /** 整张表的静态估算结果 */
    record TableEstimate(Map<ResourceLocation, Estimate> items, boolean serializedOk) {}

    /**
     * 估算已加载战利品表的静态理论概率；若表无法序列化为 JSON，退回仅收集候选项。
     */
    static TableEstimate estimate(LootDataManager registries, LootTable root) {
        var parser = LootDataType.TABLE.parser();
        JsonElement rootJson;
        try {
            rootJson = parser.toJsonTree(root);
        } catch (RuntimeException exception) {
            return fallbackCandidates(LootTableCandidates.collect(registries, root), false);
        }

        Function<ResourceLocation, JsonElement> nestedJsonResolver = id -> {
            try {
                return parser.toJsonTree(registries.getLootTable(id));
            } catch (RuntimeException exception) {
                return null;
            }
        };
        Function<ResourceLocation, Set<ResourceLocation>> nestedFallbackResolver =
                id -> LootTableCandidates.collect(registries, registries.getLootTable(id));
        Function<ResourceLocation, List<ResourceLocation>> tagItemsResolver = tagId -> {
            TagKey<Item> key = TagKey.create(Registries.ITEM, tagId);
            java.util.ArrayList<ResourceLocation> ids = new java.util.ArrayList<>();
            BuiltInRegistries.ITEM.getTagOrEmpty(key).forEach(holder -> ids.add(BuiltInRegistries.ITEM.getKey(holder.value())));
            return ids;
        };

        return estimateJson(rootJson, nestedJsonResolver, nestedFallbackResolver, tagItemsResolver);
    }

    /**
     * 纯 JSON 入口（便于不启动服务端直接做离线算法校验）。
     */
    static TableEstimate estimateJson(JsonElement rootJson,
                                      Function<ResourceLocation, JsonElement> nestedJsonResolver,
                                      Function<ResourceLocation, Set<ResourceLocation>> nestedFallbackResolver,
                                      Function<ResourceLocation, List<ResourceLocation>> tagItemsResolver) {
        Context ctx = new Context(nestedJsonResolver, nestedFallbackResolver, tagItemsResolver);
        Map<ResourceLocation, Estimate> result = ctx.evalTable(rootJson, null);
        return new TableEstimate(result, true);
    }

    /**
     * 判断动态采样命中次数是否在静态理论概率的 3.5σ 合理波动范围内（+1 次连续容错）。
     * 用于自动区分“纯粹小数波动”与“存在 GLM 魔改 / 环境条件 / 27 格满箱截断”。
     */
    static boolean isConsistent(int hits, int runs, double staticProb) {
        if (runs <= 0 || staticProb <= 0.0 || staticProb > 1.0) return false;
        double expected = runs * staticProb;
        double sigma = Math.sqrt(runs * staticProb * (1.0 - staticProb));
        return Math.abs(hits - expected) <= CONSISTENCY_SIGMA * sigma + 1.0;
    }

    private static TableEstimate fallbackCandidates(Set<ResourceLocation> candidates, boolean serializedOk) {
        Map<ResourceLocation, Estimate> map = new TreeMap<>();
        for (ResourceLocation id : candidates) {
            map.put(id, new Estimate(0.0, false));
        }
        return new TableEstimate(map, serializedOk);
    }

    // ---------------------------------------------------------------- 内部递归求值器

    private static final class Context {
        private final Function<ResourceLocation, JsonElement> nestedJsonResolver;
        private final Function<ResourceLocation, Set<ResourceLocation>> nestedFallbackResolver;
        private final Function<ResourceLocation, List<ResourceLocation>> tagItemsResolver;
        private final Map<ResourceLocation, Map<ResourceLocation, Estimate>> tableCache = new HashMap<>();
        private final Set<ResourceLocation> visiting = new HashSet<>(); // 防止引用子表形成环

        Context(Function<ResourceLocation, JsonElement> nestedJsonResolver,
                Function<ResourceLocation, Set<ResourceLocation>> nestedFallbackResolver,
                Function<ResourceLocation, List<ResourceLocation>> tagItemsResolver) {
            this.nestedJsonResolver = nestedJsonResolver;
            this.nestedFallbackResolver = nestedFallbackResolver;
            this.tagItemsResolver = tagItemsResolver;
        }

        Map<ResourceLocation, Estimate> evalTable(JsonElement element, @Nullable ResourceLocation tableId) {
            if (tableId != null && tableCache.containsKey(tableId)) {
                return tableCache.get(tableId);
            }
            if (tableId != null && !visiting.add(tableId)) {
                return Map.of();
            }
            try {
                if (!element.isJsonObject()) return Map.of();
                JsonObject obj = element.getAsJsonObject();
                EnchantState tableEnchants = inspectEnchants(getArray(obj, "functions"));
                Map<ResourceLocation, Acc> total = new TreeMap<>();

                JsonArray pools = getArray(obj, "pools");
                if (pools != null) {
                    for (JsonElement poolEl : pools) {
                        if (!poolEl.isJsonObject()) continue;
                        Map<ResourceLocation, Estimate> poolRes = evalPool(poolEl.getAsJsonObject(), tableEnchants);
                        for (var entry : poolRes.entrySet()) {
                            Acc acc = total.computeIfAbsent(entry.getKey(), k -> new Acc());
                            Estimate pe = entry.getValue();
                            acc.prob = 1.0 - (1.0 - acc.prob) * (1.0 - pe.probability());
                            acc.reliable = acc.reliable && pe.reliable();
                        }
                    }
                }

                Map<ResourceLocation, Estimate> frozen = new TreeMap<>();
                for (var entry : total.entrySet()) {
                    Acc a = entry.getValue();
                    frozen.put(entry.getKey(), new Estimate(a.reliable ? clampProb(a.prob) : 0.0, a.reliable));
                }
                if (tableId != null) tableCache.put(tableId, frozen);
                return frozen;
            } finally {
                if (tableId != null) visiting.remove(tableId);
            }
        }

        private Map<ResourceLocation, Estimate> evalPool(JsonObject poolObj, EnchantState tableEnchants) {
            CondResult poolCond = evalConditions(getArray(poolObj, "conditions"));
            Map<Integer, Double> rollsDist = parseRollsDist(poolObj.get("rolls"));
            EnchantState poolEnchants = tableEnchants.combine(inspectEnchants(getArray(poolObj, "functions")));
            JsonArray entries = getArray(poolObj, "entries");
            if (entries == null || entries.isEmpty()) return Map.of();

            boolean poolPrecise = poolCond.reliable() && rollsDist != null;
            List<FlatEntry> flatEntries = new java.util.ArrayList<>();
            if (poolPrecise) {
                for (JsonElement el : entries) {
                    if (!flattenPreciseEntry(el, poolEnchants, flatEntries)) {
                        poolPrecise = false; // 遇到条目条件 / alternatives / sequence 等动态结构
                        break;
                    }
                }
            }

            if (!poolPrecise) {
                // 该池包含不可静态评估的条件或复杂结构：仅收集候选项并标为不可靠
                Set<ResourceLocation> items = new LinkedHashSet<>();
                for (JsonElement el : entries) {
                    collectCandidatesFromEntry(el, poolEnchants, items);
                }
                Map<ResourceLocation, Estimate> fallback = new TreeMap<>();
                for (ResourceLocation id : items) {
                    fallback.put(id, new Estimate(0.0, false));
                }
                return fallback;
            }

            return computeFlatPool(flatEntries, poolCond.prob(), rollsDist);
        }

        private Map<ResourceLocation, Estimate> computeFlatPool(List<FlatEntry> flatEntries, double poolCondProb,
                                                                Map<Integer, Double> rollsDist) {
            long totalWeight = 0; // 单轮有效总权重
            for (FlatEntry entry : flatEntries) {
                totalWeight += entry.effectiveWeight(tagItemsResolver);
            }
            if (totalWeight <= 0) return Map.of();

            Map<ResourceLocation, Acc> perRoll = new TreeMap<>();
            for (FlatEntry entry : flatEntries) {
                entry.accumulateSingleRoll(totalWeight, perRoll, this);
            }

            Map<ResourceLocation, Estimate> result = new TreeMap<>();
            for (var itemEntry : perRoll.entrySet()) {
                Acc acc = itemEntry.getValue();
                if (!acc.reliable) {
                    result.put(itemEntry.getKey(), new Estimate(0.0, false));
                    continue;
                }
                double singleRollProb = clampProb(acc.prob);
                double atLeastOneInRolls = 0.0;
                for (var roll : rollsDist.entrySet()) {
                    int r = roll.getKey();
                    if (r > 0) {
                        atLeastOneInRolls += roll.getValue() * (1.0 - Math.pow(1.0 - singleRollProb, r));
                    }
                }
                result.put(itemEntry.getKey(), new Estimate(clampProb(poolCondProb * atLeastOneInRolls), true));
            }
            return result;
        }

        /** 将纯静态可评估的条目树展平；只要包含条目级条件或不支持的组合节点即返回 false */
        private boolean flattenPreciseEntry(JsonElement el, EnchantState inheritedEnchants, List<FlatEntry> out) {
            if (!el.isJsonObject()) return false;
            JsonObject obj = el.getAsJsonObject();
            JsonArray conds = getArray(obj, "conditions");
            if (conds != null && !conds.isEmpty()) return false;

            String type = normalizeType(getString(obj, "type", ""));
            switch (type) {
                case "minecraft:group" -> {
                    JsonArray children = getArray(obj, "children");
                    if (children == null) return true;
                    for (JsonElement child : children) {
                        if (!flattenPreciseEntry(child, inheritedEnchants, out)) return false;
                    }
                    return true;
                }
                case "minecraft:item" -> {
                    int weight = getInt(obj, "weight", 1);
                    if (weight <= 0) return true;
                    ResourceLocation id = ResourceLocation.tryParse(getString(obj, "name", ""));
                    if (id == null) return false;
                    EnchantState ench = inheritedEnchants.combine(inspectEnchants(getArray(obj, "functions")));
                    out.add(new FlatItem(id, weight, ench));
                    return true;
                }
                case "minecraft:empty" -> {
                    int weight = getInt(obj, "weight", 1);
                    if (weight > 0) out.add(new FlatEmpty(weight));
                    return true;
                }
                case "minecraft:tag" -> {
                    int weight = getInt(obj, "weight", 1);
                    if (weight <= 0) return true;
                    ResourceLocation tagId = ResourceLocation.tryParse(getString(obj, "name", ""));
                    if (tagId == null) return false;
                    boolean expand = obj.has("expand") && obj.get("expand").isJsonPrimitive()
                            && obj.get("expand").getAsBoolean();
                    EnchantState ench = inheritedEnchants.combine(inspectEnchants(getArray(obj, "functions")));
                    out.add(new FlatTag(tagId, expand, weight, ench));
                    return true;
                }
                case "minecraft:loot_table" -> {
                    int weight = getInt(obj, "weight", 1);
                    if (weight <= 0) return true;
                    JsonElement value = obj.get("name");
                    if (value == null) return false;
                    out.add(new FlatNested(value, weight));
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private void collectCandidatesFromEntry(JsonElement el, EnchantState inheritedEnchants, Set<ResourceLocation> out) {
            if (!el.isJsonObject()) return;
            JsonObject obj = el.getAsJsonObject();
            String type = normalizeType(getString(obj, "type", ""));
            switch (type) {
                case "minecraft:item" -> {
                    ResourceLocation id = ResourceLocation.tryParse(getString(obj, "name", ""));
                    if (id == null) return;
                    EnchantState ench = inheritedEnchants.combine(inspectEnchants(getArray(obj, "functions")));
                    addBookAwareCandidates(id, ench, out);
                }
                case "minecraft:tag" -> {
                    ResourceLocation tagId = ResourceLocation.tryParse(getString(obj, "name", ""));
                    if (tagId == null) return;
                    EnchantState ench = inheritedEnchants.combine(inspectEnchants(getArray(obj, "functions")));
                    for (ResourceLocation id : tagItemsResolver.apply(tagId)) {
                        addBookAwareCandidates(id, ench, out);
                    }
                }
                case "minecraft:loot_table" -> {
                    JsonElement value = obj.get("name");
                    if (value == null) return;
                    out.addAll(evalNested(value).keySet());
                }
                case "minecraft:group", "minecraft:alternatives", "minecraft:sequence" -> {
                    JsonArray children = getArray(obj, "children");
                    if (children == null) return;
                    for (JsonElement child : children) {
                        collectCandidatesFromEntry(child, inheritedEnchants, out);
                    }
                }
                default -> {}
            }
        }

        Map<ResourceLocation, Estimate> evalNested(JsonElement value) {
            if (value.isJsonObject()) {
                return evalTable(value, null);
            }
            if (value.isJsonPrimitive()) {
                ResourceLocation id = ResourceLocation.tryParse(value.getAsString());
                if (id == null) return Map.of();
                if (tableCache.containsKey(id)) return tableCache.get(id);
                if (visiting.contains(id)) return Map.of();
                JsonElement subJson = nestedJsonResolver.apply(id);
                if (subJson != null) {
                    return evalTable(subJson, id);
                }
                Set<ResourceLocation> fallback = nestedFallbackResolver.apply(id);
                Map<ResourceLocation, Estimate> map = new TreeMap<>();
                for (ResourceLocation item : fallback) {
                    map.put(item, new Estimate(0.0, false));
                }
                return map;
            }
            return Map.of();
        }
    }

    // ---------------------------------------------------------------- 条目抽象与工具

    private static final class Acc {
        double prob = 0.0;
        boolean reliable = true;
    }

    private record CondResult(double prob, boolean reliable) {}

    private record EnchantState(boolean unconditionalEnchant, boolean conditionalEnchant) {
        static final EnchantState NONE = new EnchantState(false, false);

        EnchantState combine(EnchantState other) {
            return new EnchantState(unconditionalEnchant || other.unconditionalEnchant,
                    conditionalEnchant || other.conditionalEnchant);
        }
    }

    private sealed interface FlatEntry permits FlatItem, FlatEmpty, FlatTag, FlatNested {
        long effectiveWeight(Function<ResourceLocation, List<ResourceLocation>> tagItemsResolver);

        void accumulateSingleRoll(long totalWeight, Map<ResourceLocation, Acc> out, Context ctx);
    }

    private record FlatItem(ResourceLocation id, int weight, EnchantState enchants) implements FlatEntry {
        @Override
        public long effectiveWeight(Function<ResourceLocation, List<ResourceLocation>> tagItemsResolver) {
            return weight;
        }

        @Override
        public void accumulateSingleRoll(long totalWeight, Map<ResourceLocation, Acc> out, Context ctx) {
            double share = (double) weight / totalWeight;
            if (BOOK_ID.equals(id) && enchants.conditionalEnchant()) {
                markUnreliable(BOOK_ID, out);
                markUnreliable(ENCHANTED_BOOK_ID, out);
                return;
            }
            ResourceLocation resolved = (BOOK_ID.equals(id) && enchants.unconditionalEnchant()) ? ENCHANTED_BOOK_ID : id;
            Acc acc = out.computeIfAbsent(resolved, k -> new Acc());
            acc.prob += share;
        }
    }

    private record FlatEmpty(int weight) implements FlatEntry {
        @Override
        public long effectiveWeight(Function<ResourceLocation, List<ResourceLocation>> tagItemsResolver) {
            return weight;
        }

        @Override
        public void accumulateSingleRoll(long totalWeight, Map<ResourceLocation, Acc> out, Context ctx) {}
    }

    private record FlatTag(ResourceLocation tagId, boolean expand, int weight, EnchantState enchants) implements FlatEntry {
        @Override
        public long effectiveWeight(Function<ResourceLocation, List<ResourceLocation>> tagItemsResolver) {
            if (!expand) return weight;
            return (long) weight * tagItemsResolver.apply(tagId).size();
        }

        @Override
        public void accumulateSingleRoll(long totalWeight, Map<ResourceLocation, Acc> out, Context ctx) {
            List<ResourceLocation> members = ctx.tagItemsResolver.apply(tagId);
            if (members.isEmpty()) return;
            double share = (double) weight / totalWeight;
            Iterable<ResourceLocation> targets = expand ? members : new LinkedHashSet<>(members);
            for (ResourceLocation id : targets) {
                if (BOOK_ID.equals(id) && enchants.conditionalEnchant()) {
                    markUnreliable(BOOK_ID, out);
                    markUnreliable(ENCHANTED_BOOK_ID, out);
                    continue;
                }
                ResourceLocation resolved = (BOOK_ID.equals(id) && enchants.unconditionalEnchant()) ? ENCHANTED_BOOK_ID : id;
                Acc acc = out.computeIfAbsent(resolved, k -> new Acc());
                acc.prob += share;
            }
        }
    }

    private record FlatNested(JsonElement value, int weight) implements FlatEntry {
        @Override
        public long effectiveWeight(Function<ResourceLocation, List<ResourceLocation>> tagItemsResolver) {
            return weight;
        }

        @Override
        public void accumulateSingleRoll(long totalWeight, Map<ResourceLocation, Acc> out, Context ctx) {
            double entryShare = (double) weight / totalWeight;
            Map<ResourceLocation, Estimate> sub = ctx.evalNested(value);
            for (var subEntry : sub.entrySet()) {
                Acc acc = out.computeIfAbsent(subEntry.getKey(), k -> new Acc());
                Estimate est = subEntry.getValue();
                if (!est.reliable()) {
                    acc.reliable = false;
                } else {
                    acc.prob += entryShare * est.probability();
                }
            }
        }
    }

    private static void markUnreliable(ResourceLocation id, Map<ResourceLocation, Acc> out) {
        out.computeIfAbsent(id, k -> new Acc()).reliable = false;
    }

    private static void addBookAwareCandidates(ResourceLocation id, EnchantState enchants, Set<ResourceLocation> out) {
        if (!BOOK_ID.equals(id)) {
            out.add(id);
            return;
        }
        if (enchants.conditionalEnchant()) {
            out.add(BOOK_ID);
            out.add(ENCHANTED_BOOK_ID);
        } else {
            out.add(enchants.unconditionalEnchant() ? ENCHANTED_BOOK_ID : BOOK_ID);
        }
    }

    private static EnchantState inspectEnchants(@Nullable JsonArray functions) {
        if (functions == null) return EnchantState.NONE;
        boolean unconditional = false;
        boolean conditional = false;
        for (JsonElement el : functions) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String type = normalizeType(getString(obj, "function", ""));
            if ("minecraft:enchant_randomly".equals(type) || "minecraft:enchant_with_levels".equals(type)) {
                JsonArray conds = getArray(obj, "conditions");
                if (conds == null || conds.isEmpty()) {
                    unconditional = true;
                } else {
                    conditional = true;
                }
            }
        }
        return new EnchantState(unconditional, conditional);
    }

    // ---------------------------------------------------------------- 条件与次数分布求值

    private static CondResult evalConditions(@Nullable JsonArray conds) {
        if (conds == null || conds.isEmpty()) return new CondResult(1.0, true);
        double prob = 1.0;
        boolean reliable = true;
        for (JsonElement el : conds) {
            CondResult sub = evalCondition(el);
            prob *= sub.prob();
            reliable = reliable && sub.reliable();
        }
        return new CondResult(clampProb(prob), reliable);
    }

    private static CondResult evalCondition(JsonElement el) {
        if (!el.isJsonObject()) return new CondResult(1.0, false);
        JsonObject obj = el.getAsJsonObject();
        String type = normalizeType(getString(obj, "condition", ""));
        return switch (type) {
            case "minecraft:random_chance" -> {
                Double mean = evalConstMean(obj.get("chance"));
                yield mean == null ? new CondResult(1.0, false) : new CondResult(clampProb(mean), true);
            }
            case "minecraft:all_of" -> evalConditions(getArray(obj, "terms"));
            case "minecraft:any_of" -> {
                JsonArray terms = getArray(obj, "terms");
                if (terms == null || terms.isEmpty()) yield new CondResult(0.0, true);
                double miss = 1.0;
                boolean reliable = true;
                for (JsonElement t : terms) {
                    CondResult sub = evalCondition(t);
                    miss *= (1.0 - sub.prob());
                    reliable = reliable && sub.reliable();
                }
                yield new CondResult(clampProb(1.0 - miss), reliable);
            }
            case "minecraft:inverted" -> {
                JsonElement term = obj.get("term");
                if (term == null) yield new CondResult(1.0, false);
                CondResult sub = evalCondition(term);
                yield new CondResult(clampProb(1.0 - sub.prob()), sub.reliable());
            }
            default -> new CondResult(1.0, false);
        };
    }

    @Nullable
    private static Map<Integer, Double> parseRollsDist(@Nullable JsonElement el) {
        if (el == null) return null;
        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            if (!prim.isNumber()) return null;
            int rolls = Math.max(0, Math.round(prim.getAsFloat()));
            return Map.of(rolls, 1.0);
        }
        if (!el.isJsonObject()) return null;
        JsonObject obj = el.getAsJsonObject();
        // NumberProviders.CODEC 在无 type 时回退 UniformGenerator
        String type = normalizeType(getString(obj, "type", "minecraft:uniform"));
        return switch (type) {
            case "minecraft:constant" -> {
                Double val = evalConstNumber(obj.get("name"));
                if (val == null) yield null;
                yield Map.of(Math.max(0, Math.round(val.floatValue())), 1.0);
            }
            case "minecraft:uniform" -> {
                Double minD = evalConstNumber(obj.get("min"));
                Double maxD = evalConstNumber(obj.get("max"));
                if (minD == null || maxD == null) yield null;
                int lo = Math.round(minD.floatValue());
                int hi = Math.round(maxD.floatValue());
                if (lo >= hi) yield Map.of(Math.max(0, lo), 1.0);
                if (hi - lo > MAX_DISCRETE_ROLLS) yield null;
                Map<Integer, Double> dist = new HashMap<>();
                double p = 1.0 / (hi - lo + 1);
                for (int r = lo; r <= hi; r++) {
                    dist.merge(Math.max(0, r), p, Double::sum);
                }
                yield dist;
            }
            case "minecraft:binomial" -> {
                Double nD = evalConstNumber(obj.get("n"));
                Double pD = evalConstNumber(obj.get("p"));
                if (nD == null || pD == null) yield null;
                int n = Math.round(nD.floatValue());
                if (n < 0 || n > MAX_DISCRETE_ROLLS) yield null;
                double p = clampProb(pD);
                yield binomialDistribution(n, p);
            }
            default -> null;
        };
    }

    private static Map<Integer, Double> binomialDistribution(int n, double p) {
        Map<Integer, Double> dist = new HashMap<>();
        double[] row = new double[n + 1];
        row[0] = 1.0;
        for (int trial = 0; trial < n; trial++) {
            for (int k = trial + 1; k >= 1; k--) {
                row[k] = row[k] * (1.0 - p) + row[k - 1] * p;
            }
            row[0] *= (1.0 - p);
        }
        for (int k = 0; k <= n; k++) {
            if (row[k] > 0.0) dist.put(k, row[k]);
        }
        return dist;
    }

    @Nullable
    private static Double evalConstNumber(@Nullable JsonElement el) {
        if (el == null) return null;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsDouble();
        }
        if (!el.isJsonObject()) return null;
        JsonObject obj = el.getAsJsonObject();
        String type = normalizeType(getString(obj, "type", "minecraft:uniform"));
        if ("minecraft:constant".equals(type)) {
            return evalConstNumber(obj.get("name"));
        }
        return null;
    }

    @Nullable
    private static Double evalConstMean(@Nullable JsonElement el) {
        Double direct = evalConstNumber(el);
        if (direct != null) return direct;
        if (el == null || !el.isJsonObject()) return null;
        JsonObject obj = el.getAsJsonObject();
        String type = normalizeType(getString(obj, "type", "minecraft:uniform"));
        if ("minecraft:uniform".equals(type)) {
            Double lo = evalConstMean(obj.get("min"));
            Double hi = evalConstMean(obj.get("max"));
            if (lo != null && hi != null) return lo >= hi ? lo : 0.5 * (lo + hi);
        } else if ("minecraft:binomial".equals(type)) {
            Double n = evalConstMean(obj.get("n"));
            Double p = evalConstMean(obj.get("p"));
            if (n != null && p != null) return Math.max(0.0, Math.round(n.floatValue())) * clampProb(p);
        }
        return null;
    }

    private static String normalizeType(String raw) {
        if (raw.isEmpty()) return "";
        return raw.indexOf(':') >= 0 ? raw : "minecraft:" + raw;
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : fallback;
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsInt() : fallback;
    }

    @Nullable
    private static JsonArray getArray(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    private static double clampProb(double p) {
        return p <= 0.0 ? 0.0 : Math.min(1.0, p);
    }
}
