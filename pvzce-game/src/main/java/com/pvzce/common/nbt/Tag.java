package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

/** Minimal NBT tag, API-shaped after {@code net.minecraft.nbt.Tag}. */
public interface Tag {
    int END = 0;
    int BYTE = 1;
    int SHORT = 2;
    int INT = 3;
    int LONG = 4;
    int FLOAT = 5;
    int DOUBLE = 6;
    int BYTE_ARRAY = 7;
    int STRING = 8;
    int LIST = 9;
    int COMPOUND = 10;
    int INT_ARRAY = 11;
    int LONG_ARRAY = 12;

    byte getId();

    void write(DataOutput output) throws IOException;

    /** Implemented by the numeric tags so readers can widen them safely. */
    interface NumericTag extends Tag {
        double doubleValue();
    }
}
