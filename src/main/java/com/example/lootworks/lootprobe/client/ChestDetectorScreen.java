package com.example.lootworks.lootprobe.client;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.example.lootworks.lootprobe.ChestDetectorMenu;
import com.example.lootworks.lootprobe.ChestDetectorResultPayload;
import com.example.lootworks.lootprobe.FateMaterial;
import com.example.lootworks.lootprobe.LootIndex;
import com.example.lootworks.lootprobe.LootProbeItems;
import com.example.lootworks.lootprobe.compat.jech.JechCompat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

/**
 * 原版容器风格的战利品概率列表 + 改命面板（目标预览、材料槽、改命按钮）+ 玩家背包。
 * <p>
 * 顶部有搜索栏（物品名，装了 JECH 时支持拼音；{@code @xxx} 按模组筛选）和深度搜索按钮。
 * 搜索只影响显示，列表里记的是原始下标，选目标时照样发原始下标给服务端。
 */
public final class ChestDetectorScreen extends AbstractContainerScreen<ChestDetectorMenu> {
    private static final String TEXT_KEY = "screen.lootworks.chest_detector.";
    private static final int COLUMNS = 2; // 同一行按概率从左到右排列
    private static final int VISIBLE_ROWS = 4; // 下方要放改命面板和背包，列表只露 4 行，其余靠滚动
    private static final int ROW_HEIGHT = 24;
    private static final int COLUMN_GAP = 4;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 25;
    private static final int LIST_HEIGHT = VISIBLE_ROWS * ROW_HEIGHT;
    private static final int CARD_WIDTH = 137;
    private static final int LIST_RIGHT = LIST_X + COLUMNS * CARD_WIDTH + COLUMN_GAP;
    private static final int SCROLL_X = LIST_RIGHT + 2;
    private static final int SCROLL_WIDTH = 7;
    private static final int TEXT_OFFSET = 24; // 给图标留出空间
    private static final int BAR_WIDTH = 68;
    private static final int TEXT_COLOR = 0x404040;
    private static final int PANEL_TEXT_X = 30;                          // 改命面板文字起点（材料槽右侧）
    private static final int FATE_BUTTON_WIDTH = 60;
    private static final int FATE_BUTTON_Y = ChestDetectorMenu.MATERIAL_Y - 2;
    private static final int BOOSTED_COLOR = 0x2E7D32;                   // 改命后概率用绿色
    private static final int TOP_BAR_Y = 6;                              // 标题行控件的顶边
    private static final int TOP_BAR_HEIGHT = 14;                        // 标题行控件的高度
    private static final int SEARCH_WIDTH = 100;                         // 搜索栏宽度
    private static final int SEARCH_X = SCROLL_X + SCROLL_WIDTH - SEARCH_WIDTH; // 搜索栏右边与滚动条对齐
    private static final int TOP_BAR_GAP = 3;                            // 深度搜索按钮与搜索栏的间距
    private static final int DEEP_REQUEST_TICKS = 40;                    // 点了深度搜索后等服务端回应的 tick 数，期间按钮不可再点
    private static final int STATUS_Y = LIST_Y + LIST_HEIGHT + 3;        // 列表下方状态行（未命中候选 / 深度搜索进度）

    /** searchName、modId、modName 都是小写，供搜索匹配 */
    private record DropView(ItemStack stack, Component name, boolean potionGroup, double chance, String percentage,
                            String searchName, String modId, String modName) {}

    private ChestDetectorResultPayload displayedResult; // 只在收到新结果时准备显示内容
    private List<DropView> drops = List.of();
    private List<Integer> visible = List.of(); // 筛选后显示的结果（drops 的下标）
    private String query = ""; // 搜索栏内容，窗口缩放重建控件时保留
    private int firstRow;
    private int dragOffset; // 按下的位置到滑块顶端的距离
    private boolean draggingScrollbar;
    private Button fateButton;
    private Button deepButton;
    private EditBox searchBox;
    private int deepRequestTicks; // 大于 0 时表示刚点过深度搜索，还在等服务端回应
    private String deepTooltipKey = ""; // 当前深度搜索按钮提示的语言键，变了才重建提示

