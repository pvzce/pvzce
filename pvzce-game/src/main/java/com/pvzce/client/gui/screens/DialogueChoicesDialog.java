package com.pvzce.client.gui.screens;

import com.pvzce.api.content.DialogueChoice;
import com.pvzce.api.content.DialogueLine;
import com.pvzce.api.content.DialogueSlot;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.components.EditBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor dialog for a line's stage and its answers.
 *
 * <p>The 对话 page's form has four rows and they are all spoken for, so the two fields that arrived
 * with two-character conversations live one level down: a button on the line's animation row opens
 * this dialog for the selected line. It edits an ordinary {@link DialogueLine.DialogueSlotEntry}
 * list and an ordinary {@link DialogueChoice} list, which is what makes it worth having at all -
 * stage and choices are hand-written JSON without it, and the page may not delete what it cannot
 * put back.
 *
 * <p>Sized for what a conversation actually is rather than for what it could be: at most
 * {@link #MAX_CHOICES} answers and the two halves of the window, because a dialogue with more is
 * not a dialogue - the bubble is a caption and the buttons fit under it.
 */
public final class DialogueChoicesDialog extends Dialog {
    /** The most answers one question may offer; the bubble has room for these and no more. */
    public static final int MAX_CHOICES = 5;

    /** What the dialog is editing, as plain values: the page owns the model. */
    public static final class Config {
        public String stageLeft = "";
        public String stageRight = "";
        public String speakerName = "";
        public final List<String> choices = new ArrayList<>();

        /**
         * Reads a line's stage, its answers and the name over its bubble.
         *
         * <p>An id is written without its namespace when it is this project's own, which is how the
         * rest of the editor spells content the game ships: typing {@code entang} means
         * {@code pvzce:entang}.
         */
        public static Config from(DialogueLine line) {
            Config config = new Config();
            if (line == null) {
                return config;
            }
            for (DialogueLine.DialogueSlotEntry entry : line.slots()) {
                if (entry.slot() == DialogueSlot.RIGHT) {
                    config.stageRight = shortId(entry.character());
                } else if (entry.slot() == DialogueSlot.LEFT) {
                    config.stageLeft = shortId(entry.character());
                }
            }
            config.speakerName = line.speakerName() == null ? "" : line.speakerName();
            for (DialogueChoice choice : line.choices()) {
                config.choices.add(choice.text());
            }
            return config;
        }

        /** A character id as the dialog shows it: the namespace only when it is not this one. */
        private static String shortId(Identifier id) {
            if (id == null) {
                return "";
            }
            return Identifier.DEFAULT_NAMESPACE.equals(id.namespace()) ? id.path() : id.toString();
        }

        /** The entered id as an identifier, or null for an empty box or a misspelt one. */
        public Identifier leftCharacter() {
            return parse(stageLeft);
        }

        public Identifier rightCharacter() {
            return parse(stageRight);
        }

        /** Never fails: a name the parser cannot read is reported by the validator, not here. */
        private static Identifier parse(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String trimmed = text.trim();
            return Identifier.tryParse(trimmed.contains(":") ? trimmed
                    : Identifier.DEFAULT_NAMESPACE + ":" + trimmed);
        }
    }

    private final Config config;
    /** Where the edited values go when the dialog is confirmed; null when nothing is listening. */
    private java.util.function.Consumer<Config> onSave;
    private final List<EditBox> choiceBoxes = new ArrayList<>();
    private EditBox leftBox;
    private EditBox rightBox;
    private EditBox speakerBox;

    public DialogueChoicesDialog(int x, int y, int width, int height, Config config) {
        super(x, y, width, height, "同台与选项");
        this.config = config == null ? new Config() : config;
        rebuild();
    }

    /** Opens the dialog for one line, or does nothing when no line is selected. */
    public static void openFor(com.pvzce.client.gui.editor.EditorContext context, DialogueLine line,
                               java.util.function.Consumer<Config> onSave) {
        if (line == null) {
            context.setStatus("先在左边选一条台词");
            return;
        }
        PvzceClient client = context.client();
        int width = Math.min(460, client.guiWidth() - 24);
        int height = Math.min(360, client.guiHeight() - 24);
        DialogueChoicesDialog dialog = new DialogueChoicesDialog((client.guiWidth() - width) / 2,
                (client.guiHeight() - height) / 2, width, height, Config.from(line));
        dialog.onSave = onSave;
        context.openDialog(dialog);
    }

    /** What the boxes currently say, read when the dialog closes. */
    public Config config() {
        commitFields();
        return config;
    }

    private void commitFields() {
        config.stageLeft = leftBox == null ? config.stageLeft : leftBox.value();
        config.stageRight = rightBox == null ? config.stageRight : rightBox.value();
        config.speakerName = speakerBox == null ? config.speakerName : speakerBox.value();
        for (int i = 0; i < choiceBoxes.size() && i < config.choices.size(); i++) {
            config.choices.set(i, choiceBoxes.get(i).value());
        }
    }

    @Override
    public void close() {
        commitFields();
        if (onSave != null) {
            // Confirming and dismissing are the same gesture here: the boxes are the dialog's whole
            // state, and a form whose every field is visible has nothing to cancel.
            onSave.accept(config);
        }
        super.close();
    }

    /** Rebuilds the whole form: the answer list changes shape as boxes are added and removed. */
    private void rebuild() {
        clearChildren();
        choiceBoxes.clear();
        int pad = 14;
        int rowH = 28;
        int rowGap = 12;
        int fieldW = Math.max(120, width - pad * 2 - 96);

        int top = y + height - 44;
        leftBox = new EditBox(x + pad + 96, top, fieldW, rowH, () -> {
        });
        leftBox.setValue(config.stageLeft, false);
        addChild(leftBox);
        rightBox = new EditBox(x + pad + 96, top - rowGap - rowH, fieldW, rowH, () -> {
        });
        rightBox.setValue(config.stageRight, false);
        addChild(rightBox);
        speakerBox = new EditBox(x + pad + 96, top - (rowGap + rowH) * 2, fieldW, rowH, () -> {
        });
        speakerBox.setValue(config.speakerName, false);
        addChild(speakerBox);

        int choicesTop = top - (rowGap + rowH) * 2 - rowGap - 10;
        int rows = Math.min(config.choices.size(), MAX_CHOICES);
        for (int i = 0; i < rows; i++) {
            EditBox box = new EditBox(x + pad + 96, choicesTop - (rowH + 6) * (i + 1), fieldW, rowH, () -> {
            });
            box.setValue(config.choices.get(i), false);
            choiceBoxes.add(box);
            addChild(box);
        }

        int buttonW = Math.max(26, (fieldW - 6) / 2);
        int buttonsY = choicesTop - (rowH + 6) * rows - rowH - 6;
        Button add = new Button(x + pad + 96, buttonsY, buttonW, rowH, "加一条选项", this::addChoice);
        add.setActive(config.choices.size() < MAX_CHOICES);
        addChild(add);
        addChild(new Button(x + pad + 96 + buttonW + 6, buttonsY, buttonW, rowH, "删最后一条",
                this::removeChoice));
        addChild(new Button(x + width - pad - 96, y + 10, 96, rowH, "确定", this::close));
    }

    private void addChoice() {
        if (config.choices.size() >= MAX_CHOICES) {
            return;
        }
        commitFields();
        config.choices.add("");
        rebuild();
    }

    private void removeChoice() {
        if (config.choices.isEmpty()) {
            return;
        }
        commitFields();
        config.choices.remove(config.choices.size() - 1);
        rebuild();
    }

    /** The boxes are the state: committed every frame, so a sentence is never lost to a close. */
    public void tick(PvzceClient client) {
        if (visible) {
            commitFields();
        }
    }

    @Override
    public void render(PvzceClient client) {
        super.render(client);
        if (!visible) {
            return;
        }
        float scale = 0.76F;
        int labelX = x + 14;
        int top = y + height - 44;
        int rowH = 28;
        int rowGap = 12;
        client.fonts().body().draw("左边立绘（角色 id，留空 = 没人）", labelX,
                top + rowH - 12, scale, 1F, 0.9F, 0.6F, 1F);
        client.fonts().body().draw("右边立绘（上一个是说话人）", labelX,
                top - rowGap + rowH - 12, scale, 1F, 0.9F, 0.6F, 1F);
        client.fonts().body().draw("气泡上的名字（玩家用 ${user_name}）", labelX,
                top - (rowGap + rowH) * 2 + rowH - 12, scale, 1F, 0.9F, 0.6F, 1F);
        if (!config.choices.isEmpty()) {
            client.fonts().body().draw("选项：有选项的台词要玩家点按钮才能往下走", labelX,
                    top - (rowGap + rowH) * 2 - rowGap - 2, scale, 1F, 0.9F, 0.6F, 1F);
        }
    }
}
