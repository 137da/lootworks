package com.example.lootworks.lootprobe.compat.jei;

import com.example.lootworks.LootWorks;
import com.example.lootworks.lootprobe.LootProbeItems;

import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI 自定义「世界交互」配方分类：极简图形布局（输入 → 产物，箭头上方悬浮触发图标），
 * 条件细节通过悬停 Tooltip 展示，卡片内不堆砌长段文字。
 */
public final class WorldInteractionCategory implements IRecipeCategory<WorldInteractionRecipe> {
    public static final RecipeType<WorldInteractionRecipe> RECIPE_TYPE =
            RecipeType.create(LootWorks.MOD_ID, "world_interaction", WorldInteractionRecipe.class);

    private static final int WIDTH = 130; // 配方卡片宽度
    private static final int HEIGHT = 44; // 配方卡片高度
    private static final int INPUT_SLOT_X = 18; // 左侧输入槽外框 X
    private static final int INPUT_SLOT_Y = 18; // 左侧输入槽外框 Y
    private static final int CATALYST_X = 57; // 箭头上方触发图标 X（与箭头水平居中对齐）
    private static final int CATALYST_Y = 3; // 箭头上方触发图标 Y
    private static final int ARROW_X = 54; // 箭头 X
    private static final int ARROW_Y = 20; // 箭头 Y
    private static final int OUTPUT_SLOT_X = 90; // 右侧输出大槽外框 X
    private static final int OUTPUT_SLOT_Y = 14; // 右侧输出大槽外框 Y

    private final IDrawable icon; // 分类标签图标
    private final IDrawableStatic slotDrawable; // 普通输入槽贴图（18x18）
    private final IDrawableStatic outputSlotDrawable; // 产物大槽贴图（26x26）
    private final IDrawableStatic arrowDrawable; // 箭头贴图（22x16）

    public WorldInteractionCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableItemLike(LootProbeItems.SKELETON_KEY.get());
        this.slotDrawable = guiHelper.getSlotDrawable();
        this.outputSlotDrawable = guiHelper.getOutputSlot();
        this.arrowDrawable = guiHelper.getRecipeArrow();
    }

    @Override
    public RecipeType<WorldInteractionRecipe> getRecipeType() {
        return RECIPE_TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.lootworks.world_interaction.title");
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public ResourceLocation getRegistryName(WorldInteractionRecipe recipe) {
        return recipe.id();
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, WorldInteractionRecipe recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, INPUT_SLOT_X + 1, INPUT_SLOT_Y + 1)
                .addItemStack(recipe.input())
                .addRichTooltipCallback((slotView, tooltip) ->
                        tooltip.add(Component.translatable(recipe.inputTipKey()).withStyle(ChatFormatting.GOLD)));

        // 触发物悬浮在箭头上方，不画槽位边框，避免玩家误以为需要放入工作台合成
        builder.addSlot(RecipeIngredientRole.CATALYST, CATALYST_X, CATALYST_Y)
                .addItemStack(recipe.catalyst())
                .addRichTooltipCallback((slotView, tooltip) ->
                        tooltip.add(Component.translatable(recipe.catalystTipKey()).withStyle(ChatFormatting.GOLD)));

        builder.addSlot(RecipeIngredientRole.OUTPUT, OUTPUT_SLOT_X + 5, OUTPUT_SLOT_Y + 5)
                .addItemStack(recipe.output());
    }

    @Override
    public void draw(WorldInteractionRecipe recipe, IRecipeSlotsView recipeSlotsView,
                     GuiGraphics guiGraphics, double mouseX, double mouseY) {
        slotDrawable.draw(guiGraphics, INPUT_SLOT_X, INPUT_SLOT_Y);
        arrowDrawable.draw(guiGraphics, ARROW_X, ARROW_Y);
        outputSlotDrawable.draw(guiGraphics, OUTPUT_SLOT_X, OUTPUT_SLOT_Y);
    }

    @Override
    public void getTooltip(ITooltipBuilder tooltip, WorldInteractionRecipe recipe,
                           IRecipeSlotsView recipeSlotsView, double mouseX, double mouseY) {
        // 鼠标移到箭头上时同样提示触发条件
        if (mouseX >= ARROW_X && mouseX <= ARROW_X + arrowDrawable.getWidth()
                && mouseY >= ARROW_Y && mouseY <= ARROW_Y + arrowDrawable.getHeight()) {
            tooltip.add(Component.translatable(recipe.catalystTipKey()).withStyle(ChatFormatting.GRAY));
        }
    }
}
