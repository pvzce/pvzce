package com.pvzce.client.gui.components;

import com.pvzce.api.content.DialogueAnimation;
import com.pvzce.api.content.DialogueCharacterDef;
import com.pvzce.api.content.DialogueChoice;
import com.pvzce.api.content.DialogueEffect;
import com.pvzce.api.content.DialogueLine;
import com.pvzce.api.content.DialogueSlot;
import com.pvzce.api.content.LevelDialogue;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.renderer.texture.Texture;
import com.pvzce.common.core.BuiltInRegistries;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A level's opening conversation: the characters on stage, a speech bubble beside whoever is
 * speaking, one line at a time, advanced by a click.
 *
 * <p>It is a {@link Dialog} rather than a screen because that is this project's one mechanism for
 * "this thing owns the input": {@code Screen} routes every mouse, key, scroll and char event to the
 * topmost visible modal dialog first, so a screen hosting a dialogue cannot also react to the clicks
 * that advance it. The frame and backdrop a dialog normally draws are overridden away - the scene
 * behind a dialogue is the point, not something to dim.
 *
 * <p>Both places a game can start use it: the seed chooser shows it before the camera pans and the
 * card panel slides in, and a level entered directly (a conveyor level, which has no chooser) shows
 * it over the lawn with the level paused.
 *
 * <p>Nothing here decides <em>whether</em> a dialogue plays, who is on stage, or what a click means:
 * that is {@link DialogueScript}, which can be tested without a window. This class is what is left -
 * textures, layout, and the two halves of a click.
 */
public final class DialogueOverlay extends Dialog {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Dialogue");

    /**
     * How tall a portrait stands, as a fraction of the window, before {@code scale}.
     *
     * <p>Nearly the whole height on purpose: a portrait is the character's presence in the scene,
     * and at two thirds of the window it read as a sticker next to the lawn rather than as someone
     * standing in the yard. The bubble then takes whatever width is left beside it instead of the
     * portrait being shrunk to make room.
     *
     * <p>Two characters on stage share that height rather than each taking it: the boxes stand in
     * the two halves of the window and would overlap if both were full height, so
     * {@link DialogueMotion#portraitHeight} cuts them down until the pair fits side by side. The
     * visible art inside a portrait frame is narrower than the frame - pea_chan's figure is 46% of
     * her texture's width - so what the reader sees is two characters standing apart, not two
     * frames colliding in the middle.
     */
    private static final float PORTRAIT_HEIGHT_RATIO = 0.90F;
    /** Gap between a portrait and its window edge. */
    private static final float PORTRAIT_MARGIN_RATIO = 0.008F;
    /** The bubble is sized to its text, within these fractions of the window width. */
    private static final float BUBBLE_MAX_WIDTH_RATIO = 0.46F;
    private static final float BUBBLE_MIN_WIDTH_RATIO = 0.20F;
    /**
     * Where the bubble's corner sits across the visible art: 0.62 = 62% of the way in from the edge
     * the bubble is on.
     *
     * <p>A portrait is a figure with air around it, and the bubble belongs beside the face rather
     * than out where the character's shoulder ends. It used to be measured from the texture's own
     * edge, which was the same number only because that art happened to end before it - see
     * {@code DialogueCharacterDef.PortraitInsets}, which is what makes the visible edge the thing
     * this fraction is applied to.
     */
    private static final float BUBBLE_PORTRAIT_ANCHOR_RATIO = 0.62F;
    /** Gap between the portrait's visible edge and the bubble beside it. */
    private static final float BUBBLE_GAP_RATIO = 0.008F;
    private static final float BUBBLE_SIDE_MARGIN_RATIO = 0.02F;
    /**
     * Where the bubble's bottom edge wants to sit, as a fraction of the window height.
     *
     * <p>Roughly at the speaker's chin: the tail hangs from the bubble's bottom corner, so this is
     * what makes it point at the character rather than at the grass.
     */
    private static final float BUBBLE_BOTTOM_RATIO = 0.70F;
    /** Smallest gap left between the bubble and the window's top edge. */
    private static final float SCREEN_MARGIN_RATIO = 0.02F;

    /**
     * The authored size of a speech bubble ({@code textures/gui/dialogue/box_left.png}).
     *
     * <p>The bubble's tail is part of the bottom-left (or bottom-right) corner slice, so that inset
     * has to be wide and tall enough to contain it whole: the tail spans x 29..54 and the bottom 23
     * rows of the 280x183 image. Anything narrower would stretch the tail sideways, anything
     * shorter would cut it off.
     */
    private static final float BOX_NATIVE_WIDTH = 280F;
    private static final float BOX_NATIVE_HEIGHT = 183F;
    private static final float BOX_INSET_LEFT = 56F;
    private static final float BOX_INSET_RIGHT = 56F;
    private static final float BOX_INSET_TOP = 24F;
    private static final float BOX_INSET_BOTTOM = 24F;

    /** Text insets inside the bubble, in the bubble's own pixels. */
    private static final float TEXT_INSET_LEFT = 26F;
    private static final float TEXT_INSET_RIGHT = 26F;
    private static final float TEXT_INSET_TOP = 18F;
    /** The bubble's body ends 23px above the image's bottom; the text stays clear of it. */
    private static final float TEXT_INSET_BOTTOM = 26F;
    /** The character's name is drawn at this fraction of the line text's size. */
    private static final float NAME_SCALE = 0.78F;
    /** The "click to continue" hint, relative to the line text's size. */
    private static final float HINT_SCALE = 0.68F;
    private static final String HINT_KEY = "pvzce.dialogue.click_continue";
    private static final String HINT_FALLBACK = "点击继续";

