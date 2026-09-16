package com.pvzce.client.gui.components;

import com.pvzce.api.content.DialogueCharacterDef;
import com.pvzce.api.content.DialogueLine;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.renderer.texture.Texture;
import com.pvzce.common.core.BuiltInRegistries;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A level's opening conversation: a portrait on the speaker's side, a speech bubble on the
 * other, one line at a time, advanced by a click.
 *
 * <p>It is a {@link Dialog} rather than a screen because that is this project's one
 * mechanism for "this thing owns the input": {@code Screen} routes every mouse, key, scroll
 * and char event to the topmost visible modal dialog first, so a screen hosting a dialogue
 * cannot also react to the clicks that advance it. The frame and backdrop a dialog normally
 * draws are overridden away - the scene behind a dialogue is the point, not something to
 * dim.
 *
 * <p>Both places a game can start use it: the seed chooser shows it before the camera pans
 * and the card panel slides in, and a level entered directly (a conveyor level, which has
 * no chooser) shows it over the lawn with the level paused.
 *
 * <p>Nothing here decides <em>whether</em> a dialogue plays - the host screen is handed the
 * lines and shows them. Whether a run is fresh is the server's and the client's entry
 * decision, not a presentation detail.
 */
public final class DialogueOverlay extends Dialog {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Dialogue");

    /**
     * How tall a portrait stands, as a fraction of the window, before {@code scale}.
     *
     * <p>Nearly the whole height on purpose: a portrait is the character's presence in the
     * scene, and at two thirds of the window it read as a sticker next to the lawn rather
     * than as someone standing in the yard. The bubble then takes whatever width is left
     * beside it instead of the portrait being shrunk to make room.
     */
    private static final float PORTRAIT_HEIGHT_RATIO = 0.90F;
    /** Gap between a portrait and its window edge. */
    private static final float PORTRAIT_MARGIN_RATIO = 0.008F;
    /** The bubble is sized to its text, within these fractions of the window width. */
    private static final float BUBBLE_MAX_WIDTH_RATIO = 0.46F;
    private static final float BUBBLE_MIN_WIDTH_RATIO = 0.20F;
    /**
     * Where the bubble's near edge sits across the portrait: 0.8 = 80% of the portrait's
     * width, not its far edge.
     *
     * <p>A portrait is a full frame of art with transparent margins, so anchoring the
     * bubble on the texture's edge leaves a character-sized hole of nothing between them.
     * Most of these portraits end around 73-90% of the frame width, so the bubble tucks
     * into that margin and reads as being next to the face rather than across the yard.
     */
    private static final float BUBBLE_PORTRAIT_ANCHOR_RATIO = 0.80F;
    /** Gap between the portrait's visible edge and the bubble beside it. */
    private static final float BUBBLE_GAP_RATIO = 0.008F;
    private static final float BUBBLE_SIDE_MARGIN_RATIO = 0.02F;
    /**
     * Where the bubble's bottom edge wants to sit, as a fraction of the window height.
     *
     * <p>Roughly at the speaker's chin: the tail hangs from the bubble's bottom corner, so
     * this is what makes it point at the character rather than at the grass.
     */
    private static final float BUBBLE_BOTTOM_RATIO = 0.70F;
    /** Smallest gap left between the bubble and the window's top edge. */
    private static final float SCREEN_MARGIN_RATIO = 0.02F;

    /**
     * The authored size of a speech bubble ({@code textures/gui/dialogue/box_left.png}).
     *
     * <p>The bubble's tail is part of the bottom-left (or bottom-right) corner slice, so
     * that inset has to be wide and tall enough to contain it whole: the tail spans x
     * 29..54 and the bottom 23 rows of the 280x183 image. Anything narrower would stretch
     * the tail sideways, anything shorter would cut it off.
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
     * <p>Slow enough to read along with, fast enough that a two-line reply is not a wait:
     * a 16-character Chinese line takes about half a second. Punctuation adds the pauses
     * in {@link #pauseAfter}, which is what makes it read as speech instead of a
     * teleprinter.
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

    /** One resolved line: the definitions behind a {@link DialogueLine}, looked up once. */
    private record Frame(DialogueCharacterDef character, Identifier portrait, String text,
                         String voice, boolean left, boolean center,
                         com.pvzce.api.content.DialogueAnimation animation) {
        String name() {
            return character == null ? "" : character.displayName();
        }

        /**
         * True when this line says nothing.
         *
         * <p>A portrait-only beat: the character is on screen and nobody is talking, which
         * is what an empty {@code text} means. Drawing a bubble for it produced a small
         * empty box with a "click to continue" in it, which reads as a bug rather than as
         * a performance.
         */
        boolean silent() {
            return text == null || text.isBlank();
        }
    }