    public ChestDetectorScreen(ChestDetectorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = SCROLL_X + SCROLL_WIDTH + 7;
        imageHeight = ChestDetectorMenu.HOTBAR_Y + 24;
        inventoryLabelX = ChestDetectorMenu.INVENTORY_X - 1;
        inventoryLabelY = ChestDetectorMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        fateButton = addRenderableWidget(Button.builder(Component.translatable(TEXT_KEY + "fate"),
                        button -> sendButton(ChestDetectorMenu.BUTTON_FATE))
                .bounds(leftPos + LIST_RIGHT - FATE_BUTTON_WIDTH, topPos + FATE_BUTTON_Y, FATE_BUTTON_WIDTH, 20)
                .build());
        fateButton.active = false;

        Component deepLabel = Component.translatable(TEXT_KEY + "deep");
        int deepWidth = Math.max(48, font.width(deepLabel) + 10); // 按文字宽度适配中英文
        deepButton = addRenderableWidget(Button.builder(deepLabel, button -> {
                    sendButton(ChestDetectorMenu.BUTTON_DEEP_SEARCH);
                    deepRequestTicks = DEEP_REQUEST_TICKS;
                })
                .bounds(leftPos + SEARCH_X - TOP_BAR_GAP - deepWidth, topPos + TOP_BAR_Y, deepWidth, TOP_BAR_HEIGHT)
                .build());
        deepButton.active = false;
        deepTooltipKey = "";

        searchBox = addRenderableWidget(new EditBox(font, leftPos + SEARCH_X, topPos + TOP_BAR_Y, SEARCH_WIDTH,
                TOP_BAR_HEIGHT, Component.translatable(TEXT_KEY + "search_hint")));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.translatable(TEXT_KEY + "search_hint").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setValue(query);
        searchBox.setResponder(text -> {
            query = text;
            applyFilter();
        });
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        int pending = menu.takePendingButton(); // 深度搜索换结果后重新定位的选择，发回服务端
        if (pending >= 0) sendButton(pending);
        // 选了可改命的目标（概率在 0~100% 之间）、放了材料、结果可用时才能改命；最终校验仍在服务端
        int selected = menu.selected();
        boolean validTarget = selected >= 0 && selected < drops.size()
                && drops.get(selected).chance() > 0.0 && drops.get(selected).chance() < 1.0;
        fateButton.active = validTarget && FateMaterial.of(menu.materialStack()) != null
                && displayedResult != null && displayedResult.runs() > 0;
        updateDeepButton();
    }

    /** 有结果、没到上限、没在搜索时就能点；提示文字说明当前为什么能 / 不能点 */
    private void updateDeepButton() {
        LootIndex.DeepProgress progress = menu.deepProgress();
        if (progress != null) deepRequestTicks = 0; // 服务端已经开始
        else if (deepRequestTicks > 0) deepRequestTicks--;

        boolean ready = displayedResult != null && displayedResult.runs() > 0;
        boolean canDeepen = ready && LootIndex.canDeepen(displayedResult.runs());
        deepButton.active = canDeepen && progress == null && deepRequestTicks == 0;

        String key = !ready || progress != null || canDeepen ? "deep_tooltip" : "deep_max"; // 提示的语言键
        int runs = ready ? displayedResult.runs() : 0;
        String tooltipKey = key + "/" + runs; // 采样次数变了也要刷新提示
        if (!tooltipKey.equals(deepTooltipKey)) {
            deepTooltipKey = tooltipKey;
            Component text = key.equals("deep_max")
                    ? Component.translatable(TEXT_KEY + key, formatCount(LootIndex.DEEP_MAX_RUNS))
                    : Component.translatable(TEXT_KEY + key, formatCount(LootIndex.DEEP_STEP_RUNS), formatCount(runs));
            deepButton.setTooltip(Tooltip.create(text));
        }
    }

