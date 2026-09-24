package com.pvzce.client.gui.components;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.font.TextStyle;

/**
 * Reusable MC-style text button.
 *
 * <p>The frame is rendered from the sliced textures in
 * {@code assets/pvzce/textures/gui/button} (normal and pressed variants),
 * never from a flat colored quad. Screens may either construct the button
 * directly or use {@link #builder(String, Runnable)}.</p>
 */
public class Button extends AbstractWidget {
    public static final int DEFAULT_WIDTH = 150;
    public static final int DEFAULT_HEIGHT = 20;

    private static final Identifier NORMAL_LEFT = Identifier.withDefaultNamespace("textures/gui/button/button_left");
    private static final Identifier NORMAL_MIDDLE = Identifier.withDefaultNamespace("textures/gui/button/button_middle");
    private static final Identifier NORMAL_RIGHT = Identifier.withDefaultNamespace("textures/gui/button/button_right");
    private static final Identifier DOWN_LEFT = Identifier.withDefaultNamespace("textures/gui/button/button_down_left");
    private static final Identifier DOWN_MIDDLE = Identifier.withDefaultNamespace("textures/gui/button/button_down_middle");
    private static final Identifier DOWN_RIGHT = Identifier.withDefaultNamespace("textures/gui/button/button_down_right");
    private static final Identifier SEED_CHOOSER =
            Identifier.withDefaultNamespace("textures/gui/screen/seeds/seed_chooser_button");
    private static final Identifier SEED_CHOOSER_DISABLED =
            Identifier.withDefaultNamespace("textures/gui/screen/seeds/seed_chooser_button_disabled");

    /**
     * The default style's label: green ink, bold, and nothing else.
     *
     * <p>No halo, by request. The shade is picked for contrast against this plate rather than for
     * its own sake: the stone texture's interior is a dark slate (RGB 74,76,109, relative luminance
     * 0.078), and the greens tried here measured 1.26:1 (#1C6B26, unreadable), 2.50:1 (#43A047)
     * and 3.89:1 (#6BC74A) against it. This is #4CAF50 at 2.97:1 - dark enough to still read as
     * green rather than as mint, light enough to carry without an edge over it.
     *
     * <p>Only for {@link Style#DEFAULT}: the wooden plates keep their plain white labels.
     */
    private static final float LABEL_R = 0.298F;
    private static final float LABEL_G = 0.686F;
    private static final float LABEL_B = 0.314F;
    private static final TextStyle LABEL_STYLE = TextStyle.bold();

    /**
     * How much of the plate's face a label may take; the rest is its margin inside the frame.
     *
     * <p>The plate arts do not hand their label the whole widget: the stone plate spends its top
     * eight pixels of forty-six on a highlight and its bottom eight on a shadow, so its face is
     * just under two thirds of the height. The face is what the eye reads as "where text goes", and
     * a label that reaches into the highlight looks like it is falling out of its button.
     *
     * <p>0.75 of the face is the tuned value: filling it (0.88 and up) reads as text bursting out
     * of the button rather than as a label on it, and much below 0.7 the buttons look empty. It is
     * one number, and it is the knob for "all button labels, a bit smaller".
     */
    static final float LABEL_SIZE_OF_FACE = 0.75F;
    /**
     * The stone plate's face, measured off {@code textures/gui/button/button_middle.png}: a 46px
     * tall tile with the face between rows 8 and 38, and a rounded end that eats about 10 of its
     * 46 columns at each side - the corners are curved, the middle of an end is flat, and a
     * centred label crosses that flat part.
     *
     * <p>What it must not do is subtract the whole caps. That was the first version, and on a
     * narrow button the caps add up to more than the button is wide: the speed button (44 units
     * wide, 48 tall) was left with a face one unit across, so its "1x" came out tiny. The corners
     * are what a label has to clear, and they are a fraction of the height, not the cap width.
     */
    private static final float STONE_FACE_OF_HEIGHT = 30F / NinePatch.BUTTON_NATIVE_HEIGHT;
    private static final float STONE_CORNER_OF_HEIGHT = 10F / NinePatch.BUTTON_NATIVE_HEIGHT;
    /**
     * The wooden plate is one stretched image rather than a nine-slice, so its frame is a fixed
     * fraction of it: rows 2..36 of its 42 carry the face, and 2 of its 156 columns at each side.
     */
    private static final float WOOD_FACE_OF_HEIGHT = 34F / 42F;
    private static final float WOOD_FACE_OF_WIDTH = 152F / 156F;
    /** Outside this band a fitted label stops looking like a deliberately sized one. */
    static final float MIN_LABEL_SCALE = 0.5F;
    static final float MAX_LABEL_SCALE = 2.4F;

