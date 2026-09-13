package com.pvzce.client.gui.editor.pages;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.LevelFileWriter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * The info page: what the level is called, what it starts with, what it pays, and where it
 * sits in the level list.
 *
 * <p>The name and description are free text, the sun and the rewards block are numbers, and
 * the theme/category rows are the level's place in the list. The page owns its slice of the
 * level file: {@link #readFrom} loads name, description, {@code initial_sun} and
 * {@code rewards} from the draft, and {@link #writeTo} hands them back to
 * {@link LevelFileWriter#info}.
 *
 * <p>Picking another theme or category is the one rename the editor allows. The new id is
 * declared here, through {@link EditorContext#setLevelId}, so the editor's save path already
 * knows it when it writes the file; moving the level file and its run save is the editor's
 * job ({@code EditorScreen.applyGroupChange}), which is why the page leaves the source file
 * alone - that is the file the move has to carry.
 */
public final class InfoPage implements EditorPage {
    private String levelName = "";
    private String description = "";
    private int initialSun = 150;
    private EditBox nameBox;
    private EditBox descriptionBox;
    private EditBox sunBox;
    /**
     * The rewards block's four editable numbers.
     *
     * <p>Kept as fields, not only as text boxes, because the info page is rebuilt on
     * every resize and a half-typed box must not become the saved value.
     */
    private EditBox rewardUnlockBox;
    private EditBox repeatCoinsBox;
    private EditBox coinDropChanceBox;
    private EditBox coinDropAmountBox;
    private EditBox coinDropBox;
    private String rewardUnlock = "";
    private int rewardRepeatCoins = LevelRewards.DEFAULT_REPEAT_COINS;
    private float rewardCoinDropChance = LevelRewards.DEFAULT_COIN_DROP_CHANCE;
    private String rewardCoinDrop = LevelRewards.DEFAULT_COIN_DROP.toString();
    private int rewardCoinDropAmount = LevelRewards.DEFAULT_COIN_DROP_AMOUNT;

    /** Vertical pitch between the info page's fields; the labels live in the gap. */
    private int infoRowPitch;

    /** The theme/category choice buttons on the info page. */
    private final List<Button> groupThemeButtons = new ArrayList<>();
    private final List<Button> groupCategoryButtons = new ArrayList<>();
    private Identifier selectedThemeId = LevelGrouping.UNCATEGORIZED;
    private Identifier selectedCategoryId = LevelGrouping.UNCATEGORIZED;
    private int groupThemeY;
    private int groupCategoryY;
    private int groupRowWidth;
    private int groupRowHeight;

    private static final String UNCATEGORIZED_KEY = "pvzce.level_uncategorized";
    private static final String UNCATEGORIZED_TEXT = "未分类";

    @Override
    public String id() {
        return "info";
    }

    @Override
    public String label() {
        return GuiLang.raw("pvzce.editor.page.info", "info");
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public boolean hasPalette() {
        return false;
    }

    /**
     * Loads the level's identity, its economy and its group from the draft.
     *
     * <p>The name keeps whatever the editor opened the level as when the file declares none:
     * a brand-new level is named by the dialog that created it, and that name must not be
     * blanked by the first page build.
     */
    @Override
    public void readFrom(EditorContext context) {
        JsonObject sourceJson = context.draft().json();
        if (sourceJson.has("name") && !sourceJson.get("name").getAsString().isBlank()) {
            levelName = sourceJson.get("name").getAsString();
        } else {
            levelName = context.levelName();
        }
        description = sourceJson.has("description") ? sourceJson.get("description").getAsString() : "";
        initialSun = sourceJson.has("initial_sun") ? sourceJson.get("initial_sun").getAsInt() : 150;
        readRewards(sourceJson);
        LevelGrouping.Group group = LevelGrouping.resolve(context.levelId(), themeIds(context),
                categoryIds(context));
        selectedThemeId = group.theme();
        selectedCategoryId = group.category();
    }

    /**
     * Commits the boxes, then writes the level's identity and economy into the draft.
     *
     * <p>A group pick renames the level: the id the rows imply is pushed to the editor here,
     * at save time, and the draft follows it. Everything the rename moves besides the id -
     * the level file and the run save - is the editor's ({@code applyGroupChange}).
     */
    @Override
    public void writeTo(EditorContext context) {
        collectInfoFields(context);
        context.setLevelName(levelName);
        Identifier renamed = idForGroup(context.levelId(), selectedThemeId, selectedCategoryId);
        if (renamed != null && !renamed.equals(context.levelId())) {
            context.setLevelId(renamed);
        }
        Identifier current = context.levelId();
        if (current != null && !current.toString().equals(context.draft().getString("id", ""))) {
            context.draft().setString("id", current.toString());
        }
        LevelFileWriter.info(context.draft(), current, levelName, description, initialSun, currentRewards());
    }

    @Override
    public void build(EditorContext context) {
        buildInfoPage(context);
    }

    @Override
    public void render(EditorContext context) {
        renderInfoPage(context);
    }

    private void buildInfoPage(EditorContext context) {
        EditorContext.Rect content = context.content();
        groupThemeButtons.clear();
        groupCategoryButtons.clear();
        int rowH = clamp(content.height() / 14, 26, 34);
        infoRowPitch = rowH + 30;
        int fieldW = Math.min(460, content.width() + context.side().width() + 10);
        int top = content.y() + content.height() - rowH;
        nameBox = context.own(new EditBox(content.x(), top, fieldW, rowH, () -> {
        }));
        nameBox.setValue(levelName, false);
        descriptionBox = context.own(new EditBox(content.x(), top - infoRowPitch, fieldW, rowH, () -> {
        }));
        descriptionBox.setValue(description, false);
        sunBox = context.own(new EditBox(content.x(), top - infoRowPitch * 2, Math.min(160, fieldW / 3), rowH,
                () -> {
                }));
        sunBox.setValue(String.valueOf(initialSun), false);

        // Rewards sit under the sun field: they are the other "what does this level
        // give the player" numbers, and keeping them on the same page means a level's
        // whole economy is in one place.
        int halfW = Math.max(80, fieldW / 2 - 6);
        rewardUnlockBox = context.own(new EditBox(content.x(), top - infoRowPitch * 3, fieldW, rowH, () -> {
        }));
        rewardUnlockBox.setValue(rewardUnlock, false);
        repeatCoinsBox = context.own(new EditBox(content.x(), top - infoRowPitch * 4, halfW, rowH, () -> {
        }));
        repeatCoinsBox.setValue(String.valueOf(rewardRepeatCoins), false);
        coinDropChanceBox = context.own(new EditBox(content.x() + halfW + 12, top - infoRowPitch * 4,
                halfW, rowH, () -> {
        }));
        coinDropChanceBox.setValue(GuiText.formatFloat(rewardCoinDropChance), false);
        coinDropAmountBox = context.own(new EditBox(content.x(), top - infoRowPitch * 5, halfW, rowH, () -> {
        }));
        coinDropAmountBox.setValue(String.valueOf(rewardCoinDropAmount), false);
        // Which coin a zombie leaves: the four denominations are separate resources
        // (10 / 50 / 1000 / 250), so the level picks one by id.
        coinDropBox = context.own(new EditBox(content.x() + halfW + 12, top - infoRowPitch * 5,
                halfW, rowH, () -> {
        }));
        coinDropBox.setValue(rewardCoinDrop, false);

        groupRowWidth = fieldW;
        groupRowHeight = rowH;
        groupThemeY = top - infoRowPitch * 6;
        groupCategoryY = groupThemeY - rowH - 14;
        LevelGrouping.Group group = LevelGrouping.resolve(context.levelId(), themeIds(context),
                categoryIds(context));
        selectedThemeId = group.theme();
        selectedCategoryId = group.category();
        buildGroupRows(context);
    }

    private void renderInfoPage(EditorContext context) {
        // Each label sits in the gap above its own field. The offsets used to be
        // hand-picked constants that put "初始阳光" on top of the sun field.
        if (nameBox != null) {
            float labelScale = 0.75F;
            float left = context.content().x();
            context.client().font().draw(GuiLang.raw("pvzce.editor.level_name", "关卡名称"),
                    left, nameBox.y() + nameBox.height() + 6F, labelScale, 0.9F, 0.9F, 0.9F, 1F);
            context.client().font().draw(GuiLang.raw("pvzce.editor.level_desc", "关卡描述"),
                    left, descriptionBox.y() + descriptionBox.height() + 6F,
                    labelScale, 0.9F, 0.9F, 0.9F, 1F);
            context.client().font().draw(GuiLang.raw("pvzce.editor.initial_sun", "初始阳光"),
                    left, sunBox.y() + sunBox.height() + 6F, labelScale, 0.9F, 0.9F, 0.9F, 1F);
            float rewardLabelY = rewardUnlockBox.y() + rewardUnlockBox.height() + 6F;
            context.client().font().draw(GuiLang.raw("pvzce.editor.reward_unlock", "首通解锁卡（留空则不给卡）"),
                    left, rewardLabelY, labelScale, 0.9F, 0.9F, 0.9F, 1F);
            context.client().font().draw(GuiLang.raw("pvzce.editor.reward_repeat", "重复通关金币"),
                    left, repeatCoinsBox.y() + repeatCoinsBox.height() + 6F,
                    labelScale, 0.9F, 0.9F, 0.9F, 1F);
            context.client().font().draw(GuiLang.raw("pvzce.editor.reward_drop_chance", "僵尸掉币概率 0~1"),
                    coinDropChanceBox.x(), coinDropChanceBox.y() + coinDropChanceBox.height() + 6F,
                    labelScale, 0.9F, 0.9F, 0.9F, 1F);
            context.client().font().draw(GuiLang.raw("pvzce.editor.reward_drop_amount", "掉币数量"),
                    coinDropAmountBox.x(), coinDropAmountBox.y() + coinDropAmountBox.height() + 6F,
                    labelScale, 0.9F, 0.9F, 0.9F, 1F);
            context.client().font().draw(GuiLang.raw("pvzce.editor.reward_drop_coin", "掉哪种币"),
                    coinDropBox.x(), coinDropBox.y() + coinDropBox.height() + 6F,
                    labelScale, 0.9F, 0.9F, 0.9F, 1F);
            context.client().font().draw(GuiLang.raw("pvzce.editor.readonly_id", "关卡 ID 创建后不可修改")
                            + "：" + context.levelId(),
                    left, sunBox.y() - 22F, labelScale, 0.78F, 0.84F, 0.84F, 1F);
            // The group rows: picking another one and saving moves the level, which
            // renames its id - the one rename the info page is allowed to make.
            context.client().font().draw(GuiLang.raw("pvzce.editor.theme", "主题"),
                    left, groupThemeY + groupRowHeight + 4F, labelScale, 0.9F, 0.9F, 0.9F, 1F);
            context.client().font().draw(GuiLang.raw("pvzce.editor.category", "类别"),
                    left, groupCategoryY + groupRowHeight + 4F, labelScale, 0.9F, 0.9F, 0.9F, 1F);
            context.client().font().draw(GuiLang.raw("pvzce.editor.group_move_hint",
                            "换组并保存会重命名关卡 ID，并移动关卡文件与该关存档"),
                    left, groupCategoryY - 20F, 0.68F, 0.85F, 0.8F, 0.6F, 1F);
        }
    }

    /**
     * Pulls the boxes into the fields.
     *
     * <p>The rewards numbers live under the sun field, so a box that never took focus still
     * contributes what it was built with; an author who typed a number and hit 保存 on the
     * same frame gets the number they typed.
     */
    private void collectInfoFields(EditorContext context) {
        if (nameBox != null) {
            levelName = nameBox.value().isBlank() ? context.levelId().path() : nameBox.value().trim();
        }
        if (descriptionBox != null) {
            description = descriptionBox.value().trim();
        }
        if (sunBox != null) {
            initialSun = GuiText.parseInt(sunBox.value(), initialSun, 0, 100_000);
            sunBox.setValue(String.valueOf(initialSun), false);
            rewardUnlock = rewardUnlockBox.value().trim();
            rewardRepeatCoins = GuiText.parseInt(repeatCoinsBox.value(), rewardRepeatCoins, 0, 100_000);
            rewardCoinDropChance = GuiText.parseFloat(coinDropChanceBox.value(), rewardCoinDropChance, 0F, 1F);
            rewardCoinDropAmount = GuiText.parseInt(coinDropAmountBox.value(), rewardCoinDropAmount, 0, 1000);
            rewardCoinDrop = coinDropBox.value().trim();
        }
    }

    /** The rewards the info page currently describes. */
    private LevelRewards currentRewards() {
        List<LevelRewards.Reward> firstClear = new ArrayList<>();
        Identifier unlock = Identifier.tryParse(rewardUnlock);
        if (unlock != null) {
            firstClear.add(LevelRewards.Reward.unlock(unlock));
        }
        List<LevelRewards.Reward> repeat = rewardRepeatCoins > 0
                ? List.of(LevelRewards.Reward.coins(rewardRepeatCoins))
                : List.of();
        Identifier drop = Identifier.tryParse(rewardCoinDrop);
        if (drop == null) {
            drop = LevelRewards.DEFAULT_COIN_DROP;
        }
        return new LevelRewards(firstClear, repeat, rewardCoinDropChance, drop, rewardCoinDropAmount);
    }

    /**
     * Reads the editable half of a level's {@code rewards} block.
     *
     * <p>Only the numbers the info page shows come back: first clear keeps its first
     * unlock and drops the rest with a log line, because normalising silently is how
     * an author loses a reward they wrote by hand.
     */
    private void readRewards(JsonObject sourceJson) {
        rewardUnlock = "";
        rewardRepeatCoins = LevelRewards.DEFAULT_REPEAT_COINS;
        rewardCoinDropChance = LevelRewards.DEFAULT_COIN_DROP_CHANCE;
        rewardCoinDrop = LevelRewards.DEFAULT_COIN_DROP.toString();
        rewardCoinDropAmount = LevelRewards.DEFAULT_COIN_DROP_AMOUNT;
        if (!sourceJson.has("rewards") || !sourceJson.get("rewards").isJsonObject()) {
            return;
        }
        JsonObject rewards = sourceJson.getAsJsonObject("rewards");
        if (rewards.has("first_clear") && rewards.get("first_clear").isJsonArray()) {
            JsonArray firstClear = rewards.getAsJsonArray("first_clear");
            for (JsonElement element : firstClear) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (entry.has("id") && entry.get("id").isJsonPrimitive()) {
                    if (rewardUnlock.isEmpty()) {
                        rewardUnlock = entry.get("id").getAsString();
                    } else {
                        System.err.println("[PVZCE] Level grants more than one card on a first clear;"
                                + " the editor edits the first one and keeps the rest only until the"
                                + " next save: " + entry.get("id").getAsString());
                    }
                }
            }
        }
        if (rewards.has("repeat") && rewards.get("repeat").isJsonArray()) {
            int coins = 0;
            for (JsonElement element : rewards.getAsJsonArray("repeat")) {
                if (element.isJsonObject() && element.getAsJsonObject().has("amount")) {
                    coins += element.getAsJsonObject().get("amount").getAsInt();
                }
            }
            rewardRepeatCoins = Math.max(0, coins);
        }
        if (rewards.has("coin_drop_chance")) {
            rewardCoinDropChance = com.pvzce.common.util.MathUtil.clamp(
                    rewards.get("coin_drop_chance").getAsFloat(), 0F, 1F);
        }
        if (rewards.has("coin_drop") && rewards.get("coin_drop").isJsonPrimitive()) {
            rewardCoinDrop = rewards.get("coin_drop").getAsString();
        }
        if (rewards.has("coin_drop_amount")) {
            rewardCoinDropAmount = Math.max(0, rewards.get("coin_drop_amount").getAsInt());
        }
    }

    /** Every theme the server's tab table offers, plus the unclassified bucket. */
    private List<Identifier> themeIds(EditorContext context) {
        LinkedHashSet<Identifier> ids = new LinkedHashSet<>();
        for (var tab : context.client().levelTabs()) {
            Identifier id = Identifier.tryParse(tab.theme());
            if (!LevelGrouping.isUncategorized(id)) {
                ids.add(id);
            }
        }
        ids.add(LevelGrouping.UNCATEGORIZED);
        return List.copyOf(ids);
    }

    /** The categories the open theme offers, plus the unclassified bucket. */
    private List<Identifier> categoryIds(EditorContext context) {
        LinkedHashSet<Identifier> ids = new LinkedHashSet<>();
        for (var tab : context.client().levelTabs()) {
            if (selectedThemeId != null && selectedThemeId.toString().equals(tab.theme())) {
                Identifier id = Identifier.tryParse(tab.category());
                if (!LevelGrouping.isUncategorized(id)) {
                    ids.add(id);
                }
            }
        }
        ids.add(LevelGrouping.UNCATEGORIZED);
        return List.copyOf(ids);
    }

    private void buildGroupRows(EditorContext context) {
        buildGroupRow(context, groupThemeButtons, themeIds(context), groupThemeY, id -> {
            if (id.equals(selectedThemeId)) {
                return;
            }
            selectedThemeId = id;
            selectedCategoryId = LevelGrouping.UNCATEGORIZED;
            List<Identifier> categories = categoryIds(context);
            if (!categories.isEmpty()) {
                selectedCategoryId = categories.get(0);
            }
            rebuildGroupRows(context);
        });
        buildGroupRow(context, groupCategoryButtons, categoryIds(context), groupCategoryY, id -> {
            if (!id.equals(selectedCategoryId)) {
                selectedCategoryId = id;
                refreshGroupState(context);
            }
        });
        refreshGroupState(context);
    }

    /** Rebuilds both rows, which is what a theme change needs (its categories changed). */
    private void rebuildGroupRows(EditorContext context) {
        for (Button button : groupThemeButtons) {
            context.removeWidget(button);
        }
        for (Button button : groupCategoryButtons) {
            context.removeWidget(button);
        }
        groupThemeButtons.clear();
        groupCategoryButtons.clear();
        buildGroupRows(context);
    }

    private void buildGroupRow(EditorContext context, List<Button> target, List<Identifier> ids, int y,
                               Consumer<Identifier> onPick) {
        int gap = 4;
        int buttonWidth = Math.max(56, (groupRowWidth - gap * (ids.size() - 1)) / Math.max(1, ids.size()));
        for (int i = 0; i < ids.size(); i++) {
            Identifier id = ids.get(i);
            Button button = new Button(context.content().x() + i * (buttonWidth + gap), y, buttonWidth,
                    groupRowHeight, groupLabel(id), () -> onPick.accept(id));
            button.style(Button.Style.SEED_CHOOSER);
            // `own` rather than `addWidget`: these belong to the page, so switching away
            // and back does not leave a second copy of the rows behind.
            context.own(button);
            target.add(button);
        }
    }

    private void refreshGroupState(EditorContext context) {
        List<Identifier> themes = themeIds(context);
        for (int i = 0; i < groupThemeButtons.size() && i < themes.size(); i++) {
            groupThemeButtons.get(i).setActive(!themes.get(i).equals(selectedThemeId));
        }
        List<Identifier> categories = categoryIds(context);
        for (int i = 0; i < groupCategoryButtons.size() && i < categories.size(); i++) {
            groupCategoryButtons.get(i).setActive(!categories.get(i).equals(selectedCategoryId));
        }
    }

    private static String groupLabel(Identifier id) {
        return LevelGrouping.isUncategorized(id) ? GuiLang.raw(UNCATEGORIZED_KEY, UNCATEGORIZED_TEXT)
                : GuiLang.name(id);
    }

    /**
     * The id a group pick implies, keeping the level's own name.
     *
     * <p>The rule {@code LevelMove.idAfterMove} applies, restated on the public
     * {@link LevelGrouping} API because that helper is package-private to the editor screen:
     * moving {@code yard/adventure/1_1} to {@code redstone/minigame} gives
     * {@code redstone/minigame/1_1}, and the unclassified bucket has no group in the id.
     */
    private static Identifier idForGroup(Identifier current, Identifier theme, Identifier category) {
        if (current == null) {
            return null;
        }
        return LevelGrouping.levelId(current.namespace(), theme, category, LevelGrouping.leafName(current));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
