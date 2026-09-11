package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public record ByteTag(byte value) implements Tag.NumericTag {
    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public byte getId() {
        return BYTE;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeByte(value);
    }
}
