package com.pvzce.common.nbt;

import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Gzip NBT IO with the same binary layout as Minecraft's {@code NbtIo}. */
public final class NbtIo {
    private NbtIo() {
    }

    public static void writeCompressed(CompoundTag tag, Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             OutputStream buffered = new BufferedOutputStream(out);
             DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(buffered))) {
            write(tag, dos);
        }
    }

    public static CompoundTag readCompressed(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file);
             DataInputStream dis = new DataInputStream(new GZIPInputStream(in))) {
            return read(dis);
        }
    }

    public static void write(CompoundTag tag, DataOutput output) throws IOException {
        writeUnnamedTag(tag, output);
    }

    /**
     * Writes one unnamed tag. Only compound roots are supported, matching
     * {@link #read}: the writer used to accept any root, so a list root produced a
     * file the reader rejected with "Root tag must be a compound tag".
     */
    public static void writeUnnamedTag(Tag tag, DataOutput output) throws IOException {
        if (tag.getId() != Tag.COMPOUND) {
            throw new IOException("Root tag must be a compound tag, got type " + tag.getId());
        }
        output.writeByte(tag.getId());
        output.writeUTF("");
        tag.write(output);
    }

    public static CompoundTag read(DataInput input) throws IOException {
        byte type = input.readByte();
        if (type == Tag.END) {
            return new CompoundTag();
        }
        if (type != Tag.COMPOUND) {
            throw new IOException("Root tag must be a compound tag");
        }
        input.readUTF(); // unnamed root name
        return readCompound(input);
    }

    public static Tag readAnyTag(DataInput input) throws IOException {
        byte type = input.readByte();
        if (type == Tag.END) {
            return EndTag.INSTANCE;
        }
        return readPayload(input, type);
    }

    private static CompoundTag readCompound(DataInput input) throws IOException {
        CompoundTag tag = new CompoundTag();
        byte type;
        while ((type = input.readByte()) != Tag.END) {
            String name = input.readUTF();
            tag.put(name, readPayload(input, type));
        }
        return tag;
    }

    private static Tag readPayload(DataInput input, byte type) throws IOException {
        return switch (type) {
            case Tag.BYTE -> new ByteTag(input.readByte());
            case Tag.SHORT -> new ShortTag(input.readShort());
            case Tag.INT -> new IntTag(input.readInt());
            case Tag.LONG -> new LongTag(input.readLong());
            case Tag.FLOAT -> new FloatTag(input.readFloat());
            case Tag.DOUBLE -> new DoubleTag(input.readDouble());
            case Tag.STRING -> new StringTag(input.readUTF());
            case Tag.BYTE_ARRAY -> readByteArray(input);
            case Tag.INT_ARRAY -> readIntArray(input);
            case Tag.LONG_ARRAY -> readLongArray(input);
            case Tag.LIST -> readList(input);
            case Tag.COMPOUND -> readCompound(input);
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    /**
     * Upper bound for any declared array/list length. A corrupt file can declare a
     * negative length (which used to escape as {@code NegativeArraySizeException},
     * breaking the declared {@link IOException} contract) or an absurd one.
     */
    public static final int MAX_COLLECTION_LENGTH = 1 << 24;

    private static int readLength(DataInput input, String what) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_COLLECTION_LENGTH) {
            throw new IOException("Invalid " + what + " length in NBT: " + length);
        }
        return length;
    }

    private static Tag readByteArray(DataInput input) throws IOException {
        int len = readLength(input, "byte array");
        byte[] values = new byte[len];
        input.readFully(values);
        return new ByteArrayTag(values);
    }

    private static Tag readIntArray(DataInput input) throws IOException {
        int len = readLength(input, "int array");
        int[] values = new int[len];
        for (int i = 0; i < len; i++) {
            values[i] = input.readInt();
        }
        return new IntArrayTag(values);
    }

    private static Tag readLongArray(DataInput input) throws IOException {
        int len = readLength(input, "long array");
        long[] values = new long[len];
        for (int i = 0; i < len; i++) {
            values[i] = input.readLong();
        }
        return new LongArrayTag(values);
    }

    private static Tag readList(DataInput input) throws IOException {
        byte elementType = input.readByte();
        int len = readLength(input, "list");
        ListTag list = new ListTag();
        if (elementType == Tag.END) {
            // Vanilla treats an END-typed list as empty regardless of its length.
            return list;
        }
        for (int i = 0; i < len; i++) {
            list.add(readPayload(input, elementType));
        }
        return list;
    }
}
