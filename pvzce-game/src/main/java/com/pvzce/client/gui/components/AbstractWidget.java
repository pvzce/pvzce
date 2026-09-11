package com.pvzce.client.gui.components;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.SpriteRenderer;

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
