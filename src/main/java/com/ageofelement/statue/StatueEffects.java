package com.ageofelement.statue;

import com.ageofelement.statue.StatueMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * **雕像自定义效果 API**（2026-11-06 用户：「给雕像加个自定义效果的 API，比如放下这个雕像就解锁某个游戏阶段，
 * 提供某些效果等，具体是什么不重要，主要是可以自定义效果」）。
 *
 * <h3>两层入口</h3>
 * <ol>
 *   <li><b>数据驱动</b>（不用写代码）：`config/age_of_element_statue/statue_effects.json`，
 *       按「触发时机 → 生物 id（`*` = 所有雕像）→ 效果列表」写，改完 `/aoestatue reloadfx` 即生效；</li>
 *   <li><b>Java API</b>（给扩展用）：{@link #register(String, StatueEffectHandler)}
 *       —— 生物 id 或 `*`，实现一个 lambda 即可。</li>
 * </ol>
 *
 * <h3>三种触发时机</h3>
 * <table border="1">
 *   <tr><td>{@code on_place}</td><td>雕像被**放下**（触发一次，owner = 放置者）</td></tr>
 *   <tr><td>{@code on_remove}</td><td>雕像被**拆掉**（触发一次，owner = 原主人）</td></tr>
 *   <tr><td>{@code on_interact}</td><td>玩家**空手右键**雕像（每次都触发）</td></tr>
 * </table>
 *
 * <h3>支持的效果类型</h3>
 * <pre>
 * {"type":"stage",  "id":"aoe_wither"}                       // 解锁游戏阶段（GameStages，反射调用，没装就跳过）
 * {"type":"effect", "id":"minecraft:resistance", "seconds":600, "amplifier":0}
 * {"type":"command","command":"say 你见证了荣耀","as_owner":true}
 * {"type":"message","text":"你获得了凋灵的认可"}              // 只在聊天栏给 owner 看
 * {"type":"item",   "id":"minecraft:nether_star", "count":1}
 * {"type":"advancement","id":"minecraft:end/kill_dragon"}     // 给成就（criterion 用自动名）
 * </pre>
 */
public final class StatueEffects {

    /** 自定义效果的函数式接口（给其它模组/我们自己的扩展用）。 */
    @FunctionalInterface
    public interface StatueEffectHandler {
        void apply(ServerPlayer owner, BlockPos pos, String bossId);
    }

    /** 触发点。 */
    public enum Trigger {
        ON_PLACE("on_place"),
        ON_REMOVE("on_remove"),
        ON_INTERACT("on_interact");

        public final String key;

        Trigger(String key) {
            this.key = key;
        }

        public static Trigger of(String key) {
            for (Trigger t : values()) {
                if (t.key.equals(key)) {
                    return t;
                }
            }
            return null;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 2026-11-08 用户：配置从主模组目录搬到自己模组目录（旧文件会自动迁过来）。 */
    private static final Path FILE = StatueConfigDir.file("statue_effects.json", true);

    /** 代码注册的处理器：trigger → (bossId 或 "*") → 处理器列表。 */
    private static final Map<Trigger, Map<String, List<StatueEffectHandler>>> CUSTOM = new HashMap<>();

    /** 配置里解析出来的动作：trigger → (bossId 或 "*") → 动作列表。 */
    private static final Map<Trigger, Map<String, List<JsonObject>>> DATA = new HashMap<>();

    private static long loadedAt = -1L;

    private StatueEffects() {
    }

    // ------------------------------------------------------------------ 代码 API

    /** **给某个生物（或 `*`）的雕像挂一个自定义效果**。 */
    public static void register(Trigger trigger, String bossId, StatueEffectHandler handler) {
        CUSTOM.computeIfAbsent(trigger, k -> new HashMap<>())
                .computeIfAbsent(bossId == null ? "*" : bossId, k -> new ArrayList<>())
                .add(handler);
    }

    // ------------------------------------------------------------------ 触发

    /** 触发一次（place / remove / interact）。任何异常都在这里被吞掉，绝不影响方块逻辑。 */
    public static void fire(Trigger trigger, ServerPlayer owner, BlockPos pos, String bossId) {
        if (trigger == null) {
            return;
        }
        try {
            // ① 代码注册的
            Map<String, List<StatueEffectHandler>> custom = CUSTOM.get(trigger);
            if (custom != null) {
                for (String key : new String[]{bossId == null ? "" : bossId, "*"}) {
                    for (StatueEffectHandler h : custom.getOrDefault(key, List.of())) {
                        try {
                            h.apply(owner, pos, bossId);
                        } catch (Throwable t) {
                            StatueMod.LOGGER.warn("[雕像效果] 自定义处理器失败：{}", t.toString());
                        }
                    }
                }
            }
            // ② 配置文件里的
            loadIfStale();
            Map<String, List<JsonObject>> data = DATA.get(trigger);
            if (data != null) {
                for (String key : new String[]{bossId == null ? "" : bossId, "*"}) {
                    for (JsonObject action : data.getOrDefault(key, List.of())) {
                        applyJson(owner, pos, action);
                    }
                }
            }
        } catch (Throwable t) {
            StatueMod.LOGGER.warn("[雕像效果] 触发 {} 失败：{}", trigger.key, t.toString());
        }
    }

    // ------------------------------------------------------------------ 读配置

    public static synchronized void reload() {
        loadedAt = -1L;
        loadIfStale();
    }

    private static synchronized void loadIfStale() {
        long now = System.currentTimeMillis();
        if (loadedAt > 0 && now - loadedAt < 1000L) {
            return;
        }
        loadedAt = now;
        try {
            if (!Files.exists(FILE)) {
                Files.createDirectories(FILE.getParent());
                Files.writeString(FILE, TEMPLATE, StandardCharsets.UTF_8);
                StatueMod.LOGGER.info("[雕像效果] 已创建 {}", FILE);
            }
            JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<Trigger, Map<String, List<JsonObject>>> parsed = new HashMap<>();
            for (Trigger t : Trigger.values()) {
                Map<String, List<JsonObject>> perBoss = new HashMap<>();
                if (root.has(t.key) && root.get(t.key).isJsonObject()) {
                    for (var e : root.getAsJsonObject(t.key).entrySet()) {
                        if (!e.getValue().isJsonArray()) {
                            continue;
                        }
                        List<JsonObject> list = new ArrayList<>();
                        for (JsonElement el : e.getValue().getAsJsonArray()) {
                            if (el.isJsonObject()) {
                                list.add(el.getAsJsonObject());
                            }
                        }
                        if (!list.isEmpty()) {
                            perBoss.put(e.getKey(), list);
                        }
                    }
                }
                parsed.put(t, perBoss);
            }
            DATA.clear();
            DATA.putAll(parsed);
        } catch (Throwable t) {
            StatueMod.LOGGER.warn("[雕像效果] 读配置失败（按空处理）：{}", t.toString());
        }
    }

    // ------------------------------------------------------------------ 执行一个动作

    private static void applyJson(ServerPlayer owner, BlockPos pos, JsonObject act) {
        if (owner == null || act == null) {
            return;
        }
        String type = act.has("type") ? act.get("type").getAsString() : "";
        switch (type) {
            case "stage" -> {
                String id = str(act, "id");
                if (!id.isEmpty()) {
                    addStage(owner, id);
                }
            }
            case "effect" -> {
                String id = str(act, "id");
                ResourceLocation rl = ResourceLocation.tryParse(id);
                if (rl == null) {
                    return;
                }
                MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(rl);
                if (effect == null) {
                    StatueMod.LOGGER.warn("[雕像效果] 没有这个效果：{}", id);
                    return;
                }
                int seconds = act.has("seconds") ? act.get("seconds").getAsInt() : 10;
                int amp = act.has("amplifier") ? act.get("amplifier").getAsInt() : 0;
                owner.addEffect(new MobEffectInstance(effect, Math.max(1, seconds) * 20, Math.max(0, amp),
                        false, true, true));
            }
            case "command" -> {
                String cmd = str(act, "command");
                if (cmd.isEmpty() || owner.getServer() == null) {
                    return;
                }
                String finalCmd = cmd.replace("%player%", owner.getGameProfile().getName());
                owner.getServer().getCommands().performPrefixedCommand(
                        owner.createCommandSourceStack().withSuppressedOutput(), finalCmd);
            }
            case "message" -> {
                String text = str(act, "text");
                if (!text.isEmpty()) {
                    owner.displayClientMessage(Component.literal(text), false);
                }
            }
            case "item" -> {
                String id = str(act, "id");
                ResourceLocation rl = ResourceLocation.tryParse(id);
                Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
                if (item == null) {
                    StatueMod.LOGGER.warn("[雕像效果] 没有这个物品：{}", id);
                    return;
                }
                int count = act.has("count") ? Math.max(1, act.get("count").getAsInt()) : 1;
                ItemStack stack = new ItemStack(item, count);
                if (!owner.getInventory().add(stack)) {
                    owner.drop(stack, false);
                }
            }
            case "advancement" -> {
                String id = str(act, "id");
                if (!id.isEmpty() && owner.getServer() != null) {
                    owner.getServer().getCommands().performPrefixedCommand(
                            owner.createCommandSourceStack().withSuppressedOutput(),
                            "advancement grant " + owner.getGameProfile().getName() + " only " + id);
                }
            }
            default -> StatueMod.LOGGER.warn("[雕像效果] 未知类型：{}", type);
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : "";
    }

    /** 给游戏阶段（反射调 GameStages，没装就静默跳过）。 */
    private static void addStage(ServerPlayer player, String stage) {
        if (!ModList.get().isLoaded("gamestages")) {
            StatueMod.LOGGER.debug("[雕像效果] 没装 gamestages，跳过阶段 {}", stage);
            return;
        }
        try {
            Class<?> helper = Class.forName("net.darkhax.gamestages.GameStageHelper");
            Method add = helper.getMethod("addStage",
                    net.minecraft.server.level.ServerPlayer.class, String[].class);
            add.invoke(null, player, new String[]{stage});
            StatueMod.LOGGER.debug("[雕像效果] {} 解锁阶段 {}", player.getGameProfile().getName(), stage);
        } catch (Throwable t) {
            StatueMod.LOGGER.warn("[雕像效果] 解锁阶段 {} 失败：{}", stage, t.toString());
        }
    }

    /** 配置模板（第一次运行自动写出来，带示例）。 */
    private static final String TEMPLATE = """
            {
              "_comment": "雕像自定义效果。结构：触发时机 -> 生物id 或 * -> 效果数组。改完在游戏里执行 /aoestatue reloadfx 生效。",
              "_triggers": "on_place 放下时 / on_remove 拆掉时 / on_interact 空手右键时",
              "_types": "stage(游戏阶段) / effect(状态效果) / command(指令, %player% 会替换成玩家名) / message(聊天) / item(物品) / advancement(成就)",
              "on_place": {
                "*": [
                  {"type": "message", "text": "你摆下了一尊雕像"}
                ],
                "minecraft:wither": [
                  {"type": "stage", "id": "aoe_wither_witness"},
                  {"type": "effect", "id": "minecraft:resistance", "seconds": 600, "amplifier": 0}
                ]
              },
              "on_remove": {
                "minecraft:wither": [
                  {"type": "message", "text": "凋灵的注视消失了"}
                ]
              },
              "on_interact": {
                "*": []
              }
            }
            """;
}