    private final PvzceClient client;
    private final List<Frame> frames;
    /** How this conversation's first portrait comes on and its last one goes off. */
    private final boolean enterSlides;
    private final boolean exitSlides;
    private int index;
    private boolean finished;
    /**
     * True while the closing slide is playing: the conversation is over, the portrait is
     * still on its way out, and {@code onFinish} waits for it.
     */
    private boolean exiting;
    private long exitStartNanos;
    /** When the first portrait started sliding in. */
    private final long enterStartNanos;
    /** The portrait size the current line animates from and to. */
    private float scaleFrom = 1F;
    private float scaleTo = 1F;
    private Runnable onFinish;
    /** When the current line started typing; the reveal is derived from wall time. */
    private long lineStartNanos;
    /** True once the line is fully on screen, either by typing out or by a click. */
    private boolean lineComplete;
    /** Ids already reported as unknown, so a level with a typo logs once instead of per frame. */
    private static final Set<String> REPORTED = new HashSet<>();

    private DialogueOverlay(PvzceClient client, List<Frame> frames,
                            com.pvzce.api.content.DialogueEffect enter,
                            com.pvzce.api.content.DialogueEffect exit) {
        super(0, 0, 0, 0, "");
        this.client = client;
        this.frames = frames;
        this.enterSlides = enter.slides();
        this.exitSlides = exit.slides();
        closeOnEscape(false);
        enterStartNanos = System.nanoTime();
        lineStartNanos = enterStartNanos;
        scaleTo = frames.isEmpty() ? 1F : frames.get(0).animation().targetScale();
        scaleFrom = 1F;
        playVoice(frames.isEmpty() ? null : frames.get(0).voice());
    }

    /**
     * Builds the overlay for a script, resolving each line's character.
     *
     * <p>Returns {@code null} when there is nothing to say, so a host screen can hold
     * {@code null} to mean "no dialogue" instead of carrying an empty overlay around and
     * asking it whether it is empty on every path.
     */
    public static DialogueOverlay create(PvzceClient client,
                                         com.pvzce.api.content.LevelDialogue dialogue,
                                         Runnable onFinish) {
        if (dialogue == null || dialogue.isEmpty()) {
            return null;
        }
        List<DialogueLine> script = dialogue.lines();
        List<Frame> frames = new ArrayList<>(script.size());
        for (DialogueLine line : script) {
            DialogueCharacterDef character = line.character() == null
                    ? null : BuiltInRegistries.DIALOGUE_CHARACTERS.get(line.character());
            if (character == null && line.character() != null && REPORTED.add(line.character().toString())) {
                LOGGER.warn("Unknown dialogue character '{}': the line is shown without a portrait",
                        line.character());
            }
            Identifier portrait = character == null ? null : character.portraitTexture(line.portrait());
            frames.add(new Frame(character, portrait, line.text(), line.voice(),
                    !line.side().isRight() && !line.side().isCenter(), line.side().isCenter(),
                    line.animation()));
        }
        DialogueOverlay overlay = new DialogueOverlay(client, frames, dialogue.enter(), dialogue.exit());
        overlay.onFinish = onFinish;
        return overlay;
    }

    /** True while the player still has lines to click through. */
    public boolean isActive() {
        return !finished && !frames.isEmpty();
    }

