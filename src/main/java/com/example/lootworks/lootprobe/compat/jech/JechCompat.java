package com.example.lootworks.lootprobe.compat.jech;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import javax.annotation.Nullable;

import com.example.lootworks.LootWorks;

import net.minecraftforge.fml.ModList;

/**
 * 可选兼容 JustEnoughCharacters（JECH）：装了就借用它的拼音匹配，和 JEI、原版搜索的拼音行为一致
 * （多音字、模糊音等都按 JECH 自己的配置）；没装就退回普通的包含匹配。
 * <p>
 * JECH 的拼音是通过改写 JEI 等类里的 {@code String.contains} 调用实现的，不会作用到本模组的代码，
 * 所以这里主动调用它的 {@code Match.contains}。全程用反射，不直接引用 JECH 的类，没装时不会因找不到类而崩溃。
 */
public final class JechCompat {
    private static final String MOD_ID = "jecharacters"; // JECH 的模组 ID
    private static final String MATCH_CLASS = "me.towdium.jecharacters.utils.Match"; // JECH 的匹配工具类

    @Nullable
    private static volatile MethodHandle contains = find(); // Match.contains(String, CharSequence)，不可用时为空

    private JechCompat() {}

    @Nullable
    private static MethodHandle find() {
        if (!ModList.get().isLoaded(MOD_ID)) return null;
        try {
            Class<?> match = Class.forName(MATCH_CLASS);
            MethodHandle handle = MethodHandles.publicLookup().findStatic(match, "contains",
                    MethodType.methodType(boolean.class, String.class, CharSequence.class));
            LootWorks.LOGGER.info("检测界面搜索已启用 JECH 拼音匹配");
            return handle;
        } catch (ReflectiveOperationException | LinkageError error) {
            // JECH 改了内部类名或方法签名：只是没有拼音，搜索照常可用
            LootWorks.LOGGER.warn("JECH 已加载但找不到拼音匹配方法，搜索退回普通匹配", error);
            return null;
        }
    }

    /** text 是否包含 query；装了 JECH 时也认拼音（全拼、首字母等） */
    public static boolean contains(String text, String query) {
        MethodHandle handle = contains;
        if (handle != null) {
            try {
                return (boolean) handle.invokeExact(text, (CharSequence) query);
            } catch (Throwable error) {
                contains = null; // 出错一次就停用，避免每帧刷日志
                LootWorks.LOGGER.warn("JECH 拼音匹配出错，搜索退回普通匹配", error);
            }
        }
        return text.contains(query);
    }
}