    private static final float HINT_COLOR_R = 0.42F;
    private static final float HINT_COLOR_G = 0.45F;
    private static final float HINT_COLOR_B = 0.48F;

    /**
     * Typewriter speed: characters revealed per second.
     *
     * <p>Slow enough to read along with, fast enough that a two-line reply is not a wait: a
     * 16-character Chinese line takes about half a second. Punctuation adds the pauses in
     * {@link #pauseAfter}, which is what makes it read as speech instead of a teleprinter.
     */
    private static final float TYPEWRITER_CHARS_PER_SECOND = 28F;
    private static final float PAUSE_AFTER_SENTENCE = 0.18F;
    private static final float PAUSE_AFTER_CLAUSE = 0.09F;

    private static final float TEXT_COLOR_R = 0.11F;
    private static final float TEXT_COLOR_G = 0.12F;
    private static final float TEXT_COLOR_B = 0.14F;
    private static final float NAME_COLOR_R = 0.24F;
    private static final float NAME_COLOR_G = 0.42F;
    private static final float NAME_COLOR_B = 0.20F;

    /**
     * How much of the window the answers span, and how tall each one is.
     *
     * <p>They are laid out in screen units, not the bubble's: the answers stand beside the character
     * rather than inside anything that grows with a sentence, so a one-word question and a long one
     * put the same buttons in the same place. The block is a fraction of the window's width and the
     * heights are clamped, so it stays a row of buttons on a small window and does not become a wall
     * on a large one.
     */
    private static final float CHOICE_BLOCK_WIDTH_RATIO = 0.34F;
    private static final float CHOICE_SIDE_MARGIN_RATIO = 0.03F;
    private static final float CHOICE_HEIGHT_RATIO = 0.075F;
    private static final float CHOICE_MIN_HEIGHT = 30F;
    private static final float CHOICE_MAX_HEIGHT = 56F;
    /** Gap between two answers, as a fraction of one answer's height. */
    private static final float CHOICE_GAP_RATIO = 0.22F;
    /**
     * How wide one answer is relative to its height when deciding how many fit in a row.
     *
     * <p>A button wants to be a good deal wider than its label, and the labels here are two to four
     * characters: this is the width one answer is worth when the layout asks "how many fit".
     */
    private static final float CHOICE_ASPECT_RATIO = 2.6F;
    /**
     * How far off the window's bottom edge the answers sit, as a fraction of its height.
     *
     * <p>In the window's lower third without being on the floor: the bubble's band starts at 0.70 and
     * grows upwards, so this clears it; the HUD's hint line and level meter live under 0.19, so this
     * clears those too; and a one-row set of answers ends up around 0.30-0.42, which is where a
     * player looking away from the character's face finds them. The first version sat at 0.235 and
     * read as part of the HUD strip along the bottom.
     */
    private static final float CHOICE_BOTTOM_RATIO = 0.40F;

    /**
     * How long after a line appears its answers ignore the mouse.
     *
     * <p>Both the question and its buttons arrive with the last character of the line - the click
     * that finished the typing is the same click, and the player's finger is still down. Without
     * this the press that completed the question would be re-read against the button that had just
     * appeared under the cursor, and answering would take a second, blind click. It is also the
     * delay a slip of the finger needs: the answers are worth a deliberate press.
     */
    private static final long CHOICE_GRACE_NANOS = 150_000_000L;

    private final PvzceClient client;
    private final DialogueScript script;
    private final boolean enterSlides;
    /**
     * The player's name, read once when the conversation is built.
     *
     * <p>One value for one conversation: the script substitutes it into the lines' names and the
     * overlay into the lines' texts, and both have to see the same string or a line and its speaker
     * could disagree about who is talking.
     */
    private final String playerName;
    private Runnable onFinish;
    /** When the current line started typing; the reveal is derived from wall time. */
    private long lineStartNanos;
    /** True once the line is fully on screen, either by typing out or by a click. */
    private boolean lineComplete;
    /** When the current line appeared; its answers ignore the mouse for a moment after. */
    private long lineShownNanos;
    /** The portrait size the current line animates from and to. */
    private float scaleFrom = 1F;
    private float scaleTo = 1F;
    /** The answers as the last drawn frame laid them out, so a click hits what the player sees. */
    private List<ChoiceButton> laidOutChoices = List.of();
    /** The answer button the pointer is pressing, or -1: a button fires on release. */
    private int pressedChoice = -1;
    /** Where the pointer is, in GUI units; x is negative until the platform reports a move. */
    private double hoverX = -1D;
    private double hoverY = -1D;
    private boolean finished;

    /** One answer button as it was laid out this frame: where it is, and what it says. */
    private record ChoiceButton(float x, float y, float width, float height, String label) {
    }

    private DialogueOverlay(PvzceClient client, DialogueScript script, DialogueEffect enter,
                            String playerName) {
        super(0, 0, 0, 0, "");
        this.client = client;
        this.script = script;
        this.enterSlides = enter.slides();
        this.playerName = playerName == null ? "" : playerName;
        closeOnEscape(false);
        lineStartNanos = script.startNanos();
        lineShownNanos = lineStartNanos;
        scaleTo = script.line().animation().targetScale();
        scaleFrom = 1F;
        playVoice(script.voice());
    }

