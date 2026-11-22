// SPDX-License-Identifier: LGPL-3.0
package com.example.lootworks.mixin;

import com.example.lootworks.LootWorks;

import net.minecraft.client.resources.language.I18n;
import net.minecraftforge.client.gui.ModListScreen;
import net.minecraftforge.client.gui.widget.ModListWidget;
import net.minecraftforge.forgespi.language.IModInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = {ModListScreen.class, ModListWidget.ModEntry.class},
        targets = "net.minecraftforge.client.gui.ModListScreen$SortType", remap = false)
abstract class ModListNameMixin {
    // 模组列表的排序、搜索、条目和详情共用当前语言下的名称。
    @Redirect(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/forgespi/language/IModInfo;getDisplayName()Ljava/lang/String;"), require = 0)
    private String lootworks$localizedName(IModInfo modInfo) {
        if (LootWorks.MOD_ID.equals(modInfo.getModId()) && I18n.exists("itemGroup.lootworks")) {
            return I18n.get("itemGroup.lootworks");
        }
        return modInfo.getDisplayName();
    }
}