    /** 按钮编号交给原版发包，服务端在 clickMenuButton 里处理 */
    private void sendButton(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ChestDetectorResultPayload result = menu.result();
        if (result != null && result != displayedResult) {
            displayedResult = result;
            drops = result.drops().stream().map(drop -> {
                // 用药水颜色区分图标。名称和悬停说明都用原版的瓶型通用名称。
                ItemStack stack = drop.potionGroup() ? PotionContents.createItemStack(drop.item(), Potions.REGENERATION)
                        : drop.item().getDefaultInstance();
                Component name = drop.potionGroup() ? Component.translatable(drop.item().getDescriptionId())
                        : stack.getHoverName();
                double chance = drop.chance();
                String modId = BuiltInRegistries.ITEM.getKey(drop.item()).getNamespace();
                return new DropView(stack, name, drop.potionGroup(), chance, formatChance(chance),
                        lower(name.getString()), modId, lower(modName(modId)));
            }).toList();
            applyFilter(); // 深度搜索换结果时保留搜索内容和滚动位置
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY); // 材料槽和背包里物品的悬停说明
        int hoveredIndex = hoveredDrop(mouseX, mouseY);
        if (hoveredIndex >= 0) {
            DropView drop = drops.get(hoveredIndex);
            if (drop.potionGroup()) {
                graphics.renderTooltip(font, drop.name(), mouseX, mouseY);
            } else {
                graphics.renderTooltip(font, drop.stack(), mouseX, mouseY);
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.pose().pushPose();
        graphics.pose().translate(leftPos, topPos, 0);

        bevel(graphics, 0, 0, imageWidth, imageHeight, 0xFFC6C6C6, 0xFFFFFFFF, 0xFF565656);
        bevel(graphics, LIST_X - 1, LIST_Y - 1, LIST_RIGHT + 1, LIST_Y + LIST_HEIGHT + 1,
                0xFFDEDEDE, 0xFF8B8B8B, 0xFFFFFFFF);
        int hoveredIndex = hoveredDrop(mouseX, mouseY);
        int visibleDrops = Math.min(VISIBLE_ROWS * COLUMNS, visible.size() - firstRow * COLUMNS);
        for (int cell = 0; cell < visibleDrops; cell++) {
            int dropIndex = visible.get(firstRow * COLUMNS + cell); // 原始下标
            int x = LIST_X + (cell % COLUMNS) * (CARD_WIDTH + COLUMN_GAP);
            int y = LIST_Y + (cell / COLUMNS) * ROW_HEIGHT;
            renderDrop(graphics, drops.get(dropIndex), x, y, dropIndex == hoveredIndex, dropIndex == menu.selected());
        }

        bevel(graphics, SCROLL_X, LIST_Y - 1, SCROLL_X + SCROLL_WIDTH, LIST_Y + LIST_HEIGHT + 1,
                0xFF8B8B8B, 0xFF565656, 0xFFFFFFFF);
        if (maxScroll() > 0) {
            int thumbTop = thumbY();
            bevel(graphics, SCROLL_X + 1, thumbTop, SCROLL_X + SCROLL_WIDTH - 1, thumbTop + thumbHeight(),
                    0xFFC6C6C6, 0xFFFFFFFF, 0xFF565656);
        }

        for (Slot slot : menu.slots) { // 槽位框：原版的凹陷样式
            bevel(graphics, slot.x - 1, slot.y - 1, slot.x + 17, slot.y + 17, 0xFF8B8B8B, 0xFF373737, 0xFFFFFFFF);
        }

        graphics.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 8, TEXT_COLOR, false);

        if (visible.isEmpty()) {
            String status = "pending";
            if (displayedResult != null) status = displayedResult.runs() == 0 ? "failed" : "empty";
            if (!drops.isEmpty()) status = "no_match"; // 有结果但被搜索筛空了
            Component message = Component.translatable(TEXT_KEY + status);
            graphics.drawString(font, message, (LIST_X + LIST_RIGHT - font.width(message)) / 2,
                    LIST_Y + (LIST_HEIGHT - font.lineHeight) / 2, TEXT_COLOR, false);
        }
        // 状态行：深度搜索进度优先；未命中候选数只在开发环境显示（主人定了正式版不显示这一行）
        LootIndex.DeepProgress progress = menu.deepProgress();
        if (progress != null) {
            graphics.drawString(font, Component.translatable(TEXT_KEY + "deep_running",
                    formatCount(progress.runs()), formatCount(progress.target())), LIST_X, STATUS_Y, BOOSTED_COLOR, false);
        } else if (!FMLLoader.isProduction() && displayedResult != null && displayedResult.unobserved() > 0) {
            graphics.drawString(font, Component.translatable(TEXT_KEY + "unobserved", displayedResult.unobserved()),
                    LIST_X, STATUS_Y, TEXT_COLOR, false);
        }
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT_COLOR, false);
        renderFatePanel(graphics);
    }

