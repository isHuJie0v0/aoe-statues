package com.ageofelement.statue;

import com.ageofelement.statue.StatueMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * **Boss 名单**（2026-11-05 用户口径：「直接找 boss 标签似乎不全，但我没办法枚举，所以加个可以后期加的名单」）。
 *
 * <h3>三层来源</h3>
 * <ol>
 *   <li><b>标签自动收</b>：`#forge:bosses`、`#c:bosses`、`#neoforge:bosses`
 *       （实测本包并起来 44 条 —— 但确实会漏：很多模组不给自己的 Boss 打标签）；</li>
 *   <li><b>手工名单</b>：`config/age_of_element_statue/statue_bosses.json` 的 {@code extra}；
 *       用户随时可以往里加 {@code "modid:entity"}，**下个命令就生效**（不用重启）；</li>
 *   <li><b>排除名单</b>：同一个文件的 {@code exclude}，用来从标签里踢掉不想要的（比如某个只是血厚的精英）。</li>
 * </ol>
 *
 * <h3>为什么还要「候选」</h3>
 * <p>用户说「我没办法枚举」——所以再加一条 {@code /aoestatue candidates}：
 * 按**名字关键词**（boss/king/queen/lord/warden/dragon/wither/elder/titan…）+ **没被收录** 筛一遍，
 * 把可能漏掉的列出来，用户看到眼熟的直接把 id 贴进 {@code extra} 就行。</p>
 *
 * <h3>文件位置</h3>
 * <pre>{@code
 * config/age_of_element_statue/statue_bosses.json
 * {
 *   "extra":   [ "modid:some_boss" ],
 *   "exclude": [ "modid:not_really_a_boss" ]
 * }
 * }</pre>
 * <p>文件不存在时自动创建（空名单 + 注释键）。写盘只在 add/remove 时发生，读盘带 1 秒缓存。</p>
 */
public final class BossCatalog {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 2026-11-08 用户：配置从主模组目录搬到自己模组目录（旧文件会自动迁过来）。 */
    private static final Path FILE = StatueConfigDir.file("statue_bosses.json", true);

    /** 名字里带这些词的，会被 {@code candidates} 列成「疑似漏收」。 */
    private static final String[] CANDIDATE_WORDS = {
            "boss", "king", "queen", "lord", "warden", "dragon", "wither", "elder",
            "titan", "avatar", "demon", "devil", "hydra", "colossus", "ancient", "guardian",
            "reaper", "lich", "necromancer", "summoner", "herald", "overlord", "primordial",
    };

    private static Set<ResourceLocation> extra = new LinkedHashSet<>();
    private static Set<ResourceLocation> exclude = new LinkedHashSet<>();
    private static long loadedAt = -1L;

    private BossCatalog() {
    }

    // ------------------------------------------------------------------ 读写文件

