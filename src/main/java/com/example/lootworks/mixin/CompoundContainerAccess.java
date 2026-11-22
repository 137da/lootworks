package com.example.lootworks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;

/** 把双箱画面中的槽位映射回它所在的单箱。 */
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccess {
    @Accessor("container1")
    Container lootworks$first();

    @Accessor("container2")
    Container lootworks$second();
}