    /** 改命面板两行字：第一行「目标  原概率 → 新概率」，第二行材料用量或操作提示 */
    private void renderFatePanel(GuiGraphics graphics) {
        if (displayedResult == null || displayedResult.runs() == 0 || drops.isEmpty()) return;
        int lineY = ChestDetectorMenu.MATERIAL_Y + 1;
        int maxWidth = LIST_RIGHT - FATE_BUTTON_WIDTH - 4 - PANEL_TEXT_X;
        int selected = menu.selected();
        if (selected < 0 || selected >= drops.size()) {
            graphics.drawString(font, Component.translatable(TEXT_KEY + "pick_target"), PANEL_TEXT_X, lineY + 4,
                    TEXT_COLOR, false);
            return;
        }

        DropView drop = drops.get(selected);
        ItemStack stack = menu.materialStack();
        FateMaterial material = FateMaterial.of(stack);
        boolean alreadyGuaranteed = drop.chance() >= 1.0;
        String chances = drop.percentage();
        String boosted = (material == null || alreadyGuaranteed) ? ""
                : " → " + formatChance(material.boost(drop.chance(), stack));
        int chanceWidth = font.width(chances + boosted);
        String name = drop.name().getString();
        int nameWidth = maxWidth - chanceWidth - 6;
        if (font.width(name) > nameWidth) {
            name = font.plainSubstrByWidth(name, Math.max(0, nameWidth - font.width("…"))) + "…";
        }
        graphics.drawString(font, name, PANEL_TEXT_X, lineY, TEXT_COLOR, false);
        int chanceX = PANEL_TEXT_X + maxWidth - chanceWidth;
        graphics.drawString(font, chances, chanceX, lineY, TEXT_COLOR, false);
        graphics.drawString(font, boosted, chanceX + font.width(chances), lineY, BOOSTED_COLOR, false);

        Component hint = alreadyGuaranteed ? Component.translatable(TEXT_KEY + "already_guaranteed")
                : material == null ? Component.translatable(TEXT_KEY + "put_material")
                : stack.is(LootProbeItems.DESTINED_CRYSTAL.get()) ? Component.translatable(TEXT_KEY + "destined_use")
                : Component.translatable(TEXT_KEY + "material_use", material.usedCount(stack), material.maxCount());
        graphics.drawString(font, hint, PANEL_TEXT_X, lineY + 10, 0x707070, false);
    }