    public enum Style {
        DEFAULT,
        SEED_CHOOSER
    }

    private static Runnable clickSoundHandler = () -> {
    };

    private String label;
    private final Runnable onPress;
    private float scale = 1F;
    private Style style = Style.DEFAULT;

    public Button(int x, int y, int width, int height, String label, Runnable onPress) {
        super(x, y, width, height);
        this.label = label;
        this.onPress = onPress;
    }

    public static Builder builder(String label, Runnable onPress) {
        return new Builder(label, onPress);
    }

    /**
     * A multiplier on the size the label works out for itself - 1 (the default) means "fill the
     * button", and this is only for the rare button that wants its text deliberately smaller or
     * larger than the frame suggests. It is not the size: {@link #fittedLabelScale} still clamps
     * the result, so a multiplier cannot push a label out of its own button.
     */
    public Button scale(float scale) {
        this.scale = scale;
        return this;
    }

    /**
     * Where a label's baseline goes: the middle of the button as the *ink* sees it, less the pixel
     * the plate sinks by when the button is hovered.
     *
     * <p>Two corrections in one place, because both are about putting text on a plate rather than
     * about any one screen. The centring uses the metrics' box - {@code ascent} above the baseline,
     * {@code descent} below - because a line box is symmetric and a hanzi is not: its ink sits
     * 0.76em up against 0.05em down, so centring the line box leaves every label low in its button.
     * The drop matches the art: {@code button_down_*} is the same plate one native pixel lower
     * inside its texture, which is what a hovered button draws, so the label sinks with it by the
     * same fraction of the height that the caps are scaled by.
     */
    static float labelBaseline(float y, float height, float ascent, float descent, boolean sunk) {
        float drop = sunk ? height / NinePatch.BUTTON_NATIVE_HEIGHT : 0F;
        return y + height / 2F - (ascent - descent) / 2F - drop;
    }

    /**
     * The scale a label wants in the face it is given: the largest that fits there, times the
     * caller's {@link #scale(float) multiplier}, clamped to a legible band.
     *
     * <p>Takes the *usable* area rather than the widget's, because that is the button's business
     * (see the face constants above); what is left is a rule about text. Pure - the two
     * measurements are what the role reports for this label at scale 1, in GUI units - so "a label
     * fits the space it was given, and a bigger space gets a bigger label" is checkable without a
     * renderer. Both matter: a tall button sizes by height, and a long label in a narrow one is
     * pulled back by its width, which is what keeps a nine-character editor button inside its
     * frame.
     */
    static float fittedLabelScale(float usableWidth, float usableHeight, float labelWidth,
                                  float lineHeight, float multiplier) {
        float byHeight = lineHeight <= 0F ? 1F
                : usableHeight * LABEL_SIZE_OF_FACE / lineHeight;
        float byWidth = labelWidth <= 0F ? byHeight
                : usableWidth * LABEL_SIZE_OF_FACE / labelWidth;
        float fitted = Math.min(byHeight, byWidth) * multiplier;
        return Math.max(MIN_LABEL_SCALE, Math.min(MAX_LABEL_SCALE, fitted));
    }

    public Button style(Style style) {
        this.style = style == null ? Style.DEFAULT : style;
        return this;
    }

    public Button setLabel(String label) {
        this.label = label;
        return this;
    }

    /** Installed once by {@code PvzceClient} so every pressed button clicks audibly. */
    public static void setClickSoundHandler(Runnable handler) {
        clickSoundHandler = handler == null ? () -> {
        } : handler;
    }