    /**
     * Builds the overlay for a conversation, resolving the characters it names.
     *
     * <p>Returns {@code null} when there is nothing to say, so a host screen can hold {@code null}
     * to mean "no dialogue" instead of carrying an empty overlay around and asking it whether it is
     * empty on every path.
     */
    public static DialogueOverlay create(PvzceClient client, LevelDialogue dialogue, Runnable onFinish) {
        if (dialogue == null || dialogue.isEmpty()) {
            return null;
        }
        String playerName = client == null || client.currentWorld() == null ? "" : client.currentWorld();
        DialogueOverlay overlay = new DialogueOverlay(client,
                DialogueScript.of(dialogue, playerName), dialogue.enter(), playerName);
        overlay.onFinish = onFinish;
        return overlay;
    }

    /**
     * The placeholder a script writes when the character is talking to the player.
     *
     * <p>{@code ${user_name}} is the name the player picked at the title screen, which is also the
     * world they are playing in (see {@code TitleScreen}). It is substituted when the overlay is
     * built rather than when the line is drawn, so one line has one final text: the typewriter, the
     * wrapping and the "click to continue" hint all measure the same string, and a name that
     * arrives later (a world change) cannot make a half-typed line change length under the player's
     * eyes.
     *
     * <p>Written with the shell's spelling because that is what it is - a value spliced into a
     * string the author wrote - and it is one more thing {@code LevelValidator} does not have to
     * know about: an unknown placeholder is simply not one of these, so it is shown as typed and
     * the author sees the literal text they wrote. A line's {@code speaker_name} takes it too, so
     * the player's own answer carries the name they chose.
     */
    public static final String USER_NAME_PLACEHOLDER = "${user_name}";

    /** Replaces {@link #USER_NAME_PLACEHOLDER} with the current player's name. */
    static String substituteUserName(PvzceClient client, String text) {
        if (text == null || !text.contains(USER_NAME_PLACEHOLDER)) {
            return text;
        }
        String name = client == null ? null : client.currentWorld();
        return text.replace(USER_NAME_PLACEHOLDER, name == null ? "" : name);
    }

    /** True while the player still has lines to click through. */
    public boolean isActive() {
        return !finished && script.isActive();
    }

    /**
     * One click: finish typing the line, answer nothing, or move on.
     *
     * <p>Two stages, because the line is being typed: while it is still coming out, a click means "I
     * have read enough, show me the rest"; once it is all there, a click means "next". A single stage
     * would skip text the player never saw, and making them wait for a line they already finished
     * reading is the other way to get this wrong.
     *
     * <p>A line with choices is a question, and a question is not answered by clicking anywhere: the
     * click does nothing and the buttons are the only way on (see {@link #mouseClicked}).
     *
     * <p>On the last line the click ends the conversation, which is where the closing slides go: the
     * portraits leave the screen first and {@code onFinish} runs when they are gone. Clicks during
     * that beat do nothing - there is nothing left to advance.
     */
    public void advance() {
        if (!isActive() || script.isExiting()) {
            return;
        }
        if (script.awaitingChoice()) {
            return;
        }
        if (!lineComplete
                && revealedCharacters(System.nanoTime()) < visibleLength(script.line().text())) {
            lineComplete = true;
            return;
        }
        step();
    }

    /** Steps the script forward and re-arms the typewriter, the sizes and the voice. */
    private void step() {
        if (script.advance()) {
            armCurrentLine();
        }
    }

    /** Answers the question the current line asks; the reply is spoken in the same click. */
    private void choose(int index) {
        if (script.choose(index)) {
            armCurrentLine();
        }
    }

    /**
     * Arms everything the current line owns: its typing clock, its animation, its voice.
     *
     * <p>The size is interpolated from whatever the previous line left behind, so two lines that ask
     * for different sizes grow and shrink instead of snapping; a line with no animation returns the
     * portrait to its layout size the same way.
     */
    private void armCurrentLine() {
        long now = System.nanoTime();
        lineComplete = false;
        lineStartNanos = now;
        lineShownNanos = now;
        scaleFrom = currentScale(now);
        scaleTo = script.line().animation().targetScale();
        // The answers are re-laid-out by the next frame; until then the old frame's buttons must
        // not be clickable, or a click could land on an answer that is no longer on screen.
        laidOutChoices = List.of();
        pressedChoice = -1;
        playVoice(script.voice());
    }

    /** The speaker's portrait size right now, mid-interpolation. */
    private float currentScale(long nowNanos) {
        if (script.isEmpty()) {
            return 1F;
        }
        DialogueAnimation animation = script.line().animation();
        return DialogueMotion.scaleAt(scaleFrom, scaleTo, nowNanos, lineStartNanos, animation.isScale());
    }

