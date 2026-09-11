package com.pvzce.client.gui.components;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.FocusManager;
import com.pvzce.client.renderer.SpriteRenderer;
import org.lwjgl.glfw.GLFW;

/**
 * A one-line text input field (world creation, level save-as, ...).
 *
 * <p>Focus belongs to the owning screen's {@link FocusManager} when one is wired
 * in, so clicking a different field moves the keyboard instead of leaving two
 * fields focused (and every keystroke going to whichever one dispatched first).
 */
public class EditBox extends AbstractWidget {
    private final Runnable onEnter;
    private final int maxLength;
    private String value = "";
    private boolean focused;
    private boolean bordered = true;
    private Runnable onValueChanged;
    private FocusManager focusManager;

    public EditBox(int x, int y, int width, int height, Runnable onEnter) {
        this(x, y, width, height, 32, onEnter);
    }

    public EditBox(int x, int y, int width, int height, int maxLength, Runnable onEnter) {
        super(x, y, width, height);
        this.maxLength = maxLength;
        this.onEnter = onEnter;
    }

    /** Connects this box to the screen's single focus owner. */
    public void setFocusManager(FocusManager focusManager) {
        this.focusManager = focusManager;
    }

    @Override
    public void render(PvzceClient client) {
        if (!visible) {
            return;
        }
        renderBackground(client, 0.05F, 0.05F, 0.05F, bordered ? 0.85F : 0.35F);
        if (bordered) {
            SpriteRenderer.solid(x, y, width, 2, 0, focused ? 0.9F : 0.4F, focused ? 0.9F : 0.4F,
                    focused ? 0.5F : 0.4F, 1F);
        }
        client.font().draw(value, x + 6, y + (height - client.font().lineHeight(0.9F)) / 2F, 0.9F,
                1F, 1F, 1F, 1F);
        if (focused && (System.nanoTime() / 500_000_000L) % 2 == 0) {
            float cursorX = x + 6 + client.font().width(value, 0.9F);
            SpriteRenderer.solid(cursorX, y + 8, 2, height - 16, 0, 1F, 1F, 1F, 0.9F);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double guiY, int button) {
        if (!isMouseOver(mouseX, guiY)) {
            return false;
        }
        if (focusManager != null) {
            focusManager.request(this);
        } else {
            focused = true;
        }
        return true;
    }

    @Override
    public boolean charTyped(char codepoint) {
        if (!focused || !visible || !active) {
            return false;
        }
        if (codepoint >= 32 && value.length() < maxLength) {
            value += codepoint;
            notifyValueChanged();
        }
        // Always consume while focused: an unhandled char must not fall through to
        // another widget's handler.
        return true;
    }

    @Override
    public boolean keyPressed(int key) {
        if (!focused || !visible || !active) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!value.isEmpty()) {
                value = value.substring(0, value.length() - 1);
                notifyValueChanged();
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (onEnter != null) {
                onEnter.run();
            }
            return true;
        }
        return false;
    }

    public String value() {
        return value;
    }

    public void setValue(String value) {
        setValue(value, true);
    }

    /** Programmatic updates (suggestion fill, history) may opt out of the change callback. */
    public void setValue(String value, boolean notify) {
        this.value = value == null ? "" : value;
        if (notify) {
            notifyValueChanged();
        }
    }

    public void setValueChangedListener(Runnable onValueChanged) {
        this.onValueChanged = onValueChanged;
    }

    private void notifyValueChanged() {
        if (onValueChanged != null) {
            onValueChanged.run();
        }
    }

    public boolean isFocused() {
        return focusManager != null ? focusManager.isFocused(this) : focused;
    }

    /** Legacy entry point; routed through the manager when one is attached. */
    public void setFocused(boolean focused) {
        if (focusManager != null) {
            if (focused) {
                focusManager.request(this);
            } else if (focusManager.isFocused(this)) {
                focusManager.clear();
            }
            return;
        }
        this.focused = focused;
    }

    /** Called only by the {@link FocusManager}. */
    public void applyFocus(boolean focused) {
        this.focused = focused;
    }

    public void setBordered(boolean bordered) {
        this.bordered = bordered;
    }
}