    /**
     * One click: finish typing the line, or move on.
     *
     * <p>Two stages, because the line is being typed: while it is still coming out, a click
     * means "I have read enough, show me the rest"; once it is all there, a click means
     * "next". A single-stage click would skip text the player never saw, and making them
     * wait for a line they already finished reading is the other way to get this wrong.
     *
     * <p>On the last line the click ends the conversation, which is where the closing slide
     * goes: the portrait leaves the screen first and {@code onFinish} runs when it is gone.
     * Clicks during that beat do nothing - there is nothing left to advance.
     */
    public void advance() {
        if (!isActive() || exiting) {
            return;
        }
        if (!lineComplete && revealedCharacters(System.nanoTime()) < visibleLength(frames.get(index).text())) {
            lineComplete = true;
            return;
        }
        if (index + 1 < frames.size()) {
            index++;
            lineComplete = false;
            lineStartNanos = System.nanoTime();
            startLineAnimation(frames.get(index));
            playVoice(frames.get(index).voice());
            return;
        }
        if (exitSlides) {
            exiting = true;
            exitStartNanos = System.nanoTime();
            return;
        }
        finish();
    }

    /**
     * Arms a line's own animation: the size it wants and, for a shake, its start time.
     *
     * <p>The size is interpolated from whatever the previous line left behind, so two lines
     * that ask for different sizes grow and shrink instead of snapping; a line with no
     * animation returns the portrait to its layout size the same way.
     */
    private void startLineAnimation(Frame frame) {
        scaleFrom = currentScale(System.nanoTime());
        scaleTo = frame.animation().targetScale();
    }

    /** The portrait's size right now, mid-interpolation. */
    private float currentScale(long nowNanos) {
        if (frames.isEmpty()) {
            return 1F;
        }
        com.pvzce.api.content.DialogueAnimation animation = frames.get(index).animation();
        return DialogueMotion.scaleAt(scaleFrom, scaleTo, nowNanos, lineStartNanos, animation.isScale());
    }

