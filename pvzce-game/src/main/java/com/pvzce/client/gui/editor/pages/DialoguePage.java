package com.pvzce.client.gui.editor.pages;

import com.pvzce.api.content.DialogueCharacterDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.LevelFileWriter;
import com.pvzce.client.gui.screens.DialogueEditorModel;
import com.pvzce.client.gui.screens.ListEditorSupport;
import com.pvzce.common.core.BuiltInRegistries;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The 对话 page: the conversation a level opens with.
 *
 * <p>Two columns, like the wave and music pages: the lines on the left in the order they
 * play, and the selected line's five fields on the right. The character and the portrait are
 * picked from lists rather than typed - a character id is a namespaced path and a portrait is
 * a file name inside that character's folder, and both are things the author would otherwise
 * have to look up in the pack.
 *
 * <p>The text is a plain field. Typing Chinese into it depends on the platform's input method
 * reaching GLFW (the editors have no IME of their own), so a level authored through a text
 * editor keeps working exactly the same way - the page reads and writes the {@code dialogue}
 * block either way.
 *
 * <p>The page owns its slice of the level file like every other page: {@link #readFrom} loads
 * {@code dialogue} into its model and {@link #writeTo} hands that model to
 * {@link LevelFileWriter#dialogue}, so a level whose dialogue page was never opened still
 * keeps its conversation.
 */
public final class DialoguePage implements EditorPage {
    private final DialogueEditorModel.Config dialogueConfig = new DialogueEditorModel.Config();
    private AbstractSelectionList<DialogueEditorModel.LineModel> dialogueLineList;
    private AbstractSelectionList<Identifier> dialogueCharacterList;
    private AbstractSelectionList<String> dialoguePortraitList;
    private EditBox dialogueTextBox;
    private EditBox dialogueVoiceBox;
    private Button dialogueSideButton;
    /** The line the detail form was last built for; the list has no change event. */
    private DialogueEditorModel.LineModel lastDialogueLineShown;
    /** The picker values that came from the model rather than from a click. */
    private Identifier lastDialogueCharacterChoice;
    private String lastDialoguePortraitChoice;

    @Override
    public String id() {
        return "dialogue";
    }

    @Override
    public String label() {
        return GuiLang.raw("pvzce.editor.page.dialogue", "dialogue");
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public boolean hasPalette() {
        return false;
    }

    /** The {@code dialogue} block is the page's whole state; the widgets are rebuilt from it. */
    @Override
    public void readFrom(EditorContext context) {
        dialogueConfig.replaceWith(DialogueEditorModel.Config.fromJson(context.draft().json()));
    }

    /**
     * Commits the boxes first: the line's text and voice are the model's to save, and the
     * 保存 button is handled before this frame's tick(), so the sentence just typed would
     * otherwise be lost to the save.
     */
    @Override
    public void writeTo(EditorContext context) {
        commitDialogueFields(currentDialogueLine());
        LevelFileWriter.dialogue(context.draft(), dialogueConfig);
    }

    @Override
    public void build(EditorContext context) {
        // The widgets are new, so the line the detail form was last built for is forgotten
        // with them: keeping it would commit the fresh selection's text into the old line.
        lastDialogueLineShown = null;
        EditorContext.Rect area = context.fullContent();
        int x = area.x();
        int y = area.y();
        int w = area.width();
        int h = area.height();
        int rowH = clamp(h / 16, 26, 34);
        int pad = 8;
        int gap = 10;

        int listW = (int) (w * 0.52F);
        int detailX = x + listW + gap;
        int detailW = w - listW - gap;

        int listTop = y + h - rowH - pad - 20;
        int listH = Math.max(60, listTop - (y + rowH + pad + 20));
        dialogueLineList = context.own(new AbstractSelectionList<DialogueEditorModel.LineModel>(
                x, y + rowH + pad + 20, listW, listH, clamp(listH / 7, 28, 42),
                (renderClient, line, rx, ry) -> renderClient.font().draw(
                        line.summary(Math.max(0, dialogueConfig.lines.indexOf(line)),
                                dialogueSpeakerName(line.character)),
                        rx, ry + 4, 0.72F, 1F, 1F, 1F, 1F)));
        dialogueLineList.setEntries(new ArrayList<>(dialogueConfig.lines));

        int bw = Math.max(70, listW / 5 - 6);
        int by = y + pad;
        context.own(new Button(x, by, bw, rowH, "新增台词", () -> addDialogueLine(context)));
        context.own(new Button(x + bw + 5, by, bw, rowH, "删除", () -> removeDialogueLine(context)));
        context.own(new Button(x + (bw + 5) * 2, by, bw, rowH, "上移",
                () -> moveDialogueLine(context, -1)));
        context.own(new Button(x + (bw + 5) * 3, by, bw, rowH, "下移",
                () -> moveDialogueLine(context, 1)));
        context.own(new Button(x + (bw + 5) * 4, by, Math.max(70, listW - (bw + 5) * 4), rowH,
                "清空对话", () -> clearDialogue(context)));

        // Right column, bottom-up: the line's own fields, then the two pickers above them.
        // Each row is a field plus the room for its label, which is drawn in the gap above
        // it - packing the rows at rowH+6 put every label on top of the field before it.
        int fieldRow = rowH + 16;
        int fieldTop = y + pad + fieldRow * 2;
        dialogueTextBox = context.own(new EditBox(detailX, fieldTop, detailW, rowH,
                this::commitDialogueFieldsNow));
        dialogueVoiceBox = context.own(new EditBox(detailX, fieldTop - fieldRow, detailW, rowH,
                this::commitDialogueFieldsNow));
        dialogueSideButton = context.own(new Button(detailX, fieldTop - fieldRow * 2, detailW, rowH,
                "位置：左", () -> toggleDialogueSide(context)));

        int listsBottom = y + pad + fieldRow * 3 + 18;
        int listBlockH = Math.max(70, (y + h - pad - 18) - listsBottom);
        int halfW = Math.max(70, (detailW - gap) / 2);
        dialogueCharacterList = context.own(new AbstractSelectionList<Identifier>(detailX, listsBottom,
                halfW, listBlockH, clamp(listBlockH / 6, 26, 36), (renderClient, id, rx, ry) -> {
            var character = BuiltInRegistries.DIALOGUE_CHARACTERS.get(id);
            String label = character == null ? id.toString() : character.displayName();
            renderClient.font().draw(label, rx, ry + 4, 0.72F, 1F, 1F, 1F, 1F);
            renderClient.font().draw(GuiText.shortId(id), rx, ry - 10, 0.62F, 0.75F, 0.8F, 0.8F, 1F);
        }));
        dialogueCharacterList.setEntries(new ArrayList<>(
                BuiltInRegistries.DIALOGUE_CHARACTERS.keySet().stream().sorted().toList()));

        dialoguePortraitList = context.own(new AbstractSelectionList<String>(
                detailX + halfW + gap, listsBottom, detailW - halfW - gap, listBlockH,
                clamp(listBlockH / 6, 26, 36), (renderClient, portrait, rx, ry) ->
                renderClient.font().draw(portrait, rx, ry + 4, 0.72F, 0.9F, 1F, 0.9F, 1F)));
        dialoguePortraitList.setEntries(List.of());

        refreshDialogueDetail(context);
    }

    @Override
    public void render(EditorContext context) {
        EditorContext.Rect area = context.fullContent();
        PvzceClient renderClient = context.client();
        renderClient.font().draw("对话 " + dialogueConfig.lines.size()
                        + " 条　关卡开始时播放：点击推进，ESC 跳过整段",
                area.x() + 4, area.y() + area.height() + 4F, 0.74F, 1F, 1F, 1F, 1F);
        if (dialogueTextBox != null) {
            float labelScale = 0.68F;
            renderClient.font().draw("台词", dialogueTextBox.x(),
                    dialogueTextBox.y() + dialogueTextBox.height() + 4F, labelScale,
                    1F, 0.9F, 0.6F, 1F);
            renderClient.font().draw("语音（sound event id，可留空）", dialogueVoiceBox.x(),
                    dialogueVoiceBox.y() + dialogueVoiceBox.height() + 4F, labelScale,
                    0.9F, 0.9F, 0.9F, 1F);
            renderClient.font().draw("台词行（顺序即播放顺序）", area.x() + 4,
                    dialogueLineList.y() + dialogueLineList.height() + 4F, 0.7F,
                    1F, 0.9F, 0.6F, 1F);
            renderClient.font().draw("角色", dialogueCharacterList.x(),
                    dialogueCharacterList.y() + dialogueCharacterList.height() + 4F,
                    0.7F, 1F, 0.9F, 0.6F, 1F);
            renderClient.font().draw("立绘（该角色目录下的 PNG）", dialoguePortraitList.x(),
                    dialoguePortraitList.y() + dialoguePortraitList.height() + 4F,
                    0.7F, 1F, 0.9F, 0.6F, 1F);
        }
        if (dialogueConfig.lines.isEmpty()) {
            renderClient.font().draw("这一关没有开场对话：点「新增台词」开始写",
                    area.x() + 4, area.y() + area.height() / 2F, 0.85F, 1F, 0.85F, 0.5F, 1F);
        }
    }

    @Override
    public void tick(EditorContext context) {
        if (dialogueLineList != null) {
            DialogueEditorModel.LineModel selected = dialogueLineList.selected();
            if (selected != lastDialogueLineShown) {
                // The boxes still hold the outgoing line's text, so it is committed to
                // that line before the form is rebuilt for the new one.
                commitDialogueFields(lastDialogueLineShown);
                lastDialogueLineShown = selected;
                refreshDialogueDetail(context);
            }
        }
        // The boxes belong to the line they are showing, and the model is what a save
        // reads: committing every frame is what keeps a typed sentence from being lost
        // to the next click.
        commitDialogueFields(currentDialogueLine());
        applyDialogueCharacterChoice(context);
        applyDialoguePortraitChoice();
    }

    /** A character id's display name, or the id itself when it is not registered. */
    private static String dialogueSpeakerName(String characterId) {
        Identifier id = Identifier.tryParse(characterId);
        var character = id == null ? null : BuiltInRegistries.DIALOGUE_CHARACTERS.get(id);
        return character == null ? characterId : character.displayName();
    }

    /** The line the detail form is showing, or {@code null} when the level has none. */
    private DialogueEditorModel.LineModel currentDialogueLine() {
        return dialogueLineList == null ? null : dialogueLineList.selected();
    }

    /** The portrait file names inside a character's portrait directory, sorted. */
    private List<String> portraitsFor(EditorContext context, String characterId) {
        Identifier id = Identifier.tryParse(characterId);
        DialogueCharacterDef character =
                id == null ? null : BuiltInRegistries.DIALOGUE_CHARACTERS.get(id);
        if (character == null) {
            return List.of();
        }
        Identifier dir = character.resolvedPortraitDir();
        String prefix = "assets/" + dir.toPath() + "/";
        try {
            return context.client().resources().listResources(prefix).keySet().stream()
                    .filter(path -> path.startsWith(prefix) && path.endsWith(".png"))
                    .map(path -> path.substring(prefix.length(), path.length() - ".png".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Rebuilds the detail form from the selected line.
     *
     * <p>A focused field keeps what the author is typing: the model is copied into the
     * boxes only for the fields that are not being edited.
     */
    private void refreshDialogueDetail(EditorContext context) {
        DialogueEditorModel.LineModel line = currentDialogueLine();
        boolean has = line != null;
        if (dialogueTextBox == null) {
            return;
        }
        dialogueTextBox.setActive(has);
        dialogueVoiceBox.setActive(has);
        dialogueSideButton.setActive(has);
        if (!has) {
            dialogueTextBox.setValue("", false);
            dialogueVoiceBox.setValue("", false);
            dialogueSideButton.setLabel("位置：-");
            if (dialoguePortraitList != null) {
                dialoguePortraitList.setEntries(List.of());
            }
            return;
        }
        if (!dialogueTextBox.isFocused()) {
            dialogueTextBox.setValue(line.text, false);
        }
        if (!dialogueVoiceBox.isFocused()) {
            dialogueVoiceBox.setValue(line.voice, false);
        }
        dialogueSideButton.setLabel(line.left ? "位置：左" : "位置：右");

        // The pickers follow the line rather than the other way around; the "last choice"
        // marks are what stop that sync from being read back as a user choice, which
        // would silently rewrite a hand-written id the lists do not contain.
        if (dialogueCharacterList != null) {
            Identifier wanted = Identifier.tryParse(line.character);
            int index = wanted == null ? -1 : dialogueCharacterList.entries().indexOf(wanted);
            if (index >= 0) {
                dialogueCharacterList.select(index);
            }
            lastDialogueCharacterChoice = dialogueCharacterList.selected();
        }
        refreshDialoguePortraits(context);
        if (dialoguePortraitList != null) {
            int index = dialoguePortraitList.entries().indexOf(line.portrait);
            dialoguePortraitList.select(index >= 0 ? index : 0);
            lastDialoguePortraitChoice = dialoguePortraitList.selected();
        }
    }

    /** Rebuilds the portrait list for the selected line's character. */
    private void refreshDialoguePortraits(EditorContext context) {
        if (dialoguePortraitList == null) {
            return;
        }
        DialogueEditorModel.LineModel line = currentDialogueLine();
        List<String> portraits = line == null ? List.of() : portraitsFor(context, line.character);
        dialoguePortraitList.setEntries(new ArrayList<>(portraits));
        if (line != null && !portraits.isEmpty()) {
            dialoguePortraitList.select(0);
            lastDialoguePortraitChoice = portraits.get(0);
        }
    }

    /**
     * Copies the boxes into the line they are showing.
     *
     * <p>Committed every frame rather than on Enter: a text field whose value only
     * reaches the model when the author remembers to press a key loses the sentence they
     * just typed the moment they click another line or press 保存.
     */
    private void commitDialogueFields(DialogueEditorModel.LineModel line) {
        if (line == null || dialogueTextBox == null) {
            return;
        }
        line.text = dialogueTextBox.value();
        line.voice = dialogueVoiceBox.value().trim();
    }

    private void commitDialogueFieldsNow() {
        commitDialogueFields(currentDialogueLine());
        refreshDialogueLineList();
    }

    private void toggleDialogueSide(EditorContext context) {
        DialogueEditorModel.LineModel line = currentDialogueLine();
        if (line == null) {
            return;
        }
        line.left = !line.left;
        refreshDialogueDetail(context);
        refreshDialogueLineList();
    }

    private void applyDialogueCharacterChoice(EditorContext context) {
        if (dialogueCharacterList == null) {
            return;
        }
        Identifier chosen = dialogueCharacterList.selected();
        DialogueEditorModel.LineModel line = currentDialogueLine();
        if (line == null || chosen == null || chosen.equals(lastDialogueCharacterChoice)) {
            return;
        }
        lastDialogueCharacterChoice = chosen;
        line.character = chosen.toString();
        // The old portrait belongs to the old character's folder; the first file of the
        // new one is the only choice that is certainly available.
        List<String> portraits = portraitsFor(context, line.character);
        line.portrait = portraits.isEmpty() ? "" : portraits.get(0);
        refreshDialoguePortraits(context);
        refreshDialogueLineList();
    }

    private void applyDialoguePortraitChoice() {
        if (dialoguePortraitList == null) {
            return;
        }
        String chosen = dialoguePortraitList.selected();
        DialogueEditorModel.LineModel line = currentDialogueLine();
        if (line == null || chosen == null || chosen.equals(lastDialoguePortraitChoice)) {
            return;
        }
        lastDialoguePortraitChoice = chosen;
        line.portrait = chosen;
        refreshDialogueLineList();
    }

    private void refreshDialogueLineList() {
        if (dialogueLineList != null) {
            ListEditorSupport.refresh(dialogueLineList, new ArrayList<>(dialogueConfig.lines),
                    currentDialogueLine());
        }
    }

    private void addDialogueLine(EditorContext context) {
        DialogueEditorModel.LineModel line = new DialogueEditorModel.LineModel();
        // The first registered character is the sensible default: a line with no character
        // has no portrait and no name, which is exactly what the author is about to fix.
        line.character = BuiltInRegistries.DIALOGUE_CHARACTERS.keySet().stream()
                .sorted().findFirst().map(Identifier::toString).orElse("");
        List<String> portraits = portraitsFor(context, line.character);
        line.portrait = portraits.isEmpty() ? "" : portraits.get(0);
        dialogueConfig.lines.add(line);
        refreshDialogueLineList();
        dialogueLineList.select(dialogueConfig.lines.size() - 1);
        lastDialogueLineShown = line;
        refreshDialogueDetail(context);
    }

    private void removeDialogueLine(EditorContext context) {
        DialogueEditorModel.LineModel line = currentDialogueLine();
        if (line == null) {
            return;
        }
        DialogueEditorModel.LineModel next = ListEditorSupport.remove(dialogueConfig.lines, line);
        ListEditorSupport.refresh(dialogueLineList, new ArrayList<>(dialogueConfig.lines), next);
        lastDialogueLineShown = currentDialogueLine();
        refreshDialogueDetail(context);
    }

    private void moveDialogueLine(EditorContext context, int delta) {
        DialogueEditorModel.LineModel line = currentDialogueLine();
        if (ListEditorSupport.move(dialogueConfig.lines, line, delta)) {
            refreshDialogueLineList();
            refreshDialogueDetail(context);
        }
    }

    private void clearDialogue(EditorContext context) {
        dialogueConfig.lines.clear();
        refreshDialogueLineList();
        lastDialogueLineShown = null;
        refreshDialogueDetail(context);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
