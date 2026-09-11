package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public record ShortTag(short value) implements Tag.NumericTag {
    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public byte getId() {
        return SHORT;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeShort(value);
    }
}
