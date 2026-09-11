package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public record FloatTag(float value) implements Tag.NumericTag {
    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public byte getId() {
        return FLOAT;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeFloat(value);
    }
}
