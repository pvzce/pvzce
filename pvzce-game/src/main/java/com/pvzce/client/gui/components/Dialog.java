package com.pvzce.client.gui.components;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable MC-style modal dialog component.
 *
 * <p>A dialog is an ordinary widget that owns its own child widgets: it draws
 * a nine-sliced textured frame (from {@code assets/pvzce/textures/gui/dialog}),
 * an optional dimmed backdrop, an optional title plate drawn above the frame,
 * and then delegates mouse/key events to its children. Screens can show it
 * in-place instead of pushing a full replacement screen.</p>
 */
public class Dialog extends AbstractWidget {
    public static final int DEFAULT_WIDTH = 420;
    public static final int DEFAULT_HEIGHT = 320;

    private static final Identifier HEADER = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_header");
    private static final Identifier TOP_LEFT = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_topleft");
    private static final Identifier TOP = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_topmiddle");
    private static final Identifier TOP_RIGHT = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_topright");
    private static final Identifier CENTER_LEFT = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_centerleft");
    private static final Identifier CENTER = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_centermiddle");
    private static final Identifier CENTER_RIGHT = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_centerright");
    private static final Identifier BOTTOM_LEFT = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_bottomleft");
    private static final Identifier BOTTOM = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_bottommiddle");
    private static final Identifier BOTTOM_RIGHT = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_bottomright");
    private static final Identifier BIG_BOTTOM_LEFT = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_bigbottomleft");
    private static final Identifier BIG_BOTTOM = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_bigbottommiddle");
    private static final Identifier BIG_BOTTOM_RIGHT = Identifier.withDefaultNamespace("textures/gui/dialog/dialog_bigbottomright");

    private String title;
    private final List<AbstractWidget> children = new ArrayList<>();
    private Runnable onClose;
    private float titleScale = 1.2F;
    private boolean modal = true;
    private boolean closeOnEscape = true;
    private boolean renderBackdrop = true;
    private boolean showTitlePlate = true;
    /** True when the title is drawn inside the frame rather than on the plate. */
    private boolean inlineTitle;
    private boolean bigBottom;
    /**
     * How much of the frame's own border to draw, as a fraction.
     *
     * <p>The stone frame is authored at roughly 209 texture pixels of border for a 213-wide piece
     * of art: on a small dialog that is most of the dialog, and a few rows of content inside it
     * have nowhere to go. One is the frame as drawn, smaller values are the same carving at a
     * thinner border - the slices keep their shape, so it reads as the same frame rather than as
     * a different widget.
     */
    private float frameScale = 1F;

    public Dialog(int x, int y, int width, int height, String title) {
        super(x, y, width, height);
        this.title = title;
    }

    /** Draws the frame with {@code scale} of its native border thickness. See {@link #frameScale}. */
    public Dialog frameScale(float frameScale) {
        this.frameScale = Math.max(0.1F, Math.min(1F, frameScale));
        return this;
    }

    /** The frame's border thickness after {@link #frameScale}, for a subclass's layout. */
    public float frameInset() {
        return Math.max(8F, NinePatch.DIALOG_TOP * frameScale);
    }

    /**
     * Draws the title as text inside the frame instead of on the hanging wooden plate.
     *
     * <p>The plate is drawn over the frame's top border and is about 38 GUI units tall at the
     * sizes this game runs at, so a dialog that fills its frame with content has no room left
     * under it - the plate would be behind the first row. A plain title is smaller and stays
     * inside, which is what a content-dense dialog wants.
     */
    public Dialog inlineTitle() {
        this.showTitlePlate = false;
        this.inlineTitle = true;
        return this;
    }

    /** The title's size, for a subclass that has to leave room for it. */
    public float titleScale() {
        return titleScale;
    }

    public Dialog title(String title) {
        this.title = title;
        return this;
    }

    public Dialog titleScale(float titleScale) {
        this.titleScale = titleScale;
        return this;
    }

    public Dialog modal(boolean modal) {
        this.modal = modal;
        return this;
    }

    public Dialog closeOnEscape(boolean closeOnEscape) {
        this.closeOnEscape = closeOnEscape;
        return this;
    }

    public Dialog backdrop(boolean backdrop) {
        this.renderBackdrop = backdrop;
        return this;
    }

    public Dialog titlePlate(boolean titlePlate) {
        this.showTitlePlate = titlePlate;
        return this;
    }

    /** Uses the taller bottom corners ({@code dialog_bigbottom*}) for large dialogs. */
    public Dialog bigBottom(boolean bigBottom) {
        this.bigBottom = bigBottom;
        return this;
    }

    public Dialog onClose(Runnable onClose) {
        this.onClose = onClose;
        return this;
    }

    public Dialog addChild(AbstractWidget widget) {
        children.add(widget);
        return this;
    }

    public Dialog addButton(Button button) {
        return addChild(button);
    }

    public Dialog clearChildren() {
        children.clear();
        return this;
    }

    /**
     * Removes one child, for a dialog that rebuilds part of itself.
     *
     * <p>{@link #children()} hands out an immutable copy, so a caller cannot edit the list
     * it reads; a dialog whose second row depends on its first (a category list that
     * follows the chosen theme) has to be able to drop the old row's widgets.
     */
    public Dialog removeChild(AbstractWidget widget) {
        children.remove(widget);
        return this;
    }

    public List<AbstractWidget> children() {
        return List.copyOf(children);
    }

    public String title() {
        return title;
    }

    public boolean isModal() {
        return modal;
    }

    /** Hides the dialog and runs the optional close callback. */
    public void close() {
        setVisible(false);
        if (onClose != null) {
            onClose.run();
        }
    }

