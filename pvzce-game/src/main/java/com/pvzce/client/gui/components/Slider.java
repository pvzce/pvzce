package com.pvzce.client.gui.components;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.SpriteRenderer;

import java.util.function.Consumer;

/**
 * Simple horizontal slider used by the config screen.
 *
 * <p>Two behaviours matter for anything that persists its value:
 * {@link #onChange} fires only when the value actually changes, and
 * {@link #onCommit} fires once when the drag ends. The slider used to call its
 * change callback on every mouse-move and even when a clamped value was unchanged,
 * and the config screen wrote the whole config file from that callback - so
 * dragging a slider rewrote the file every frame, and a scroll wheel at the limit
 * rewrote it for nothing.
 */
public class Slider extends AbstractWidget {
    /** Not final: {@link #configure} retargets one slider at another rule. */
    private float min;
    private float max;
    private Consumer<Slider> onChange;
    private float value;
    private boolean dragging;
    private Runnable onCommit;

    public Slider(int x, int y, int width, int height, float min, float max, float value,
                  Consumer<Slider> onChange) {
        super(x, y, width, height);
        this.min = min;
        this.max = max;
        this.value = Math.max(min, Math.min(max, value));
        this.onChange = onChange;
    }

    /** Runs once when a drag or a wheel adjustment finishes. */
    public Slider onCommit(Runnable onCommit) {
        this.onCommit = onCommit;
        return this;
    }

    /**
     * Retargets the slider at a different range and value in one call.
     *
     * <p>The editor's rule page points one slider at whichever rule is selected.
     * Setting range and value separately fires the change callback while only half
     * the bounds are updated, which writes a clamped value for the rule that was
     * selected a moment ago.
     */
    public Slider configure(float min, float max, float value, Consumer<Slider> onChange) {
        this.min = min;
        this.max = max;
        this.value = Math.max(min, Math.min(max, value));
        this.onChange = onChange;
        return this;
    }

    @Override
    public void render(PvzceClient client) {
        if (!visible) {
            return;
        }
        renderBackground(client, 0.12F, 0.12F, 0.12F, 0.9F);
        float ratio = (value - min) / Math.max(0.0001F, max - min);
        float fill = width * ratio;
        SpriteRenderer.solid(x, y + height / 2F - 3, fill, 6, 0.1F, 0.35F, 0.6F, 0.3F, 1F);
        float thumb = 10;
        // Clamped so the thumb never leaves the track at either extreme.
        float thumbX = Math.max(x, Math.min(x + width - thumb, x + fill - thumb / 2F));
        SpriteRenderer.solid(thumbX, y, thumb, height, 0.2F, 0.9F, 0.9F, 0.9F, 1F);
    }

    @Override
    public boolean mouseClicked(double mouseX, double guiY, int button) {
        if (!isMouseOver(mouseX, guiY)) {
            return false;
        }
        if (button == 0) {
            dragging = true;
            setFromMouse(mouseX);
        }
        return true;
    }

    @Override
    public void mouseDragged(double mouseX, double guiY, int button) {
        if (dragging && button == 0) {
            setFromMouse(mouseX);
        }
    }

    @Override
    public void mouseReleased(double mouseX, double guiY, int button) {
        if (dragging) {
            dragging = false;
            commit();
        }
    }

    @Override
    public void mouseScrolled(double mouseX, double guiY, double amount) {
        if (isMouseOver(mouseX, guiY)) {
            setValue(value + (float) amount * 0.05F);
            commit();
        }
    }

    private void setFromMouse(double mouseX) {
        float ratio = Math.max(0F, Math.min(1F, (float) ((mouseX - x) / (double) width)));
        setValue(min + ratio * (max - min));
    }

    public float value() {
        return value;
    }

    public void setValue(float value) {
        float clamped = Math.max(min, Math.min(max, value));
        if (clamped == this.value) {
            return;
        }
        this.value = clamped;
        if (onChange != null) {
            onChange.accept(this);
        }
    }

    private void commit() {
        if (onCommit != null) {
            onCommit.run();
        }
    }

    public float min() {
        return min;
    }

    public float max() {
        return max;
    }
}
