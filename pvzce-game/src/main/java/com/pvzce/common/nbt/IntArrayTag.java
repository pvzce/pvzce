package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public record IntArrayTag(int[] values) implements Tag {
    @Override
    public byte getId() {
        return INT_ARRAY;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeInt(values.length);
        for (int value : values) {
            output.writeInt(value);
        }
    }
}
