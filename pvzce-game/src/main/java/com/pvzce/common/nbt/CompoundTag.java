package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CompoundTag implements Tag {
    private final Map<String, Tag> tags = new LinkedHashMap<>();

    public boolean contains(String key) {
        return tags.containsKey(key);
    }

    /**
     * Stores a tag. Null keys and values are rejected here rather than at write
     * time: {@code put(key, null)} used to be accepted and then threw an NPE from
     * inside the save, after the rest of the file had already been written.
     */
    public void put(String key, Tag tag) {
        if (key == null) {
            throw new IllegalArgumentException("NBT key must not be null");
        }
        if (tag == null) {
            throw new IllegalArgumentException("NBT value for '" + key + "' must not be null");
        }
        tags.put(key, tag);
    }

    public void putString(String key, String value) {
        put(key, new StringTag(value));
    }

    public void putInt(String key, int value) {
        put(key, new IntTag(value));
    }

    public void putLong(String key, long value) {
        put(key, new LongTag(value));
    }

    public void putFloat(String key, float value) {
        put(key, new FloatTag(value));
    }

    public void putDouble(String key, double value) {
        put(key, new DoubleTag(value));
    }

    public void putByte(String key, byte value) {
        put(key, new ByteTag(value));
    }

    public String getString(String key) {
        Tag tag = tags.get(key);
        return tag instanceof StringTag s ? s.value() : "";
    }

    public int getInt(String key) {
        return (int) number(key, 0D);
    }

    public long getLong(String key) {
        return (long) number(key, 0D);
    }

    public float getFloat(String key) {
        return (float) number(key, 0D);
    }

    public double getDouble(String key) {
        return number(key, 0D);
    }

    /**
     * Reads any numeric tag as a double, mirroring vanilla's lenient numeric
     * getters. Reading strictly by tag class meant a value written as an int and
     * later read as a float silently produced the default instead of the value -
     * the classic way a save "loses" a field without any error.
     */
    private double number(String key, double fallback) {
        Tag tag = tags.get(key);
        if (tag instanceof NumericTag numeric) {
            return numeric.doubleValue();
        }
        return fallback;
    }

    /** The nested compound at {@code key}, or a fresh empty one when absent. */
    public CompoundTag getCompound(String key) {
        Tag tag = tags.get(key);
        return tag instanceof CompoundTag c ? c : new CompoundTag();
    }

    public ListTag getList(String key) {
        Tag tag = tags.get(key);
        return tag instanceof ListTag l ? l : new ListTag();
    }

    public Map<String, Tag> entries() {
        return tags;
    }

    @Override
    public byte getId() {
        return COMPOUND;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        for (Map.Entry<String, Tag> entry : tags.entrySet()) {
            output.writeByte(entry.getValue().getId());
            output.writeUTF(entry.getKey());
            entry.getValue().write(output);
        }
        output.writeByte(END);
    }
}
