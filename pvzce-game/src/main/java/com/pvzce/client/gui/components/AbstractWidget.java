package com.pvzce.client.gui.components;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.input.ScrollRegion;
import com.pvzce.client.renderer.SpriteRenderer;

import java.util.List;

/** Minimal MC-style widget: bounds + visible/active/hovered state. */
public abstract class AbstractWidget {
    protected int x;
    protected int y;
    protected int width;
    protected int height;
    protected boolean visible = true;
    protected boolean active = true;
    protected boolean hovered;

    protected AbstractWidget(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public abstract void render(PvzceClient client);

    public boolean isMouseOver(double mouseX, double guiY) {
        return visible && active
                && mouseX >= x && mouseX < x + width
                && guiY >= y && guiY < y + height;
    }

    public boolean mouseClicked(double mouseX, double guiY, int button) {
        hovered = isMouseOver(mouseX, guiY);
        return hovered;
    }

    /** Called every frame with the current cursor position (MC-style hover tracking). */
    public void mouseMoved(double mouseX, double guiY) {
        hovered = isMouseOver(mouseX, guiY);
    }

    public boolean keyPressed(int key) {
        return false;
    }

    public boolean charTyped(char codepoint) {
        return false;
    }

    public void mouseScrolled(double mouseX, double guiY, double amount) {
    }

    public void mouseDragged(double mouseX, double guiY, int button) {
    }

    public void mouseReleased(double mouseX, double guiY, int button) {
    }

    /**
     * The scroll region a press at this point would drive, or {@code null} for "not scrollable".
     *
     * <p>Asked by the touch gesture layer before it lets a press become a swipe
     * ({@code com.pvzce.client.input.PointerGesture}): a widget that scrolls answers here, and a
     * screen that scrolls its own drawing answers {@code Screen.onScrollRegionAt} instead.
     * Implementations must return {@code null} outside their own bounds - the caller only checks
     * {@link #isMouseOver}, it does not know how a subclass clips itself.
     */
    public ScrollRegion scrollRegionAt(double mouseX, double guiY) {
        return null;
    }

    /**
     * True when this widget needs press, drag and release for itself, so a press on it must never
     * be reinterpreted as a swipe.
     *
     * <p>Only {@link Slider} says yes today. Without the rule, a slider drawn over a scrolling list
     * would be dead on a touchscreen: its press would become a scroll candidate, its drags would be
     * eaten as scrolling and its release would never commit the value.
     */
    public boolean claimsDrag() {
        return false;
    }

    /**
     * True when any of {@code widgets} under the point claims the drag for itself.
     *
     * <p>Checked <em>before</em> any region, by both callers, because a widget that needs
     * press-drag-release (a {@link Slider}) must win over a region it happens to be drawn on top of.
     */
    public static boolean claimsDragAt(List<AbstractWidget> widgets, double mouseX, double guiY) {
        for (AbstractWidget widget : widgets) {
            if (widget.isMouseOver(mouseX, guiY) && widget.claimsDrag()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first scroll region among {@code widgets} under the point, or {@code null}.
     *
     * <p>Lives here rather than in each caller because the two callers are a screen and a dialog, and
     * "which widget is under this point" has to give the same answer to both. The scan follows the
     * widget list's own order - the same order {@code Screen.mouseClicked} offers the click in.
     */
    public static ScrollRegion regionAt(List<AbstractWidget> widgets, double mouseX, double guiY) {
        for (AbstractWidget widget : widgets) {
            if (widget.isMouseOver(mouseX, guiY)) {
                ScrollRegion region = widget.scrollRegionAt(mouseX, guiY);
                if (region != null) {
                    return region;
                }
            }
        }
        return null;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean isHovered() {
        return hovered;
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isActive() {
        return active;
    }

    protected void renderBackground(PvzceClient client, float r, float g, float b, float a) {
        SpriteRenderer.solid(x, y, width, height, 0, r, g, b, a);
    }
}