    @Override
    public void render(PvzceClient client) {
        if (!visible) {
            return;
        }
        // The label sizes itself to the face the plate leaves it rather than to a number each call
        // site guessed: one label is the same proportion of every button, whatever the screen laid
        // out, and the frame keeps its own margin.
        float faceHeight = height * (style == Style.SEED_CHOOSER
                ? WOOD_FACE_OF_HEIGHT : STONE_FACE_OF_HEIGHT);
        float faceWidth = style == Style.SEED_CHOOSER
                ? width * WOOD_FACE_OF_WIDTH : width - height * STONE_CORNER_OF_HEIGHT * 2F;
        float labelScale = fittedLabelScale(Math.max(1F, faceWidth), Math.max(1F, faceHeight),
                client.fonts().button().width(label, 1F),
                client.fonts().button().lineHeight(1F), scale);
        // Where it goes, and how a hover moves it: see labelBaseline. The label keeps its size when
        // the plate sinks - re-fitting the glyphs to a 4% smaller raster every time the cursor
        // crosses a button would cost a second set of atlas entries for a difference nobody sees.
        float ascent = client.fonts().button().ascent(labelScale);
        float descent = client.fonts().button().lineHeight(labelScale) - ascent;
        float labelY = labelBaseline(y, height, ascent, descent, active && hovered);
        if (style == Style.SEED_CHOOSER) {
            Identifier texture = active ? SEED_CHOOSER : SEED_CHOOSER_DISABLED;
            client.drawTexture(texture, x, y, width, height, 0F, 1F, 1F, 1F, 1F);
            if (active && hovered) {
                client.drawSolid(x, y, width, height, 0.05F, 1F, 1F, 1F, 0.14F);
            }
            float textWidth = client.fonts().button().width(label, labelScale);
            client.fonts().button().draw(label, x + (width - textWidth) / 2F, labelY,
                    labelScale, 1F, 1F, 1F, active ? 1F : 0.55F);
            return;
        }

        boolean down = active && hovered;
        Identifier left = down ? DOWN_LEFT : NORMAL_LEFT;
        Identifier middle = down ? DOWN_MIDDLE : NORMAL_MIDDLE;
        Identifier right = down ? DOWN_RIGHT : NORMAL_RIGHT;

        float shade = active ? 1F : 0.6F;
        float leftWidth = NinePatch.BUTTON_LEFT * height / NinePatch.BUTTON_NATIVE_HEIGHT;
        float rightWidth = NinePatch.BUTTON_RIGHT * height / NinePatch.BUTTON_NATIVE_HEIGHT;
        NinePatch.drawThreeSlice(client, left, middle, right, x, y, width, height, 0F,
                leftWidth, rightWidth, shade, shade, shade, active ? 1F : 0.85F);

        float textWidth = client.fonts().button().width(label, labelScale);
        client.fonts().button().draw(label, x + (width - textWidth) / 2F, labelY,
                labelScale, LABEL_R, LABEL_G, LABEL_B, active ? 1F : 0.6F, LABEL_STYLE);
    }

    @Override
    public boolean mouseClicked(double mouseX, double guiY, int button) {
        if (super.mouseClicked(mouseX, guiY, button) && button == 0) {
            if (onPress != null) {
                clickSoundHandler.run();
                onPress.run();
            }
            return true;
        }
        return false;
    }

    public String label() {
        return label;
    }

    /** Fluent constructor helper mirroring {@code Button.Builder} in MC. */
    public static final class Builder {
        private final String label;
        private final Runnable onPress;
        private int x;
        private int y;
        private int width = DEFAULT_WIDTH;
        private int height = DEFAULT_HEIGHT;
        private float scale = 1F;
        private Style style = Style.DEFAULT;

        private Builder(String label, Runnable onPress) {
            this.label = label;
            this.onPress = onPress;
        }

        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder height(int height) {
            this.height = height;
            return this;
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder bounds(int x, int y, int width, int height) {
            return pos(x, y).size(width, height);
        }

        public Builder scale(float scale) {
            this.scale = scale;
            return this;
        }

        public Builder style(Style style) {
            this.style = style == null ? Style.DEFAULT : style;
            return this;
        }

        public Button build() {
            return new Button(x, y, width, height, label, onPress).scale(scale).style(style);
        }
    }
}
