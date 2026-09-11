package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public record ByteArrayTag(byte[] values) implements Tag {
    @Override
    public byte getId() {
        return BYTE_ARRAY;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeInt(values.length);
        output.write(values);
    }
}