    /** 读盘（1 秒缓存；文件不存在就建一个空模板）。 */
    public static synchronized void loadIfStale() {
        long now = System.currentTimeMillis();
        if (loadedAt > 0 && now - loadedAt < 1000L) {
            return;
        }
        loadedAt = now;
        try {
            if (!Files.exists(FILE)) {
                createTemplate();
            }
            String text = Files.readString(FILE, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            extra = parse(obj, "extra");
            exclude = parse(obj, "exclude");
        } catch (Throwable t) {
            StatueMod.LOGGER.warn("[雕像] 读 Boss 名单失败（按空名单继续）：{}", t.toString());
        }
    }

    private static Set<ResourceLocation> parse(JsonObject obj, String key) {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return out;
        }
        for (var e : obj.getAsJsonArray(key)) {
            ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    private static void createTemplate() throws IOException {
        Files.createDirectories(FILE.getParent());
        JsonObject obj = new JsonObject();
        obj.addProperty("_comment", "Boss 雕像名单：标签漏掉的 Boss 往 extra 里加 modid:entity；不想收的放 exclude。改完在游戏里执行 /aoestatue reload 即可。");
        obj.add("extra", new JsonArray());
        obj.add("exclude", new JsonArray());
        Files.writeString(FILE, GSON.toJson(obj), StandardCharsets.UTF_8);
        StatueMod.LOGGER.info("[雕像] 已创建名单文件 {}", FILE);
    }

    private static synchronized void save() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("_comment", "Boss 雕像名单：标签漏掉的 Boss 往 extra 里加 modid:entity；不想收的放 exclude。改完在游戏里执行 /aoestatue reload 即可。");
            JsonArray ex = new JsonArray();
            extra.forEach(id -> ex.add(id.toString()));
            JsonArray nx = new JsonArray();
            exclude.forEach(id -> nx.add(id.toString()));
            obj.add("extra", ex);
            obj.add("exclude", nx);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(obj), StandardCharsets.UTF_8);
            loadedAt = System.currentTimeMillis();
        } catch (Throwable t) {
            StatueMod.LOGGER.warn("[雕像] 写 Boss 名单失败：{}", t.toString());
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 最终名单 = 标签 + extra - exclude（都要求实体类型真实存在）。 */
    public static List<ResourceLocation> all() {
        loadIfStale();
        Set<ResourceLocation> out = new LinkedHashSet<>();
        for (var entry : ForgeRegistries.ENTITY_TYPES.getEntries()) {
            if (isBossTagged(entry.getValue())) {
                out.add(entry.getKey().location());
            }
        }
        int fromTag = out.size();
        for (ResourceLocation id : extra) {
            if (ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
                out.add(id);
            }
        }
        out.removeAll(exclude);
        StatueMod.LOGGER.debug("[雕像] 名单：标签 {} 条 + extra {} 条 - exclude {} 条 ⇒ {} 条",
                fromTag, extra.size(), exclude.size(), out.size());
        return new ArrayList<>(out);
    }

    public static int taggedCount() {
        int n = 0;
        for (var entry : ForgeRegistries.ENTITY_TYPES.getEntries()) {
            if (isBossTagged(entry.getValue())) {
                n++;
            }
        }
        return n;
    }

    public static Set<ResourceLocation> manualExtra() {
        loadIfStale();
        return new LinkedHashSet<>(extra);
    }

    public static Set<ResourceLocation> manualExclude() {
        loadIfStale();
        return new LinkedHashSet<>(exclude);
    }

    /** 加进 extra（已存在返回 false）。 */
    public static boolean add(String rawId) {
        loadIfStale();
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null || !ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
            return false;
        }
        exclude.remove(id);
        boolean changed = extra.add(id);
        if (changed) {
            save();
        }
        return changed;
    }

    /** 从 extra 移除；如果它在标签里，会改成写进 exclude（否则删掉也没用）。 */
    public static boolean remove(String rawId) {
        loadIfStale();
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null) {
            return false;
        }
        boolean changed = extra.remove(id);
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type != null && isBossTagged(type)) {
            changed |= exclude.add(id);
        }
        if (changed) {
            save();
        }
        return changed;
    }

    /** 强制重读文件。 */
    public static void reload() {
        loadedAt = -1L;
        loadIfStale();
    }

    /**
     * **疑似漏收的候选**：名字里带 boss/king/warden/dragon… 且还没在名单里的实体。
     *
     * <p>用户说「没办法枚举」，这条就是给他一个「照着名单挑」的入口 —— 看到眼熟的一行 `add` 即可。</p>
     */
    public static List<ResourceLocation> candidates() {
        Set<ResourceLocation> inList = new LinkedHashSet<>(all());
        List<ResourceLocation> out = new ArrayList<>();
        for (var entry : ForgeRegistries.ENTITY_TYPES.getEntries()) {
            ResourceLocation id = entry.getKey().location();
            if (inList.contains(id)) {
                continue;
            }
            String lower = id.getPath().toLowerCase();
            for (String w : CANDIDATE_WORDS) {
                if (lower.contains(w)) {
                    out.add(id);
                    break;
                }
            }
        }
        out.sort((a, b) -> a.toString().compareTo(b.toString()));
        return out;
    }

    /** 标签判定（`type.is(tag)` 对不存在的标签返回 false，不会抛）。 */
    public static boolean isBossTagged(EntityType<?> type) {
        if (type == null) {
            return false;
        }
        for (String ns : new String[]{"forge", "c", "neoforge"}) {
            try {
                TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE,
                        new ResourceLocation(ns, "bosses"));
                if (type.is(tag)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 标签加载异常不该影响枚举
            }
        }
        return false;
    }

    /** 文件路径（命令里打给用户看）。 */
    public static String filePath() {
        return FILE.toString();
    }
}
