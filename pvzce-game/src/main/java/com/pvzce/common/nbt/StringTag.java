package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public record StringTag(String value) implements Tag {
    @Override
    public byte getId() {
        return STRING;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        output.writeUTF(value);
    }
}
