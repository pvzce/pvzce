package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public record LongArrayTag(long[] values) implements Tag {
    @Override
    public byte getId() {
        return LONG_ARRAY;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeInt(values.length);
        for (long value : values) {
            output.writeLong(value);
        }
    }
}
