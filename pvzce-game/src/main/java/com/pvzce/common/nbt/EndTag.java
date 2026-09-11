package com.pvzce.common.nbt;

import java.io.DataOutput;

public final class EndTag implements Tag {
    public static final EndTag INSTANCE = new EndTag();

    private EndTag() {
    }

    @Override
    public byte getId() {
        return END;
    }

    @Override
    public void write(DataOutput output) {
    }
}
