package com.pvzce.client.gui.config;

/** Float slider entry. */
public final class FloatConfigEntry implements ConfigEntry<Float> {
    private final String id;
    private final String label;
    private final float min;
    private final float max;
    private final Runnable saveConsumer;
    private float value;

    public FloatConfigEntry(String id, String label, float value, float min, float max, Runnable saveConsumer) {
        this.id = id;
        this.label = label;
        this.min = min;
        this.max = max;
        this.saveConsumer = saveConsumer;
        this.value = Math.max(min, Math.min(max, value));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Float value() {
        return value;
    }

    @Override
    public void setValue(Float value) {
        this.value = Math.max(min, Math.min(max, value));
        if (saveConsumer != null) {
            saveConsumer.run();
        }
    }

    public float min() {
        return min;
    }

    public float max() {
        return max;
    }
}