    /**
     * How many characters of {@code text} the typewriter has revealed by {@code now}.
     *
     * <p>Counts characters, not time per character: punctuation is charged a pause on top
     * of its own character, so the reveal walks the string and stops when the budget runs
     * out. Newlines are free and are not counted - the wrap owns the line breaks.
     */
    private int revealedCharacters(long now) {
        if (lineComplete) {
            return visibleLength(frames.get(index).text());
        }
        double budget = Math.max(0D, (now - lineStartNanos) / 1_000_000_000D);
        String text = frames.get(index).text();
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
     * <p>Indexes the wrapped lines (which have no newlines left) so the typing cursor and
     * the layout agree: the bubble is laid out from the whole line, and only the tail of it
     * is missing while it types.
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
     * <p>No closing slide: this is the player saying "I am done reading", and making them
     * wait out an animation they just asked to skip is the one thing a skip must not do.
     */
    public void skipAll() {
        if (isActive()) {
            finish();
        }
    }

    private void finish() {
        finished = true;
        exiting = false;
        setVisible(false);
        if (onFinish != null) {
            onFinish.run();
        }
    }

    /**
     * Ends the conversation once the closing slide has played out.
     *
     * <p>Called from {@link #render}: the overlay is drawn every frame while it is up, which
     * is the frame clock this beat belongs to.
     */
    private void tickExit(long nowNanos) {
        if (exiting && DialogueMotion.progress(nowNanos, exitStartNanos, DialogueMotion.SLIDE_NANOS) >= 1F) {
            finish();
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
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            advance();
        }
        // Every button is swallowed, not just the one that advances: a right click
        // reaching the screen behind would cancel the card selection or drop a card
        // while the player is still reading.
        return true;
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
        // Geometry is derived from the client's size on every frame; a dialog's usual
        // "keep the frame on screen" clamp has nothing to clamp.
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Where a portrait and a bubble ended up, in GUI pixels. */
    private record Portrait(Identifier texture, float x, float y, float width, float height) {
    }

    private record Bubble(float x, float y, float width, float height, float scale,
                          List<String> lines, float textLeft, float textTop, float textBottom,
                          float hintHeight, String name, String hint) {
    }

    @Override
    public void render(PvzceClient client) {
        if (!isActive()) {
            return;
        }
        long now = System.nanoTime();
        tickExit(now);
        if (!isActive()) {
            // The closing slide just finished: this frame has nothing left to draw and the
            // host has already been told the conversation is over.
            return;
        }
        Frame frame = frames.get(index);
        float guiW = client.guiWidth();
        float guiH = client.guiHeight();
        // Capped below what a tall window would ask for: the bubble is a caption, not a
        // headline, and at 1.5 it read as one.
        float textScale = Math.max(0.85F, Math.min(1.35F, guiH / 300F));
        float lineHeight = client.font().lineHeight(textScale);

        Portrait portrait = animatedPortrait(frame, guiW, guiH, now);
        Bubble bubble = bubbleBox(client, frame, portrait, guiW, guiH, textScale, lineHeight);

        if (portrait != null) {
            client.drawTexture(portrait.texture(), portrait.x(), portrait.y(),
                    portrait.width(), portrait.height(), 0F, 1F, 1F, 1F, 1F);
        }
        if (bubble == null) {
            // A silent line: the portrait is the whole beat. Nothing to click through
            // visually, but the click still advances - the modal owns the input either way.
            return;
        }
        Identifier box = frame.character() == null
                ? Identifier.withDefaultNamespace(frame.left()
                        ? "textures/gui/dialogue/box_left" : "textures/gui/dialogue/box_right")
                : frame.character().box(frame.left());
        NinePatch.drawNineSlice(client, box, bubble.x(), bubble.y(), bubble.width(), bubble.height(), 0.1F,
                BOX_NATIVE_WIDTH, BOX_NATIVE_HEIGHT,
                BOX_INSET_LEFT * bubble.scale(), BOX_INSET_RIGHT * bubble.scale(),
                BOX_INSET_TOP * bubble.scale(), BOX_INSET_BOTTOM * bubble.scale(),
                1F, 1F, 1F, 1F);

        float textX = bubble.x() + bubble.textLeft();
        float cursor = bubble.y() + bubble.height() - bubble.textTop();
        if (!bubble.name().isEmpty()) {
            float nameScale = textScale * NAME_SCALE;
            cursor -= client.font().lineHeight(nameScale) + 4F;
            client.font().draw(bubble.name(), textX, cursor + 4F, nameScale,
                    NAME_COLOR_R, NAME_COLOR_G, NAME_COLOR_B, 1F);
        }
        // The bubble is laid out from the whole line and only draws the part that has
        // been typed, so the frame never resizes while the text comes out.
        int revealed = revealedCharacters(System.nanoTime());
        int drawn = 0;
        for (String line : bubble.lines()) {
            cursor -= lineHeight;
            String shown = typedPrefix(line, drawn, revealed);
            if (!shown.isEmpty()) {
                client.font().draw(shown, textX, cursor, textScale,
                        TEXT_COLOR_R, TEXT_COLOR_G, TEXT_COLOR_B, 1F);
            }
            drawn += line.length();
        }
        // The hint sits under the text, right-aligned inside the bubble: it is about the
        // bubble, so it belongs to it rather than to the screen behind it. Its own row was
        // reserved above the bubble's bottom inset, which is where the frame's border and
        // the tail live - drawing it any lower would put it on the lawn behind. It only
        // appears once the line has finished typing: while the text is still coming out,
        // the click completes it instead of continuing, and a "继续" that did something
        // else would be a lie.
        if (revealed >= visibleLength(frame.text())) {
            float hintScale = textScale * HINT_SCALE;
            float hintWidth = client.font().width(bubble.hint(), hintScale);
            client.font().draw(bubble.hint(),
                    bubble.x() + bubble.width() - bubble.textLeft() - hintWidth,
                    bubble.y() + bubble.textBottom() + 2F,
                    hintScale, HINT_COLOR_R, HINT_COLOR_G, HINT_COLOR_B, 1F);
        }
    }

    /** The portrait's visible edge on the side the bubble sits: its texture is mostly margin. */
    private static float nearPortraitEdge(Portrait portrait, boolean left) {
        return left
                ? portrait.x() + portrait.width() * BUBBLE_PORTRAIT_ANCHOR_RATIO
                : portrait.x() + portrait.width() * (1F - BUBBLE_PORTRAIT_ANCHOR_RATIO);
    }

    /**
     * The speaker's box: on their side of the window, feet on the bottom edge, sized from
     * the art's own aspect ratio so the character is never stretched.
     *
     * <p>{@code center} stands them in the middle of the window instead, at the same size -
     * the staging for a line that is about the character rather than about the exchange.
     *
     * <p>Null (and nothing drawn) when the line names no portrait or the pack does not
     * provide it - the bubble still carries the line, and {@code LevelValidator} is what
     * says the art is missing.
     */
    private Portrait portraitBox(Frame frame, float guiW, float guiH) {
        Identifier texture = frame.portrait();
        if (texture == null || !client.hasTexture(texture)) {
            return null;
        }
        Texture loaded;
        try {
            loaded = client.textures().getOrLoad(texture);
        } catch (RuntimeException e) {
            client.warnMissingTexture(texture);
            return null;
        }
        float scale = frame.character() == null ? 1F : frame.character().scale();
        float height = guiH * PORTRAIT_HEIGHT_RATIO * scale;
        float width = height * loaded.width() / (float) Math.max(1, loaded.height());
        float x;
        if (frame.center()) {
            x = (guiW - width) / 2F;
        } else {
            x = frame.left()
                    ? guiW * PORTRAIT_MARGIN_RATIO
                    : guiW - width - guiW * PORTRAIT_MARGIN_RATIO;
        }
        return new Portrait(texture, x, 0F, width, height);
    }

    /**
     * The portrait where it is <em>this frame</em>: sized for the line, shaken if the line
     * asks for it, and pushed off screen while the conversation slides on or off.
     *
     * <p>All three are applied to the box rather than to a transform stack, because the bubble
     * is anchored to the portrait's edge: moving the box is what makes the two travel together
     * instead of the bubble standing still while its speaker walks.
     *
     * <p>The size scales about the portrait's floor - a character grows taller, they do not
     * float - which is why a line may only ask for so much of it ({@code MAX_SCALE}): past the
     * window's own height the top of the art leaves the screen.
     */
    private Portrait animatedPortrait(Frame frame, float guiW, float guiH, long nowNanos) {
        Portrait placed = portraitBox(frame, guiW, guiH);
        if (placed == null) {
            return null;
        }
        float scale = currentScale(nowNanos);
        float width = placed.width() * scale;
        float height = placed.height() * scale;
        // Keep the floor and the centre line: growing goes up, shrinking settles down.
        float x = placed.x() + (placed.width() - width) / 2F;
        float y = placed.y();

        float progress = slideProgress(nowNanos);
        boolean entering = !exiting;
        float offsetX = DialogueMotion.slideOffsetX(
                progress, frame.center(), frame.left(), entering, guiW);
        float offsetY = DialogueMotion.slideOffsetY(progress, frame.center(), entering, guiH);
        if (frame.animation().isShake()) {
            offsetX += DialogueMotion.shakeOffset(nowNanos, lineStartNanos, guiW,
                    frame.animation().amount());
        }
        return new Portrait(placed.texture(), x + offsetX, y + offsetY, width, height);
    }

    /**
     * How far the slide that is running has come: the closing one while the conversation is
     * ending, the opening one otherwise, and 1 (in place) when there is no opening slide.
     *
     * <p>The closing slide is a separate timer rather than a rewind of the opening one, so a
     * conversation with {@code "enter": "none"} - or one that was read past its opening beat -
     * still leaves from its place on screen.
     */
    private float slideProgress(long nowNanos) {
        if (exiting) {
            return DialogueMotion.slideProgress(nowNanos, exitStartNanos);
        }
        if (!enterSlides) {
            return 1F;
        }
        return DialogueMotion.slideProgress(nowNanos, enterStartNanos);
    }

    /**
     * Moves one character down when a wrapped line would be left alone on the last row.
     *
     * <p>{@code wrapLines} breaks wherever the width runs out, so "…僵尸的世" / "界" is a
     * perfectly correct wrap and a perfectly ugly one: a single character has no shape to
     * align to. The bubble hugs its widest line either way, so this costs no room.
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
        List<String> balanced = new java.util.ArrayList<>(lines);
        balanced.set(balanced.size() - 2, previous.substring(0, previous.length() - 1));
        balanced.set(balanced.size() - 1, previous.substring(previous.length() - 1) + last);
        return balanced;
    }

    /**
     * The speech bubble: beside the portrait, hugging its own text - or nothing at all.
     *
     * <p>Width comes from the text, not from the window: a one-line reply gets a small
     * bubble next to the character instead of a wide empty box across half the screen.
     * The wrapping limit is the widest bubble that fits beside the portrait, so a long
     * line grows the bubble up to that limit and then wraps.
     *
     * <p>A line with no text gets no bubble (see {@link Frame#silent()}), and a centred
     * speaker gets one across the middle of the window rather than beside a portrait that
     * is not beside anything: "beside" has no meaning in the middle, and both answers -
     * left or right - would point the tail at empty lawn.
     */
    private Bubble bubbleBox(PvzceClient client, Frame frame, Portrait portrait, float guiW, float guiH,
                             float textScale, float lineHeight) {
        if (frame.silent()) {
            return null;
        }
        float margin = guiH * SCREEN_MARGIN_RATIO;
        float gap = guiW * BUBBLE_GAP_RATIO;
        float sideMargin = guiW * BUBBLE_SIDE_MARGIN_RATIO;
        float free = portrait == null || frame.center()
                ? guiW - sideMargin * 2F - gap
                : (frame.left()
                        ? guiW - nearPortraitEdge(portrait, true)
                        : nearPortraitEdge(portrait, false)) - sideMargin - gap;
        float maxWidth = Math.max(guiW * BUBBLE_MIN_WIDTH_RATIO,
                Math.min(guiW * BUBBLE_MAX_WIDTH_RATIO, free));

        // Every inset scales with the bubble, so the tail in the bottom corner keeps its
        // shape at any bubble size instead of being squeezed sideways.
        float scale = maxWidth / BOX_NATIVE_WIDTH;
        float textLeft = TEXT_INSET_LEFT * scale;
        float textRight = TEXT_INSET_RIGHT * scale;
        float textTop = TEXT_INSET_TOP * scale;
        float textBottom = TEXT_INSET_BOTTOM * scale;

        List<String> lines = avoidOrphans(client.font().wrapLines(frame.text(),
                Math.max(24F, maxWidth - textLeft - textRight), textScale));
        String name = frame.name();
        float nameHeight = name.isEmpty() ? 0F
                : client.font().lineHeight(textScale * NAME_SCALE) + 4F;
        String hint = GuiLang.raw(HINT_KEY, HINT_FALLBACK);
        float hintHeight = client.font().lineHeight(textScale * HINT_SCALE) + 3F;

        float widest = 0F;
        for (String line : lines) {
            widest = Math.max(widest, client.font().width(line, textScale));
        }
        float width = Math.max(guiW * BUBBLE_MIN_WIDTH_RATIO,
                Math.min(maxWidth, widest + textLeft + textRight));
        float height = nameHeight + lines.size() * lineHeight + textTop + hintHeight + textBottom;
        float x;
        if (frame.center()) {
            // Across the lower half of a centred portrait, like a caption: the bubble's
            // bottom edge is already the speaker's chin height, so this is the same band
            // the side layout uses, just centred under the face.
            x = (guiW - width) / 2F;
        } else if (frame.left()) {
            x = portrait == null ? sideMargin : nearPortraitEdge(portrait, true) + gap;
        } else {
            x = portrait == null ? guiW - width - sideMargin
                    : nearPortraitEdge(portrait, false) - gap - width;
        }
        float y = Math.min(guiH * BUBBLE_BOTTOM_RATIO, guiH - height - margin);
        y = Math.max(margin, y);
        return new Bubble(x, y, width, height, scale, lines, textLeft, textTop, textBottom,
                hintHeight, name, hint);
    }
}
