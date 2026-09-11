package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public record IntTag(int value) implements Tag.NumericTag {
    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public byte getId() {
        return INT;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeInt(value);
    }
}
