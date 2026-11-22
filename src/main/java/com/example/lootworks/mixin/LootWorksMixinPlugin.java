package com.example.lootworks.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.minecraftforge.fml.loading.LoadingModList;

/**
 * 按可选模组是否存在决定是否应用 mixin：{@code mixin.lootr} 包下的只在装了 Lootr 时应用，
 * 否则目标类不存在会让游戏启动失败。这里不能引用任何 Lootr 的类。
 */
public final class LootWorksMixinPlugin implements IMixinConfigPlugin {
    private static final String LOOTR_PACKAGE = "com.example.lootworks.mixin.lootr."; // Lootr 专用 mixin 所在包

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(LOOTR_PACKAGE)) {
            return LoadingModList.get().getModFileById("lootr") != null;
        }
        return true;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