    /** 名称与概率分两行，给中文和较长的物品名都留出空间。 */
    private void renderDrop(GuiGraphics graphics, DropView drop, int x, int y, boolean hovered, boolean selected) {
        int background = selected ? 0xFFC8E6C9 : hovered ? 0xFFFFE9B8 : 0xFFF0F0F0; // 选中绿色，悬停浅金
        graphics.fill(x, y, x + CARD_WIDTH, y + ROW_HEIGHT - 1, background);
        if (selected) {
            graphics.renderOutline(x, y, CARD_WIDTH, ROW_HEIGHT - 1, 0xFF2E7D32);
        }
        graphics.renderItem(drop.stack(), x + 4, y + 4);

        int nameX = x + TEXT_OFFSET;
        int nameWidth = CARD_WIDTH - TEXT_OFFSET - 4;
        String name = drop.name().getString();
        if (font.width(name) > nameWidth) {
            name = font.plainSubstrByWidth(name, nameWidth - font.width("…")) + "…";
        }
        graphics.drawString(font, name, nameX, y + 3, TEXT_COLOR, false);

        graphics.fill(nameX, y + 15, nameX + BAR_WIDTH, y + 20, 0xFFB7B7B7);
        int filled = Math.max(1, (int) Math.round(BAR_WIDTH * drop.chance()));
        graphics.fill(nameX, y + 15, nameX + filled, y + 20, 0xFFBA7926);
        graphics.drawString(font, drop.percentage(), x + CARD_WIDTH - 4 - font.width(drop.percentage()),
                y + 13, TEXT_COLOR, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0 && scrollY != 0 && inList(mouseX, mouseY, LIST_X, SCROLL_X + SCROLL_WIDTH)) {
            int steps = (int) Math.ceil(Math.abs(scrollY));
            firstRow = Mth.clamp(firstRow + (scrollY > 0 ? -steps : steps), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (searchBox.isMouseOver(mouseX, mouseY)) {
            if (button == 1) searchBox.setValue(""); // 和原版创造栏一样，右键清空
        } else if (getFocused() == searchBox) {
            setFocused(null); // 点别处时搜索栏失焦，背包快捷键恢复可用
        }
        int clicked = button == 0 ? hoveredDrop((int) mouseX, (int) mouseY) : -1;
        if (clicked >= 0 && menu.select(clicked)) { // 本地先更新显示，再把选择同步给服务端
            sendButton(ChestDetectorMenu.BUTTON_SELECT_BASE + clicked);
            return true;
        }
        if (button == 0 && maxScroll() > 0 && inList(mouseX, mouseY, SCROLL_X, SCROLL_X + SCROLL_WIDTH)) {
            int offset = (int) mouseY - topPos - thumbY();
            dragOffset = offset >= 0 && offset < thumbHeight() ? offset : thumbHeight() / 2;
            draggingScrollbar = true;
            scrollTo(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingScrollbar) {
            scrollTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 搜索栏有焦点时吞掉按键，避免打字时按 E 关窗、按数字键换快捷栏；Esc 仍然关窗 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            searchBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------------------------------------------------------- 搜索

    /** 按搜索内容重新筛选；空格分开的每个词都要匹配，{@code @} 开头的词匹配模组 ID 或模组名 */
    private void applyFilter() {
        String[] terms = lower(query).trim().split("\\s+");
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < drops.size(); i++) {
            if (matches(drops.get(i), terms)) matched.add(i);
        }
        visible = List.copyOf(matched);
        firstRow = Mth.clamp(firstRow, 0, maxScroll());
    }

    private static boolean matches(DropView drop, String[] terms) {
        for (String term : terms) {
            if (term.isEmpty()) continue;
            if (term.startsWith("@")) {
                String mod = term.substring(1);
                if (!mod.isEmpty() && !drop.modId().contains(mod) && !JechCompat.contains(drop.modName(), mod)) return false;
            } else if (!JechCompat.contains(drop.searchName(), term)) {
                return false;
            }
        }
        return true;
    }

    /** 模组显示名，找不到就用模组 ID */
    private static String modName(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getDisplayName()).orElse(modId);
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private boolean inList(double mouseX, double mouseY, int minX, int maxX) {
        return mouseX >= leftPos + minX && mouseX < leftPos + maxX
                && mouseY >= topPos + LIST_Y && mouseY < topPos + LIST_Y + LIST_HEIGHT;
    }

    /** 两列之间的间隙以及最后一行的空位没有悬停说明。 */
    private int hoveredDrop(int mouseX, int mouseY) {
        if (!inList(mouseX, mouseY, LIST_X, LIST_RIGHT)) return -1;
        int cellX = mouseX - leftPos - LIST_X;
        int cellY = mouseY - topPos - LIST_Y;
        int columnStride = CARD_WIDTH + COLUMN_GAP;
        if (cellX % columnStride >= CARD_WIDTH || cellY % ROW_HEIGHT == ROW_HEIGHT - 1) return -1;
        int position = (firstRow + cellY / ROW_HEIGHT) * COLUMNS + cellX / columnStride; // 在筛选结果里的位置
        return position < visible.size() ? visible.get(position) : -1;
    }

    private int totalRows() {
        return (visible.size() + COLUMNS - 1) / COLUMNS;
    }

    private int maxScroll() {
        return Math.max(0, totalRows() - VISIBLE_ROWS);
    }

    private int thumbHeight() {
        return Math.max(12, LIST_HEIGHT * VISIBLE_ROWS / totalRows());
    }

    private int thumbY() {
        return LIST_Y + Math.round((float) firstRow * (LIST_HEIGHT - thumbHeight()) / maxScroll());
    }

    private void scrollTo(double mouseY) {
        double fraction = (mouseY - topPos - LIST_Y - dragOffset) / (LIST_HEIGHT - thumbHeight());
        firstRow = Mth.clamp((int) Math.round(fraction * maxScroll()), 0, maxScroll());
    }

    /** 使用原版容器的亮边、暗边和矩形背景，无需额外贴图。 */
    private static void bevel(GuiGraphics graphics, int x, int y, int right, int bottom, int base, int top, int shadow) {
        graphics.fill(x, y, right, bottom, base);
        graphics.fill(x, y, right - 1, y + 1, top);
        graphics.fill(x, y + 1, x + 1, bottom - 1, top);
        graphics.fill(right - 1, y, right, bottom, shadow);
        graphics.fill(x, bottom - 1, right - 1, bottom, shadow);
    }

    /** 采样次数带千分位，例如 10,000 */
    private static String formatCount(int count) {
        return NumberFormat.getIntegerInstance(Locale.ROOT).format(count);
    }

    private static String formatChance(double chance) {
        NumberFormat format = NumberFormat.getPercentInstance(Locale.ROOT);
        int precision = chance >= 0.01 ? 1
                : chance >= 0.0001 || chance <= 0.0 ? 2
                : chance >= 0.00001 ? 3 : 4; // 保留超稀有静态概率的有效位，不误显示为 0.00%
        format.setMinimumFractionDigits(precision);
        format.setMaximumFractionDigits(precision);
        return format.format(chance);
    }
}
