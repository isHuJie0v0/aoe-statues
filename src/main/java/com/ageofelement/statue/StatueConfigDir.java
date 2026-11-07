package com.ageofelement.statue;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * **雕像模组自己的配置目录**（2026-11-08）。
 *
 * <p>用户：「雕像的模组的配置在 aoe 里面吗，拿出来」——原来两个 json 落在
 * {@code config/age_of_element/}（主模组的目录），对单独发布的雕像模组来说很别扭：
 * 玩家装雕像模组却不装主模组时，那个目录根本不会存在，容易以为是坏档。</p>
 *
 * <p>现在统一放到 <b>{@code config/age_of_element_statue/}</b>：</p>
 * <pre>
 * config/age_of_element_statue/
 *   ├─ statue_bosses.json     名单（extra / exclude）
 *   └─ statue_effects.json    数据驱动效果
 * </pre>
 *
 * <p><b>向后兼容</b>：首次读取时如果新路径没有、旧路径（{@code config/age_of_element/…}）有，
 * 就把旧文件**复制**过来（旧的保留，不删，免得用户回退版本后丢配置），并打一条 INFO。</p>
 */
public final class StatueConfigDir {

    /** 目录名（config 下一级）。 */
    public static final String DIR = "age_of_element_statue";

    private StatueConfigDir() {
    }

    /** 目录路径（不存在时创建）。 */
    public static Path dir() {
        Path d = FMLPaths.CONFIGDIR.get().resolve(DIR);
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
            // 建不出来就让上层用旧路径失败时自己兜底
        }
        return d;
    }

    /**
     * 本模组某个配置文件的路径；必要时从旧位置迁移。
     *
     * @param name     文件名，例如 {@code statue_bosses.json}
     * @param migrateFromOld 是否尝试从 {@code config/age_of_element/<name>} 迁移
     */
    public static Path file(String name, boolean migrateFromOld) {
        Path target = dir().resolve(name);
        if (migrateFromOld && !Files.exists(target)) {
            Path old = FMLPaths.CONFIGDIR.get().resolve("age_of_element").resolve(name);
            if (Files.exists(old)) {
                try {
                    Files.copy(old, target, StandardCopyOption.REPLACE_EXISTING);
                    com.ageofelement.statue.StatueMod.LOGGER.info(
                            "[雕像] 配置已从旧位置迁移：{} → {}", old, target);
                } catch (IOException e) {
                    com.ageofelement.statue.StatueMod.LOGGER.warn(
                            "[雕像] 迁移配置失败（{} → {}）：{}", old, target, e.toString());
                }
            }
        }
        return target;
    }
}
