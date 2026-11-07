package com.ageofelement.statue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * **Boss 雕像登记簿**（2026-11-05；2026-11-05 晚改造成「增量计数」，见性能评估）。
 *
 * <h3>为什么不是「每次重算都遍历全部雕像」</h3>
 * <p>原版写法的 {@code byOwner} 是 O(全部雕像)，再配上「每 5 秒兜底」就会周期性扫全表。
 * 现在改成**两份表**：</p>
 * <ul>
 *   <li>{@code positions}：`维度|坐标 → (BossId, 主人)` —— 只在**拆雕像时反查主人**用（O(1)）；</li>
 *   <li>{@code counts}：`主人 → (BossId → 座数)` —— 放置/拆除时**直接加减**（O(1)），
 *       加成只需遍历「这个主人有几种雕像」（一般 ≤ 20），**与雕像总数无关**。</li>
 * </ul>
 *
 * <p>所以「每 5 秒兜底」已经删掉了：数据在每个事件上就是准的。
 * 真要出现漂移（比如外挂直接把方块刷掉），拆一次对应的雕像就会自己纠正。</p>
 */
public class StatueRegistry extends SavedData {

    private static final String NAME = "aoe_boss_statues";

    /** 同一种 Boss 的雕像最多计几座（加成口径，2026-11-06）。 */
    public static final int PER_TYPE_CAP = 2;

    /** 一条位置记录。 */
    public record Entry(ResourceKey<Level> dimension, BlockPos pos, String bossId, UUID owner) {
    }

    private final Map<String, Entry> positions = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> counts = new HashMap<>();

    public static StatueRegistry get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        // ⚠️ 1.20.1 的 DimensionDataStorage 只有 (loader, creator, name) 三参版本
        return overworld.getDataStorage().computeIfAbsent(StatueRegistry::load, StatueRegistry::new, NAME);
    }

    private static String key(ResourceKey<Level> dim, BlockPos pos) {
        return dim.location() + "|" + pos.asLong();
    }

    // ------------------------------------------------------------------ 读写

    public static StatueRegistry load(CompoundTag tag) {
        StatueRegistry data = new StatueRegistry();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            try {
                ResourceLocation dim = ResourceLocation.tryParse(c.getString("Dim"));
                ResourceKey<Level> key = dim == null ? Level.OVERWORLD
                        : ResourceKey.create(Registries.DIMENSION, dim);
                Entry e = new Entry(key, BlockPos.of(c.getLong("Pos")),
                        c.getString("Boss"), UUID.fromString(c.getString("Owner")));
                data.positions.put(key(e.dimension(), e.pos()), e);
                data.countUp(e.owner(), e.bossId(), 1);
            } catch (Throwable ignored) {
                // 坏条目跳过，别让整个存档读不出来
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry e : positions.values()) {
            CompoundTag c = new CompoundTag();
            c.putString("Dim", e.dimension().location().toString());
            c.putLong("Pos", e.pos().asLong());
            c.putString("Boss", e.bossId());
            c.putString("Owner", e.owner().toString());
            list.add(c);
        }
        tag.put("Entries", list);
        return tag;
    }

    // ------------------------------------------------------------------ 写入

    /** 放置：登记坐标 + 计数 +1。 */
    public void put(ResourceKey<Level> dim, BlockPos pos, String bossId, UUID owner) {
        String k = key(dim, pos);
        Entry old = positions.get(k);
        if (old != null) {
            countUp(old.owner(), old.bossId(), -1);      // 换绑/重叠：先扣旧的
        }
        Entry e = new Entry(dim, pos, bossId == null ? "" : bossId, owner);
        positions.put(k, e);
        countUp(owner, e.bossId(), 1);
        setDirty();
    }

    /** 拆除：按坐标反查主人并计数 -1（返回被删的条目，可能为 null）。 */
    public Entry removeAt(ResourceKey<Level> dim, BlockPos pos) {
        Entry e = positions.remove(key(dim, pos));
        if (e != null) {
            countUp(e.owner(), e.bossId(), -1);
            setDirty();
        }
        return e;
    }

    /** 换绑：坐标不变，只换 BossId（先扣旧 id、再加新 id）。 */
    public void rebind(ResourceKey<Level> dim, BlockPos pos, String newBossId) {
        Entry old = positions.get(key(dim, pos));
        if (old == null) {
            return;
        }
        countUp(old.owner(), old.bossId(), -1);
        Entry e = new Entry(old.dimension(), old.pos(), newBossId == null ? "" : newBossId, old.owner());
        positions.put(key(dim, pos), e);
        countUp(e.owner(), e.bossId(), 1);
        setDirty();
    }

    private void countUp(UUID owner, String bossId, int delta) {
        if (owner == null || bossId == null || bossId.isEmpty()) {
            return;                                       // 空雕像不计数
        }
        Map<String, Integer> m = counts.computeIfAbsent(owner, k -> new HashMap<>());
        int now = m.getOrDefault(bossId, 0) + delta;
        if (now <= 0) {
            m.remove(bossId);
            if (m.isEmpty()) {
                counts.remove(owner);
            }
        } else {
            m.put(bossId, now);
        }
    }

    // ------------------------------------------------------------------ 查询

    /** **有效座数**：每种 Boss 最多计 2 座（O(种类数)，与雕像总数无关）。 */
    public int counted(UUID owner) {
        Map<String, Integer> m = counts.get(owner);
        if (m == null) {
            return 0;
        }
        int n = 0;
        for (int c : m.values()) {
            n += Math.min(PER_TYPE_CAP, c);
        }
        return n;
    }

    /** 这个主人每种雕像各几座（给命令/调试用）。 */
    public Map<String, Integer> countsOf(UUID owner) {
        Map<String, Integer> m = counts.get(owner);
        return m == null ? Map.of() : new HashMap<>(m);
    }
}