    /**
     * Re-lays out the dialog for a new window size. The base implementation keeps
     * the dialog on screen and shifts its children with it; dialogs that compute
     * their geometry from the window size override this.
     */
    public void onResize(int guiWidth, int guiHeight) {
        // A dialog laid out for a large window would otherwise stay half off-screen
        // after the window shrinks.
        int maxX = Math.max(0, guiWidth - width);
        int maxY = Math.max(0, guiHeight - height);
        setPosition(Math.min(Math.max(0, x), maxX), Math.min(Math.max(0, y), maxY));
    }

    @Override
    public void setPosition(int x, int y) {
        int deltaX = x - this.x;
        int deltaY = y - this.y;
        super.setPosition(x, y);
        for (AbstractWidget child : children) {
            child.setPosition(child.x() + deltaX, child.y() + deltaY);
        }
    }

    @Override
    public void render(PvzceClient client) {
        if (!visible) {
            return;
        }
        if (renderBackdrop) {
            client.drawSolid(0, 0, client.guiWidth(), client.guiHeight(), -0.5F,
                    0F, 0F, 0F, 0.55F);
        }
        renderFrame(client);
        if (showTitlePlate) {
            renderTitlePlate(client);
        } else if (inlineTitle) {
            renderInlineTitle(client);
        }
        for (AbstractWidget child : children) {
            child.render(client);
        }
    }

    private void renderFrame(PvzceClient client) {
        Identifier bottomLeft = bigBottom ? BIG_BOTTOM_LEFT : BOTTOM_LEFT;
        Identifier bottom = bigBottom ? BIG_BOTTOM : BOTTOM;
        Identifier bottomRight = bigBottom ? BIG_BOTTOM_RIGHT : BOTTOM_RIGHT;
        float bottomHeight = bigBottom ? NinePatch.DIALOG_BIG_BOTTOM : NinePatch.DIALOG_BOTTOM;
        NinePatch.drawNineSlice(client,
                TOP_LEFT, TOP, TOP_RIGHT,
                CENTER_LEFT, CENTER, CENTER_RIGHT,
                bottomLeft, bottom, bottomRight,
                x, y, width, height, 0F,
                NinePatch.DIALOG_LEFT * frameScale, NinePatch.DIALOG_RIGHT * frameScale,
                NinePatch.DIALOG_TOP * frameScale, bottomHeight * frameScale,
                1F, 1F, 1F, 1F);
    }

    private void renderTitlePlate(PvzceClient client) {
        if (title == null || title.isEmpty()) {
            return;
        }
        float topHeight = NinePatch.DIALOG_TOP;
        float plateWidth = Math.min(width * 0.62F, 280F);
        float plateHeight = plateWidth * 64F / 187F;
        float plateX = x + (width - plateWidth) / 2F;
        // The title plate sits on top of the frame instead of being drawn over
        // the frame's top border. If the dialog is too close to the window
        // top, fall back to the in-frame placement so the title stays visible.
        float plateY = y + height;
        if (plateY + plateHeight > client.guiHeight()) {
            plateY = y + height - topHeight + (topHeight - plateHeight) / 2F;
        }
        client.drawTexture(HEADER, plateX, plateY, plateWidth, plateHeight, 0.1F, 1F, 1F, 1F, 1F);

        float textWidth = client.fonts().button().width(title, titleScale);
        float textX = x + (width - textWidth) / 2F;
        float textY = plateY + (plateHeight - client.fonts().button().lineHeight(titleScale)) / 2F;
        client.fonts().button().draw(title, textX, textY, titleScale, 1F, 1F, 1F, 1F);
    }

    /** The title as one line of text just under the frame's top border. */
    private void renderInlineTitle(PvzceClient client) {
        if (title == null || title.isEmpty()) {
            return;
        }
        float textY = y + height - NinePatch.DIALOG_TOP * frameScale + 4F;
        client.fonts().button().draw(title, x + NinePatch.DIALOG_LEFT * frameScale,
                textY, titleScale, 1F, 0.95F, 0.6F, 1F);
    }

    @Override
    public boolean mouseClicked(double mouseX, double guiY, int button) {
        if (!visible) {
            return false;
        }
        for (AbstractWidget child : children) {
            if (child.isMouseOver(mouseX, guiY) && child.mouseClicked(mouseX, guiY, button)) {
                return true;
            }
        }
        return isMouseOver(mouseX, guiY) || modal;
    }

    @Override
    public void mouseMoved(double mouseX, double guiY) {
        super.mouseMoved(mouseX, guiY);
        for (AbstractWidget child : children) {
            child.mouseMoved(mouseX, guiY);
        }
    }

    @Override
    public void mouseReleased(double mouseX, double guiY, int button) {
        for (AbstractWidget child : children) {
            child.mouseReleased(mouseX, guiY, button);
        }
    }

    @Override
    public void mouseDragged(double mouseX, double guiY, int button) {
        for (AbstractWidget child : children) {
            child.mouseDragged(mouseX, guiY, button);
        }
    }

    @Override
    public void mouseScrolled(double mouseX, double guiY, double amount) {
        for (AbstractWidget child : children) {
            if (child.isMouseOver(mouseX, guiY)) {
                child.mouseScrolled(mouseX, guiY, amount);
            }
        }
    }

    @Override
    public boolean keyPressed(int key) {
        if (visible && key == GLFW.GLFW_KEY_ESCAPE && closeOnEscape) {
            close();
            return true;
        }
        for (AbstractWidget child : children) {
            if (child.keyPressed(key)) {
                return true;
            }
        }
        return visible && modal;
    }

    @Override
    public boolean charTyped(char codepoint) {
        for (AbstractWidget child : children) {
            if (child.charTyped(codepoint)) {
                return true;
            }
        }
        return visible && modal;
    }
}
