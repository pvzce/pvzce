package com.pvzce.client.gui.editor.pages;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.LevelFileWriter;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The unlock page: what a level asks for before it can be played.
 *
 * <p>Two-column, like the wave and music pages: the editable list on the left, and on
 * the right the things that can be added to it plus the purchase price. Prerequisite
 * levels are picked from the registry rather than typed from memory - a level id is a
 * path like {@code pvzce:yard/adventure/1_1}, and a typo locks the level forever with
 * no clue why.
 *
 * <p>The page owns its slice of the level file: {@link #readFrom} loads {@code unlock} into
 * its four fields, and {@link #writeTo} hands them back to {@link LevelFileWriter#unlock}.
 * A requirement the page cannot express is kept verbatim in {@link #unlockExtra} and merged
 * back, so opening a level here never quietly drops a condition the author wrote by hand.
 */
public final class UnlockPage implements EditorPage {
    /** Prerequisite level ids, comma separated; the same shape the text box edits. */
    private String unlockLevels = "";
    /** Required card ids, comma separated. */
    private String unlockCards = "";
    /** Coin price for buying the level outright; 0 means it cannot be bought. */
    private int unlockCost;
    /** Keep the level out of the list until its conditions are met. */
    private boolean unlockHidden;
    private EditBox unlockLevelsBox;
    private EditBox unlockCardsBox;
    private EditBox unlockCostBox;
    private Button unlockHiddenButton;
    /** Requirements the page cannot edit, kept verbatim across a save. */
    private JsonArray unlockExtra = new JsonArray();

    @Override
    public String id() {
        return "unlock";
    }

    @Override
    public String label() {
        return GuiLang.raw("pvzce.editor.page.unlock", "unlock");
    }

    @Override
    public int order() {
        return 80;
    }

    @Override
    public boolean hasPalette() {
        return false;
    }

    /** The {@code unlock} block is the page's whole state; the boxes are filled from it. */
    @Override
    public void readFrom(EditorContext context) {
        readUnlock(context.draft().json());
    }

    /** Commits the boxes first: a half-typed prerequisite must not lose to a rebuild. */
    @Override
    public void writeTo(EditorContext context) {
        commitUnlockFields();
        LevelFileWriter.unlock(context.draft(), unlockJson());
    }

    @Override
    public void build(EditorContext context) {
        buildUnlockPage(context);
    }

    @Override
    public void render(EditorContext context) {
        renderUnlockPage(context);
    }

    private void buildUnlockPage(EditorContext context) {
        EditorContext.Rect area = context.fullContent();
        int x = area.x();
        int y = area.y();
        int w = area.width();
        int h = area.height();
        int pad = 10;
        // Three rows in two columns, laid out bottom-up from the button row. The band is
        // about 415 logical pixels tall at 720p with the automatic GUI scale of 2, so the
        // row height comes from what is left after the button row and two label gaps -
        // deriving it from ``h / n`` instead ran the top row out of the panel and behind
        // the navigation bar.
        int gap = 16;
        int colW = Math.max(150, (w - gap) / 2);
        int rightX = x + colW + gap;
        int fieldW = colW - pad * 2;

        // Four bands stacked from the bottom: the button row, then the card row, then the
        // level row, each with room above it for its own label. The row height comes from
        // what is left, capped so a tall window does not produce absurdly deep text boxes.
        int rowH = MathUtil.clamp((h - pad * 2 - 96) / 3, 24, 40);
        int buttonY = y + pad;
        int cardsY = buttonY + rowH + 38;
        int levelsY = cardsY + rowH + 38;

        unlockLevelsBox = context.own(new EditBox(x, levelsY, fieldW, rowH, this::commitUnlockFields));
        unlockLevelsBox.setValue(unlockLevels, false);
        unlockCardsBox = context.own(new EditBox(x, cardsY, fieldW, rowH, this::commitUnlockFields));
        unlockCardsBox.setValue(unlockCards, false);
        unlockCostBox = context.own(new EditBox(rightX, levelsY, Math.max(80, fieldW / 3), rowH,
                this::commitUnlockFields));
        unlockCostBox.setValue(String.valueOf(unlockCost), false);
        unlockHiddenButton = context.own(new Button(rightX, cardsY, fieldW, rowH,
                unlockHidden ? "隐藏关：开" : "隐藏关：关", this::toggleUnlockHidden));

        int bw = Math.max(80, (w - 20) / 3);
        context.own(new Button(x, buttonY, bw, rowH, "移除最后的前置关卡", () -> removeFromUnlockBox(true)));
        context.own(new Button(x + bw + 10, buttonY, bw, rowH, "移除最后的所需卡",
                () -> removeFromUnlockBox(false)));
        context.own(new Button(x + (bw + 10) * 2, buttonY, bw, rowH, "清空条件", () -> {
            unlockLevels = "";
            unlockCards = "";
            unlockLevelsBox.setValue("", false);
            unlockCardsBox.setValue("", false);
            commitUnlockFields();
        }));
    }

    /**
     * Takes the last id back out of one of the boxes.
     *
     * <p>"Remove the last one" rather than "remove the selected one": the text box is the
     * source of truth, the list is only a picker, and a mis-click that appended the wrong
     * level should be one click to undo.
     */
    private void removeFromUnlockBox(boolean levels) {
        List<String> ids = splitIdList(levels ? unlockLevels : unlockCards);
        if (ids.isEmpty()) {
            return;
        }
        ids.remove(ids.size() - 1);
        String joined = String.join(", ", ids);
        if (levels) {
            unlockLevels = joined;
            unlockLevelsBox.setValue(joined, false);
        } else {
            unlockCards = joined;
            unlockCardsBox.setValue(joined, false);
        }
    }

    private void toggleUnlockHidden() {
        unlockHidden = !unlockHidden;
        if (unlockHiddenButton != null) {
            unlockHiddenButton.setLabel(unlockHidden ? "隐藏关：开" : "隐藏关：关");
        }
    }

    /**
     * Pulls the two id boxes into the fields.
     *
     * <p>An unknown id is reported and dropped by the {@code LevelValidator} on load, so
     * the editor keeps what was typed: refusing to save a level because a prerequisite is
     * misspelled would hide the typo behind a dialog instead of a log line.
     */
    private void commitUnlockFields() {
        if (unlockLevelsBox != null) {
            unlockLevels = unlockLevelsBox.value();
        }
        if (unlockCardsBox != null) {
            unlockCards = unlockCardsBox.value();
        }
        if (unlockCostBox != null) {
            unlockCost = GuiText.parseInt(unlockCostBox.value(), unlockCost, 0, 100_000);
        }
    }

    /**
     * The unlock page's labels, its explanation, and the live validation of what was typed.
     *
     * <p>The validation runs the same checks the server's {@code LevelValidator} does on
     * load, so a misspelled prerequisite shows up while typing instead of as a level that
     * can never be entered.
     */
    private void renderUnlockPage(EditorContext context) {
        if (unlockLevelsBox == null) {
            return;
        }
        float labelScale = 0.78F;
        context.client().fonts().body().draw("前置关卡（需先通关；逗号分隔，留空表示不限）",
                unlockLevelsBox.x(), unlockLevelsBox.y() + unlockLevelsBox.height() + 5F,
                labelScale, 0.9F, 0.9F, 0.9F, 1F);
        context.client().fonts().body().draw("所需卡（需已解锁；同上）",
                unlockCardsBox.x(), unlockCardsBox.y() + unlockCardsBox.height() + 5F,
                labelScale, 0.9F, 0.9F, 0.9F, 1F);
        context.client().fonts().body().draw("金币解锁价（0 = 不可购买）",
                unlockCostBox.x(), unlockCostBox.y() + unlockCostBox.height() + 5F,
                labelScale, 0.9F, 0.9F, 0.9F, 1F);
        context.client().fonts().body().draw("隐藏关（未满足条件时不出现在列表里）",
                unlockHiddenButton.x(), unlockHiddenButton.y() + unlockHiddenButton.height() + 5F,
                labelScale, 0.9F, 0.9F, 0.9F, 1F);

        List<String> problems = new ArrayList<>();
        for (String id : splitIdList(unlockLevels)) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null || BuiltInRegistries.LEVELS.get(parsed) == null) {
                problems.add("未知关卡 " + id);
            } else if (parsed.equals(context.levelId())) {
                problems.add("本关不能作为自己的前置");
            }
        }
        for (String id : splitIdList(unlockCards)) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null || com.pvzce.common.core.SlotResolver.resolve(parsed).isEmpty()) {
                problems.add("未知卡 " + id);
            }
        }

        // One status line, in the gap between the price row and the card row: the only
        // horizontal space on this page not already spoken for by a label.
        List<String> picked = new ArrayList<>(splitIdList(unlockLevels));
        picked.addAll(splitIdList(unlockCards));
        float statusY = (unlockCostBox.y() + unlockCostBox.height() + unlockCardsBox.y()) / 2F;
        if (!problems.isEmpty()) {
            drawTrimmed(context, "有问题：" + String.join("；", problems), rightX(), statusY, 0.72F,
                    1F, 0.5F, 0.4F);
        } else if (picked.isEmpty() && unlockCost <= 0 && !unlockHidden) {
            context.client().fonts().body().draw("本关无条件：任何玩家都可以直接进入", rightX(), statusY, 0.72F,
                    0.7F, 0.9F, 0.7F, 1F);
        } else {
            String note = "条件生效：需解锁 " + picked.size() + " 项"
                    + (unlockCost > 0 ? "，或花 " + unlockCost + " 金币买下" : "")
                    + (unlockHidden ? "；未满足前不出现在列表里" : "");
            context.client().fonts().body().draw(note, rightX(), statusY, 0.72F, 0.85F, 0.9F, 0.6F, 1F);
        }
    }

    /** The x where the right column starts, read back from one of its widgets. */
    private float rightX() {
        return unlockCostBox == null ? 0F : unlockCostBox.x();
    }

    /**
     * Draws a message, shortening it until it fits the half-panel it lives in.
     *
     * <p>A prerequisite id is long; a list of them is longer than half a 1280px window,
     * and text drawn past the panel runs over the board instead of being clipped.
     */
    private void drawTrimmed(EditorContext context, String text, float x, float y, float scale,
                             float r, float g, float b) {
        String shown = text;
        float limit = Math.max(120F, context.client().guiWidth() / 2F - x - 20F);
        while (shown.length() > 12 && context.client().fonts().body().width(shown, scale) > limit) {
            shown = shown.substring(0, shown.length() - 4);
        }
        if (!shown.equals(text)) {
            shown = shown + "…";
        }
        context.client().fonts().body().draw(shown, x, y, scale, r, g, b, 1F);
    }

    /**
     * Reads the level's {@code unlock} block into the page's four fields.
     *
     * <p>A requirement the page cannot express (a coin threshold, or a type a later
     * version added) is kept in {@link #unlockExtra} and written back on save, so opening
     * a level in the editor never quietly drops a condition the author wrote by hand.
     */
    private void readUnlock(JsonObject sourceJson) {
        unlockLevels = "";
        unlockCards = "";
        unlockCost = 0;
        unlockHidden = false;
        unlockExtra = new JsonArray();
        if (!sourceJson.has("unlock") || !sourceJson.get("unlock").isJsonObject()) {
            return;
        }
        JsonObject unlock = sourceJson.getAsJsonObject("unlock");
        unlockHidden = unlock.has("hidden") && unlock.get("hidden").getAsBoolean();
        if (unlock.has("cost") && unlock.get("cost").isJsonPrimitive()) {
            unlockCost = Math.max(0, unlock.get("cost").getAsInt());
        }
        if (!unlock.has("requires") || !unlock.get("requires").isJsonArray()) {
            return;
        }
        List<String> levels = new ArrayList<>();
        List<String> cards = new ArrayList<>();
        for (JsonElement element : unlock.getAsJsonArray("requires")) {
            if (!element.isJsonObject()) {
                unlockExtra.add(element);
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String type = entry.has("type") ? entry.get("type").getAsString() : "";
            String id = entry.has("id") && entry.get("id").isJsonPrimitive()
                    ? entry.get("id").getAsString() : "";
            if ("level".equals(type) && !id.isEmpty()) {
                levels.add(id);
            } else if ("card".equals(type) && !id.isEmpty()) {
                cards.add(id);
            } else {
                // A coin threshold or an unknown type: preserved verbatim, not editable here.
                unlockExtra.add(element.deepCopy());
            }
        }
        unlockLevels = String.join(", ", levels);
        unlockCards = String.join(", ", cards);
    }

    /**
     * The {@code unlock} block for the level file, or an empty object when there is none.
     *
     * <p>Whole-block ownership like {@code rewards}: the page covers every field the
     * format has, and anything it could not represent is kept in {@code unlockExtra}.
     */
    private JsonObject unlockJson() {
        JsonArray requires = new JsonArray();
        for (String id : splitIdList(unlockLevels)) {
            requires.add(LevelFileWriter.requirementJson("level", id));
        }
        for (String id : splitIdList(unlockCards)) {
            requires.add(LevelFileWriter.requirementJson("card", id));
        }
        for (JsonElement extra : unlockExtra) {
            requires.add(extra.deepCopy());
        }
        JsonObject out = new JsonObject();
        out.add("requires", requires);
        if (unlockCost > 0) {
            out.addProperty("cost", unlockCost);
        }
        if (unlockHidden) {
            out.addProperty("hidden", true);
        }
        return out;
    }

    private static List<String> splitIdList(String value) {
        List<String> ids = new ArrayList<>();
        if (value == null) {
            return ids;
        }
        for (String part : value.split(",")) {
            String id = part.trim();
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

}