    /**
     * How many characters of {@code text} the typewriter has revealed by {@code now}.
     *
     * <p>Counts characters, not time per character: punctuation is charged a pause on top of its own
     * character, so the reveal walks the string and stops when the budget runs out. Newlines are free
     * and are not counted - the wrap owns the line breaks.
     */
    private int revealedCharacters(long now) {
        if (lineComplete) {
            return visibleLength(script.line().text());
        }
        double budget = Math.max(0D, (now - lineStartNanos) / 1_000_000_000D);
        String text = script.line().text();
        int visible = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (codePoint == '\n') {
                i += width;
                continue;
            }
            double cost = 1D / TYPEWRITER_CHARS_PER_SECOND + pauseAfter(codePoint);
            if (budget < cost) {
                break;
            }
            budget -= cost;
            i += width;
            visible++;
        }
        return visible;
    }

    /** Extra seconds spent on one character, for the punctuation that ends a thought. */
    private static float pauseAfter(int codePoint) {
        return switch (codePoint) {
            case '。', '！', '？', '…', '.', '!', '?' -> PAUSE_AFTER_SENTENCE;
            case '，', '、', '；', '：', ',', ';', ':' -> PAUSE_AFTER_CLAUSE;
            default -> 0F;
        };
    }

    /** Characters that actually get drawn: everything but the explicit line breaks. */
    private static int visibleLength(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (codePoint != '\n') {
                count++;
            }
            i += width;
        }
        return count;
    }

    /**
     * The first {@code visible} drawn characters of a wrapped line list.
     *
     * <p>Indexes the wrapped lines (which have no newlines left) so the typing cursor and the layout
     * agree: the bubble is laid out from the whole line, and only the tail of it is missing while it
     * types.
     */
    private static String typedPrefix(String line, int alreadyDrawn, int visible) {
        int remaining = visible - alreadyDrawn;
        if (remaining <= 0) {
            return "";
        }
        if (remaining >= line.length()) {
            return line;
        }
        // Never split a surrogate pair: half of one is not a character the atlas has.
        int end = remaining;
        if (Character.isHighSurrogate(line.charAt(end - 1)) && end < line.length()) {
            end--;
        }
        return line.substring(0, end);
    }

    /**
     * Jumps to the end of the conversation (ESC, or a host that needs to skip it).
     *
     * <p>No closing slide: this is the player saying "I am done reading", and making them wait out an
     * animation they just asked to skip is the one thing a skip must not do.
     */
    public void skipAll() {
        if (isActive()) {
            script.skipAll();
            finish();
        }
    }

    private void finish() {
        finished = true;
        setVisible(false);
        if (onFinish != null) {
            onFinish.run();
        }
    }

    private void playVoice(String voice) {
        if (voice != null && !voice.isBlank() && client.sound() != null) {
            client.sound().play(voice, 1F, 1F);
        }
    }

    // ------------------------------------------------------------------
    // Input: a modal dialog is offered every event before the screen behind it
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double guiY, int button) {
        if (!isActive()) {
            return false;
        }
        // Every button is swallowed, not just the one that advances: a right click reaching the
        // screen behind would cancel the card selection or drop a card while the player is still
        // reading.
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return true;
        }
        ChoiceButton hit = choiceAt(mouseX, guiY);
        if (hit != null) {
            hoverX = mouseX;
            hoverY = guiY;
            pressedChoice = laidOutChoices.indexOf(hit);
            return true;
        }
        advance();
        return true;
    }

    @Override
    public void mouseReleased(double mouseX, double guiY, int button) {
        int pressed = pressedChoice;
        pressedChoice = -1;
        if (!isActive() || button != GLFW.GLFW_MOUSE_BUTTON_LEFT || pressed < 0) {
            return;
        }
        // The release counts only on the button it started on: a press that slid off is a change
        // of mind, which is the same rule every other button in this game follows.
        ChoiceButton hit = choiceAt(mouseX, guiY);
        if (hit != null && laidOutChoices.indexOf(hit) == pressed) {
            choose(pressed);
        }
    }

    @Override
    public void mouseMoved(double mouseX, double guiY) {
        // Kept so a drawn button can be told where the pointer is without asking the platform
        // again: the widget's hover state is what makes a plate light up under the cursor.
        hoverX = mouseX;
        hoverY = guiY;
    }

    @Override
    public boolean keyPressed(int key) {
        if (!isActive()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            skipAll();
        }
        return true;
    }

    @Override
    public boolean charTyped(char codepoint) {
        return isActive();
    }

    @Override
    public void onResize(int guiWidth, int guiHeight) {
        // Geometry is derived from the client's size on every frame; a dialog's usual "keep the
        // frame on screen" clamp has nothing to clamp.
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Where a portrait ended up, in GUI pixels.
     *
     * <p>{@code x}, {@code width} and {@code height} are the texture's own rectangle - what gets
     * drawn - while {@code visibleLeft} and {@code visibleRight} bound the art inside it, which is
     * what the bubble anchors to (see {@code DialogueCharacterDef.PortraitInsets}).
     */
    private record Portrait(Identifier texture, Identifier character, DialogueSlot slot, boolean speaker,
                            float x, float y, float width, float height,
                            float visibleLeft, float visibleRight) {
    }

    /** The bubble as it was laid out this frame. The answers are not part of it; see {@link #choiceLayout}. */
    private record Bubble(float x, float y, float width, float height, float scale,
                          List<String> lines, float textLeft, float textTop, float textBottom,
                          float hintHeight, String name, String hint, Identifier box) {
    }

    /**
     * Development probe: {@code -Ppvzce.smoke=pvzce.traceDialogue=true} prints what every frame is
     * staging.
     *
     * <p>A conversation that does not move looks the same whether the click never reached the modal,
     * the line is still typing, or a question is holding it - and a screenshot cannot tell those
     * apart. This is the line that does: which line, whose, how much of it is revealed, who is on
     * stage, and whether it is waiting for an answer.
     */
    private static final boolean TRACE = Boolean.getBoolean("pvzce.traceDialogue");

    @Override
    public void render(PvzceClient client) {
        if (!isActive()) {
            return;
        }
        long now = System.nanoTime();
        if (script.tick(now)) {
            // The closing slides just finished: this frame has nothing left to draw and the host has
            // already been told the conversation is over.
            finish();
            return;
        }
        if (!isActive()) {
            return;
        }
        float guiW = client.guiWidth();
        float guiH = client.guiHeight();
        // Capped below what a tall window would ask for: the bubble is a caption, not a headline,
        // and at 1.5 it read as one.
        float textScale = Math.max(0.85F, Math.min(1.35F, guiH / 300F));
        float lineHeight = client.fonts().button().lineHeight(textScale);

        DialogueLine line = script.line();
        float scale = currentScale(now);
        Portrait speaker = null;
        // The listener first, so the speaker is the one on top: they are the one being read.
        List<Portrait> others = new ArrayList<>();
        for (DialogueScript.StagePortrait staged : script.portraits()) {
            Portrait placed = placedPortrait(client, staged, now, guiW, guiH, scale);
            if (placed == null) {
                continue;
            }
            if (placed.speaker()) {
                speaker = placed;
            } else {
                others.add(placed);
            }
        }
        for (Portrait portrait : others) {
            drawPortrait(client, portrait);
        }
        if (speaker != null) {
            drawPortrait(client, speaker);
        }

        String text = substituteUserName(client, line.text());
        if (TRACE) {
            System.out.println("[DLG] line=" + script.index() + "/" + script.size()
                    + " char=" + line.character() + " text=" + text
                    + " revealed=" + revealedCharacters(now) + "/" + visibleLength(text)
                    + " portraits=" + script.portraits().size()
                    + " choices=" + script.choices().size()
                    + " awaiting=" + script.awaitingChoice());
        }
        if (text == null || text.isBlank()) {
            // A silent line: the portraits are the whole beat. Nothing to click through visually,
            // but the click still advances - the modal owns the input either way.
            laidOutChoices = List.of();
            return;
        }
        boolean asked = revealedCharacters(now) >= visibleLength(text);
        // Answers exist once the question has finished being asked - a button that can be pressed
        // while the line is still typing is a button pressed before the question was read - and they
        // are laid out from the speaker and the window, not from the bubble (see {@link #choiceLayout}).
        ChoiceButton[] laidOut = asked ? choiceLayout(script.choices(), line, guiW, guiH)
                : new ChoiceButton[0];
        laidOutChoices = List.of(laidOut);
        Bubble bubble = bubbleBox(client, text, script.speakerName(), speaker, line.side(),
                guiW, guiH, textScale, lineHeight);
        drawBubble(client, bubble, text, lineHeight, textScale, now, asked);
        for (int i = 0; i < laidOut.length; i++) {
            drawChoice(client, laidOut[i], i == pressedChoice);
        }
    }

    private void drawPortrait(PvzceClient client, Portrait portrait) {
        client.drawTexture(portrait.texture(), portrait.x(), portrait.y(),
                portrait.width(), portrait.height(), 0F, 1F, 1F, 1F, 1F);
    }

    /**
     * Draws the bubble: the plate, the name, the typed prefix of the line, the answers, the hint.
     *
     * <p>The bubble is laid out from the whole line and only draws the part that has been typed, so
     * the frame never resizes while the text comes out.
     */
    private void drawBubble(PvzceClient client, Bubble bubble, String text, float lineHeight,
                            float textScale, long now, boolean asked) {
        NinePatch.drawNineSlice(client, bubble.box(), bubble.x(), bubble.y(), bubble.width(), bubble.height(),
                0.1F, BOX_NATIVE_WIDTH, BOX_NATIVE_HEIGHT,
                BOX_INSET_LEFT * bubble.scale(), BOX_INSET_RIGHT * bubble.scale(),
                BOX_INSET_TOP * bubble.scale(), BOX_INSET_BOTTOM * bubble.scale(),
                1F, 1F, 1F, 1F);

        float textX = bubble.x() + bubble.textLeft();
        float cursor = bubble.y() + bubble.height() - bubble.textTop();
        if (!bubble.name().isEmpty()) {
            float nameScale = textScale * NAME_SCALE;
            cursor -= client.fonts().button().lineHeight(nameScale) + 4F;
            client.fonts().button().draw(bubble.name(), textX, cursor + 4F, nameScale,
                    NAME_COLOR_R, NAME_COLOR_G, NAME_COLOR_B, 1F);
        }
        int revealed = revealedCharacters(now);
        int drawn = 0;
        for (String line : bubble.lines()) {
            cursor -= lineHeight;
            String shown = typedPrefix(line, drawn, revealed);
            if (!shown.isEmpty()) {
                client.fonts().button().draw(shown, textX, cursor, textScale,
                        TEXT_COLOR_R, TEXT_COLOR_G, TEXT_COLOR_B, 1F);
            }
            drawn += line.length();
        }
        // The hint sits under the text, right-aligned inside the bubble: it is about the bubble, so
        // it belongs to it rather than to the screen behind it. Its own row was reserved above the
        // bubble's bottom inset, which is where the frame's border and the tail live - drawing it any
        // lower would put it on the lawn behind. It only appears once the line has finished typing:
        // while the text is still coming out, the click completes it instead of continuing, and a
        // "继续" that did something else would be a lie. A question has no "click to continue" at
        // all, because a click does not continue it - its answers do.
        if (asked && !script.awaitingChoice()) {
            float hintScale = textScale * HINT_SCALE;
            float hintWidth = client.fonts().body().width(bubble.hint(), hintScale);
            client.fonts().body().draw(bubble.hint(),
                    bubble.x() + bubble.width() - bubble.textLeft() - hintWidth,
                    bubble.y() + bubble.textBottom() + 2F,
                    hintScale, HINT_COLOR_R, HINT_COLOR_G, HINT_COLOR_B, 1F);
        }
    }

    /**
     * Draws one answer as a real {@link Button}, so it looks like every other button in the game.
     *
     * <p>The widget is built per frame rather than kept: it has no state worth keeping - the plate,
     * the fitted label and the press are all read from where it was laid out this frame - and one
     * that outlived the frame could be clicked after its line had gone.
     */
    private void drawChoice(PvzceClient client, ChoiceButton choice, boolean pressed) {
        Button plate = new Button(Math.round(choice.x()), Math.round(choice.y()),
                Math.round(choice.width()), Math.round(choice.height()), choice.label(), () -> {
        });
        plate.mouseMoved(hoverX >= 0D ? hoverX : choice.x() - 1D, hoverY);
        if (pressed) {
            // The pressed plate is the widget's own inactive look; a dialog has no hover or press
            // state of its own to draw a second way here.
            plate.setActive(false);
        }
        plate.render(client);
    }

    /**
     * The speaker's portrait where it is <em>this frame</em>: in their half of the window, sized
     * from the art's own aspect ratio, shaken if the line asks for it, and pushed off screen while
     * one of the three slides is running.
     *
     * <p>What comes back is the rectangle the <em>art</em> occupies, not the rectangle the texture
     * does: a portrait frame carries whatever margin the artist left around the figure, and a
     * character with a wide one would otherwise stand a third of a window away from their own speech
     * (see {@code DialogueCharacterDef.PortraitInsets}). The whole frame is drawn at the offset that
     * puts the art where the layout asked for it.
     *
     * <p>Null (and nothing drawn) when the character's pack provides no art for their look - the
     * bubble still carries the line, and {@code LevelValidator} is what says the art is missing.
     */
    private Portrait placedPortrait(PvzceClient client, DialogueScript.StagePortrait staged,
                                    long nowNanos, float guiW, float guiH, float scale) {
        DialogueCharacterDef character = staged.character;
        if (character == null) {
            return null;
        }
        Identifier texture = textureOf(client, staged);
        if (texture == null) {
            return null;
        }
        Texture loaded;
        try {
            loaded = client.textures().getOrLoad(texture);
        } catch (RuntimeException e) {
            client.warnMissingTexture(texture);
            return null;
        }
        float height = portraitHeight(client, character, guiW, guiH);
        float width = height * loaded.width() / (float) Math.max(1, loaded.height());
        DialogueCharacterDef.PortraitInsets insets = character.insets(staged.portrait);
        boolean right = staged.slot == DialogueSlot.RIGHT;
        // Where the art's own edge is put: the window margin, on the character's side.
        float edge = right ? guiW * (1F - PORTRAIT_MARGIN_RATIO) : guiW * PORTRAIT_MARGIN_RATIO;
        DialogueScript.Slide slide = script.slideOf(character.id());
        if (slide != null) {
            // Entering and leaving are the same journey in opposite directions; the script has
            // already decided which of them this is.
            float progress = DialogueMotion.slideProgress(nowNanos, slide.startNanos);
            edge += DialogueMotion.slideOffsetX(progress, false, !right, slide.entering, guiW);
        }
        boolean speaker = character.id().equals(script.line().character());
        float scaledWidth = width * scale;
        float scaledHeight = height * scale;
        float grow = (width - scaledWidth) / 2F;
        // Where the art's own edge lands, and therefore where the frame is drawn from: the layout
        // asks for the figure to stand at the edge, not for the texture to.
        float padded = 1F - insets.left() - insets.right();
        float artEdge = right ? edge - scaledWidth * padded : edge;
        float x = artEdge - scaledWidth * insets.left() + grow;
        if (speaker && slide == null && script.line().animation().isShake()) {
            x += DialogueMotion.shakeOffset(nowNanos, lineStartNanos, guiW,
                    script.line().animation().amount());
        }
        float visibleLeft = x + scaledWidth * insets.left();
        float visibleRight = x + scaledWidth * (1F - insets.right());
        return new Portrait(texture, character.id(), staged.slot, speaker, x, 0F, scaledWidth, scaledHeight,
                visibleLeft, visibleRight);
    }

    /** The texture a staged character is drawn with, or null when the pack has neither look. */
    private Identifier textureOf(PvzceClient client, DialogueScript.StagePortrait staged) {
        Identifier texture = staged.character.portraitTexture(staged.portrait);
        if (texture != null && client.hasTexture(texture)) {
            return texture;
        }
        // The named look is not in this pack. Fall back to the character's own portrait (the one
        // named after them) rather than to nothing: a speaker who vanishes for one line reads as a
        // bug, and the line is still the line.
        Identifier fallback = staged.character.portraitTexture(staged.character.id().path());
        if (fallback != null && client.hasTexture(fallback)) {
            if (texture != null && !staged.portrait.isBlank()) {
                LOGGER.warn("Unknown portrait '{}' for '{}': using '{}'",
                        staged.portrait, staged.character.id(), staged.character.id().path());
            }
            return fallback;
        }
        return null;
    }

    /** The height every portrait is laid out at, so two of them fit side by side. */
    private float portraitHeight(PvzceClient client, DialogueCharacterDef character, float guiW, float guiH) {
        List<Float> ratios = new ArrayList<>();
        for (DialogueScript.StagePortrait staged : script.portraits()) {
            ratios.add(aspectOf(client, staged, character));
        }
        float tallest = guiH * PORTRAIT_HEIGHT_RATIO * character.scale();
        return DialogueMotion.portraitHeight(tallest, guiW * (1F - PORTRAIT_MARGIN_RATIO * 2F), ratios);
    }

    /** A portrait's width over its height, from the loaded texture; 2/3 when it is not drawable. */
    private float aspectOf(PvzceClient client, DialogueScript.StagePortrait staged,
                           DialogueCharacterDef measuring) {
        Identifier texture = textureOf(client, staged);
        if (texture == null) {
            return 2F / 3F;
        }
        try {
            Texture loaded = client.textures().getOrLoad(texture);
            // The layout height carries this character's own scale, so the width has to as well:
            // a character drawn 1.2x takes 1.2x the room, and the pair has to be shrunk for it.
            float scale = measuring == null ? 1F : measuring.scale();
            return loaded.width() * scale / (float) Math.max(1, loaded.height());
        } catch (RuntimeException e) {
            return 2F / 3F;
        }
    }

    /**
     * The speech bubble: beside the speaker, hugging its own text - or nothing at all.
     *
     * <p>Width comes from the text, not from the window: a one-line reply gets a small bubble next to
     * the character instead of a wide empty box across half the screen. The wrapping limit is the
     * widest bubble that fits beside the speaker without running off the window, so a long line may
     * reach past the middle rather than being confined to the gap between two characters: when
     * someone is standing in the other half, a bubble that stopped at their edge would wrap a
     * sentence into four lines to avoid covering a portrait the reader has already seen.
     *
     * <p>A line with no text gets no bubble (the caller never asks for one), and a centred speaker
     * gets one across the middle of the window rather than beside a portrait that is not beside
     * anything: "beside" has no meaning in the middle, and both answers - left or right - would point
     * the tail at empty lawn.
     */
    private Bubble bubbleBox(PvzceClient client, String text, String speakerName,
                             Portrait speaker, DialogueLine.Side side, float guiW, float guiH,
                             float textScale, float lineHeight) {
        float margin = guiH * SCREEN_MARGIN_RATIO;
        float gap = guiW * BUBBLE_GAP_RATIO;
        float sideMargin = guiW * BUBBLE_SIDE_MARGIN_RATIO;
        boolean left = !side.isRight();
        float free;
        if (side.isCenter() || speaker == null) {
            free = guiW - sideMargin * 2F - gap;
        } else if (left) {
            free = guiW - nearPortraitEdge(speaker, true) - sideMargin - gap;
        } else {
            free = nearPortraitEdge(speaker, false) - sideMargin - gap;
        }
        float maxWidth = Math.max(guiW * BUBBLE_MIN_WIDTH_RATIO,
                Math.min(guiW * BUBBLE_MAX_WIDTH_RATIO, free));

        // Every inset scales with the bubble, so the tail in the bottom corner keeps its shape at any
        // bubble size instead of being squeezed sideways.
        float scale = maxWidth / BOX_NATIVE_WIDTH;
        float textLeft = TEXT_INSET_LEFT * scale;
        float textRight = TEXT_INSET_RIGHT * scale;
        float textTop = TEXT_INSET_TOP * scale;
        float textBottom = TEXT_INSET_BOTTOM * scale;

        List<String> lines = avoidOrphans(client.fonts().button().wrapLines(text,
                Math.max(24F, maxWidth - textLeft - textRight), textScale));
        String name = speakerName == null ? "" : speakerName;
        float nameHeight = name.isEmpty() ? 0F
                : client.fonts().button().lineHeight(textScale * NAME_SCALE) + 4F;
        String hint = GuiLang.raw(HINT_KEY, HINT_FALLBACK);
        float hintHeight = client.fonts().body().lineHeight(textScale * HINT_SCALE) + 3F;

        float widest = 0F;
        for (String line : lines) {
            widest = Math.max(widest, client.fonts().button().width(line, textScale));
        }
        float width = Math.max(guiW * BUBBLE_MIN_WIDTH_RATIO,
                Math.min(maxWidth, widest + textLeft + textRight));
        float height = nameHeight + lines.size() * lineHeight + textTop + hintHeight + textBottom;
        float x;
        if (side.isCenter()) {
            // Across the lower half of a centred portrait, like a caption: the bubble's bottom edge
            // is already the speaker's chin height, so this is the same band the side layout uses,
            // just centred under the face.
            x = (guiW - width) / 2F;
        } else if (left) {
            x = speaker == null ? sideMargin : nearPortraitEdge(speaker, true) + gap;
        } else {
            x = speaker == null ? guiW - width - sideMargin
                    : nearPortraitEdge(speaker, false) - gap - width;
        }
        x = Math.max(sideMargin, Math.min(guiW - width - sideMargin, x));
        float y = Math.min(guiH * BUBBLE_BOTTOM_RATIO, guiH - height - margin);
        y = Math.max(margin, y);
        Identifier box = speaker == null || speaker.character() == null
                ? Identifier.withDefaultNamespace(left
                        ? "textures/gui/dialogue/box_left" : "textures/gui/dialogue/box_right")
                : boxOf(speaker, left);
        return new Bubble(x, y, width, height, scale, lines, textLeft, textTop, textBottom,
                hintHeight, name, hint, box);
    }

    /** The bubble texture the speaker talks through, from their own character definition. */
    private Identifier boxOf(Portrait speaker, boolean left) {
        DialogueCharacterDef character = BuiltInRegistries.DIALOGUE_CHARACTERS.get(speaker.character());
        if (character == null) {
            return Identifier.withDefaultNamespace(left
                    ? "textures/gui/dialogue/box_left" : "textures/gui/dialogue/box_right");
        }
        return character.box(left);
    }

    /**
     * Lays the answers out on the screen: beside the speaker, across the window, low down.
     *
     * <p>Not inside the bubble, and not over the character. A speech bubble is the character's, and a
     * row of things to press inside it reads as part of what they are saying; the answers stand in
     * the half of the window the <em>speaker is not in</em> - left of a right-hand speaker, right of
     * a left-hand one - so pressing one never covers the face that just asked. They are laid out
     * against the window's bottom edge band rather than the bubble's, so the same question asked by
     * a short line and a long one puts its answers in the same place.
     *
     * <p>One row when they fit, otherwise stacked in equally wide columns, and all of them the same
     * size: a wide answer gets a smaller label, not a wider button, because a list of unequal widths
     * reads as if some answers mattered more than others.
     */
    private ChoiceButton[] choiceLayout(List<DialogueChoice> choices, DialogueLine line,
                                        float guiW, float guiH) {
        ChoiceButton[] laidOut = new ChoiceButton[choices.size()];
        if (choices.isEmpty()) {
            return laidOut;
        }
        // The half the speaker is not standing in: their own half stays clear of anything clickable.
        boolean rightSide = !line.side().isRight();
        float blockWidth = guiW * CHOICE_BLOCK_WIDTH_RATIO;
        float height = Math.max(CHOICE_MIN_HEIGHT, Math.min(CHOICE_MAX_HEIGHT,
                guiH * CHOICE_HEIGHT_RATIO));
        float gap = height * CHOICE_GAP_RATIO;
        // One row while the answers fit across the block; past that they stack, widest row first.
        int columns = Math.max(1, (int) (blockWidth / (height * CHOICE_ASPECT_RATIO)));
        columns = Math.min(columns, Math.max(1, choices.size()));
        if (columns >= choices.size()) {
            columns = choices.size();
        } else if (choices.size() % columns == 1 && columns > 1) {
            // A last row with a single answer on it reads as an afterthought; move one down from
            // the row above so both rows carry the same number.
            columns--;
        }
        int rows = (choices.size() + columns - 1) / columns;
        float buttonWidth = (blockWidth - (columns - 1) * gap) / columns;
        float blockHeight = rows * height + (rows - 1) * gap;
        float blockLeft = rightSide ? guiW - guiW * CHOICE_SIDE_MARGIN_RATIO - blockWidth
                : guiW * CHOICE_SIDE_MARGIN_RATIO;
        float blockBottom = Math.max(guiH * CHOICE_BOTTOM_RATIO, guiH * SCREEN_MARGIN_RATIO);
        for (int i = 0; i < choices.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            // Bottom row first: the answers climb away from the window's edge, so the one the eye
            // reaches first is the one nearest the bottom of the screen.
            int fromBottom = rows - 1 - row;
            laidOut[i] = new ChoiceButton(blockLeft + column * (buttonWidth + gap),
                    blockBottom + fromBottom * (height + gap), buttonWidth, height,
                    substituteUserName(client, choices.get(i).text()));
        }
        return laidOut;
    }

    /** The answer button under the pointer, or null. */
    private ChoiceButton choiceAt(double mouseX, double guiY) {
        for (ChoiceButton choice : laidOutChoices) {
            if (mouseX >= choice.x() && mouseX < choice.x() + choice.width()
                    && guiY >= choice.y() && guiY < choice.y() + choice.height()) {
                return choice;
            }
        }
        return null;
    }

    /**
     * Where the bubble's tail should sit across a portrait.
     *
     * <p>Not the art's edge: a portrait is a figure with air around it, and the bubble belongs beside
     * the face rather than out where the character's shoulder ends. The two constants are the near
     * edge of the art (whichever side the bubble is on) and how far back along it the bubble's
     * corner reaches, which is what {@code BUBBLE_PORTRAIT_ANCHOR_RATIO} has always meant.
     */
    private static float nearPortraitEdge(Portrait portrait, boolean left) {
        float visible = left ? portrait.visibleRight() : portrait.visibleLeft();
        float width = Math.max(1F, portrait.visibleRight() - portrait.visibleLeft());
        return left
                ? visible - width * (1F - BUBBLE_PORTRAIT_ANCHOR_RATIO)
                : visible + width * (1F - BUBBLE_PORTRAIT_ANCHOR_RATIO);
    }

    /**
     * Moves one character down when a wrapped line would be left alone on the last row.
     *
     * <p>{@code wrapLines} breaks wherever the width runs out, so "…僵尸的世" / "界" is a perfectly
     * correct wrap and a perfectly ugly one: a single character has no shape to align to. The bubble
     * hugs its widest line either way, so this costs no room.
     */
    private static List<String> avoidOrphans(List<String> lines) {
        if (lines.size() < 2) {
            return lines;
        }
        String last = lines.get(lines.size() - 1);
        String previous = lines.get(lines.size() - 2);
        if (last.length() != 1 || previous.length() < 2) {
            return lines;
        }
        List<String> balanced = new ArrayList<>(lines);
        balanced.set(balanced.size() - 2, previous.substring(0, previous.length() - 1));
        balanced.set(balanced.size() - 1, previous.substring(previous.length() - 1) + last);
        return balanced;
    }
}
