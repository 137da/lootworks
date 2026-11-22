package com.example.lootworks.mixin.lootr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.lootworks.lootprobe.FateHighlights;

import net.minecraft.world.Container;

@Pseudo
@Mixin(targets = "noobanidus.mods.lootr.data.SpecialChestInventory", remap = false)
public abstract class LootrInventoryMixin {
    @Inject(method = "setChanged", at = @At("TAIL"), require = 0)
    private void lootworks$checkMovedLoot(CallbackInfo callback) {
        FateHighlights.onInventoryChanged((Container) (Object) this);
    }
}
