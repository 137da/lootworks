package com.example.lootworks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.lootworks.lootprobe.FateHighlights;

import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(BlockEntity.class)
public abstract class BlockEntityContainerMixin {
    @Inject(method = "setChanged()V", at = @At("TAIL"))
    private void lootworks$checkMovedLoot(CallbackInfo callback) {
        if (this instanceof Container inventory) FateHighlights.onInventoryChanged(inventory);
    }
}
