package com.pvzce.client.gui.components;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;

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

    public Button scale(float scale) {
        this.scale = scale;
        return this;
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
        if (style == Style.SEED_CHOOSER) {
            Identifier texture = active ? SEED_CHOOSER : SEED_CHOOSER_DISABLED;
            client.drawTexture(texture, x, y, width, height, 0F, 1F, 1F, 1F, 1F);
            if (active && hovered) {
                client.drawSolid(x, y, width, height, 0.05F, 1F, 1F, 1F, 0.14F);
            }
            float textWidth = client.fonts().button().width(label, scale);
            client.fonts().button().draw(label, x + (width - textWidth) / 2F,
                    y + (height - client.fonts().button().lineHeight(scale)) / 2F,
                    scale, 1F, 1F, 1F, active ? 1F : 0.55F);
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

        float textWidth = client.fonts().button().width(label, scale);
        client.fonts().button().draw(label, x + (width - textWidth) / 2F,
                y + (height - client.fonts().button().lineHeight(scale)) / 2F,
                scale, 1F, 1F, 1F, active ? 1F : 0.6F);
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
