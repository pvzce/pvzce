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
    private int cursor;
    private int anchor;
    private float scrollX;
    private float[] advances = {0F};
    public enum Style { DEFAULT, PARCHMENT }
    private Style style = Style.DEFAULT;

    public EditBox style(Style style) { this.style = style; return this; }

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
        boolean paper = style == Style.PARCHMENT;
        if (paper) renderBackground(client, .94F, .84F, .63F, 1F);
        else renderBackground(client, 0.05F, 0.05F, 0.05F, bordered ? 0.85F : 0.35F);
        if (bordered) {
            if (paper) SpriteRenderer.solid(x, y, width, 2, 0, .55F, focused ? .32F : .42F, .12F, 1F);
            else SpriteRenderer.solid(x, y, width, 2, 0, focused ? 0.9F : 0.4F, focused ? 0.9F : 0.4F,
                    focused ? 0.5F : 0.4F, 1F);
        }
        var font = client.fonts().body();
        advances = new float[value.length() + 1];
        for (int i = 0; i < value.length();) {
            int chars = Character.charCount(value.codePointAt(i));
            if (chars == 2) advances[i + 1] = advances[i];
            advances[i + chars] = advances[i] + font.width(value.substring(i, i + chars), .9F);
            i += chars;
        }
        float available = Math.max(1F, width - 14F);
        scrollX = Math.max(Math.max(0F, advances[cursor] - available), Math.min(scrollX, advances[cursor]));
        scrollX = Math.min(scrollX, Math.max(0F, advances[value.length()] - available));
        float textX = x + 6F - scrollX;
        float textY = y + (height - font.lineHeight(.9F)) / 2F;
        client.clipping().push(x + 4, y + 2, width - 8, height - 4);
        try {
            if (focused && cursor != anchor) {
                float start = textX + advances[Math.min(cursor, anchor)];
                float end = textX + advances[Math.max(cursor, anchor)];
                SpriteRenderer.solid(start, textY, end - start, font.lineHeight(.9F), 0, .25F, .48F, .8F, .65F);
            }
            font.draw(value, textX, textY, .9F,
                    paper ? .25F : 1F, paper ? .15F : 1F, paper ? .06F : 1F, 1F);
            if (focused && (System.nanoTime() / 500_000_000L) % 2 == 0) {
                SpriteRenderer.solid(textX + advances[cursor], textY, 1, font.lineHeight(.9F), 0,
                        paper ? .25F : 1F, paper ? .15F : 1F, paper ? .06F : 1F, .9F);
            }
        } finally {
            client.clipping().pop();
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
        cursor = positionAt(mouseX);
        anchor = cursor;
        return true;
    }

    @Override
    public boolean claimsDrag() { return true; }

    @Override
    public void mouseDragged(double mouseX, double guiY, int button) {
        if (focused && button == 0) cursor = positionAt(mouseX);
    }

    private int positionAt(double mouseX) {
        if (advances.length != value.length() + 1) return value.length();
        float local = (float) mouseX - x - 6F + scrollX;
        for (int i = 0; i < value.length();) {
            int next = value.offsetByCodePoints(i, 1);
            if (local < (advances[i] + advances[next]) / 2F) return i;
            i = next;
        }
        return value.length();
    }

    @Override
    public boolean charTyped(char codepoint) {
        if (!focused || !visible || !active) {
            return false;
        }
        if (codepoint >= 32 && codepoint != 127) appendText(String.valueOf(codepoint));
        // Always consume while focused: an unhandled char must not fall through to
        // another widget's handler.
        return true;
    }

    @Override
    public boolean keyPressed(int key) {
        return keyPressed(key, false, false);
    }

    /** Modifiers come from the window at dispatch; the editing model needs no native window. */
    public boolean keyPressed(int key, boolean control, boolean shift) {
        if (!focused || !visible || !active) return false;
        if (control && key == GLFW.GLFW_KEY_A) {
            anchor = 0;
            cursor = value.length();
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
                || key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END) {
            int next;
            if (key == GLFW.GLFW_KEY_HOME) next = 0;
            else if (key == GLFW.GLFW_KEY_END) next = value.length();
            else if (!shift && cursor != anchor) next = key == GLFW.GLFW_KEY_LEFT
                    ? Math.min(cursor, anchor) : Math.max(cursor, anchor);
            else next = control ? wordBoundary(key == GLFW.GLFW_KEY_LEFT ? -1 : 1)
                    : Character.offsetByCodePoints(value, cursor,
                            key == GLFW.GLFW_KEY_LEFT ? (cursor > 0 ? -1 : 0) : (cursor < value.length() ? 1 : 0));
            cursor = next;
            if (!shift) anchor = cursor;
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
            if (cursor == anchor) {
                boolean back = key == GLFW.GLFW_KEY_BACKSPACE;
                anchor = control ? wordBoundary(back ? -1 : 1)
                        : Character.offsetByCodePoints(value, cursor,
                                back ? (cursor > 0 ? -1 : 0) : (cursor < value.length() ? 1 : 0));
            }
            replaceSelection("");
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (onEnter != null) onEnter.run();
            return true;
        }
        return false;
    }

    private int wordBoundary(int direction) {
        int pos = cursor;
        if (direction < 0) {
            while (pos > 0 && Character.isWhitespace(value.charAt(pos - 1))) pos--;
            while (pos > 0 && !Character.isWhitespace(value.charAt(pos - 1))) pos--;
        } else {
            while (pos < value.length() && !Character.isWhitespace(value.charAt(pos))) pos++;
            while (pos < value.length() && Character.isWhitespace(value.charAt(pos))) pos++;
        }
        return pos;
    }

    public String selectedText() { return value.substring(Math.min(cursor, anchor), Math.max(cursor, anchor)); }

    public void cutSelection() { replaceSelection(""); }

    private boolean replaceSelection(String replacement) {
        int start = Math.min(cursor, anchor), end = Math.max(cursor, anchor);
        String changed = value.substring(0, start) + replacement + value.substring(end);
        cursor = anchor = start + replacement.length();
        if (changed.equals(value)) return false;
        value = changed;
        notifyValueChanged();
        return true;
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
        cursor = anchor = this.value.length();
        if (notify) {
            notifyValueChanged();
        }
    }

    /**
     * Inserts pasted text at the cursor, replacing the selection up to the field's limit.
     *
     * <p>Control characters are dropped rather than inserted: a paste from a terminal or a web page
     * regularly carries a trailing newline, and a one-line field that shows nothing for it would
     * look like the paste had half failed. Everything past {@link #maxLength} is dropped for the
     * same reason a typed character there is: the field has one length, and it is the one it draws.
     *
     * @return true when at least one character was taken
     */
    public boolean appendText(String text) {
        if (text == null || text.isEmpty() || !active || !visible) {
            return false;
        }
        StringBuilder clean = new StringBuilder();
        int available = maxLength - value.length() + Math.abs(cursor - anchor);
        for (int i = 0; i < text.length();) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (codepoint < 32 || codepoint == 127) continue;
            if (clean.length() + Character.charCount(codepoint) > available) break;
            clean.appendCodePoint(codepoint);
        }
        return !clean.isEmpty() && replaceSelection(clean.toString());
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
