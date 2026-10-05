package com.pvzce.common.network;

import com.pvzce.api.util.Identifier;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * MC-style binary stream wrapper around a Netty {@link ByteBuf}.
 *
 * <p>Every collection, optional and identifier accessor lives here so a packet
 * never hand-rolls its own length-prefixed loop. Those loops were repeated in
 * more than a dozen packet classes, and every copy read the declared count
 * straight into an {@code ArrayList} capacity - a single corrupt or hostile
 * length prefix could allocate gigabytes before a byte of payload was read.
 * All lengths are validated against the remaining buffer here instead.
 */
public final class PacketByteBuf {
    /** Same cap as vanilla's string reader, in UTF-8 bytes. */
    public static final int MAX_STRING_BYTES = 32767;
    /** Sanity cap for any collection on the wire. */
    public static final int MAX_COLLECTION_SIZE = 4096;
    public static final int MAX_VARINT_BYTES = 5;

    private final ByteBuf delegate;

    public PacketByteBuf(ByteBuf delegate) {
        this.delegate = delegate;
    }

    public ByteBuf delegate() {
        return delegate;
    }

    public int readableBytes() {
        return delegate.readableBytes();
    }

    // ---------- primitives ----------

    public void writeVarInt(int value) {
        while ((value & ~0x7F) != 0) {
            delegate.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        delegate.writeByte(value);
    }

    public int readVarInt() {
        int result = 0;
        int shift = 0;
        byte b;
        do {
            if (shift >= MAX_VARINT_BYTES * 7) {
                throw new DecoderException("VarInt is too big");
            }
            b = delegate.readByte();
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    public void writeByte(int value) {
        delegate.writeByte(value);
    }

    public int readByte() {
        return delegate.readByte() & 0xFF;
    }

    public void writeInt(int value) {
        delegate.writeInt(value);
    }

    public int readInt() {
        return delegate.readInt();
    }

    public void writeLong(long value) {
        delegate.writeLong(value);
    }

    public long readLong() {
        return delegate.readLong();
    }

    public void writeFloat(float value) {
        delegate.writeFloat(value);
    }

    public float readFloat() {
        return delegate.readFloat();
    }

    public void writeDouble(double value) { delegate.writeDouble(value); }
    public double readDouble() { return delegate.readDouble(); }

    /** Map collections use the same bounded lengths as lists; duplicate keys are invalid. */
    public <K, V> void writeMap(java.util.Map<K, V> values, BiConsumer<K, PacketByteBuf> keyWriter,
                              BiConsumer<V, PacketByteBuf> valueWriter) {
        writeList(new ArrayList<>(values.entrySet()), (entry, buf) -> {
            keyWriter.accept(entry.getKey(), buf);
            valueWriter.accept(entry.getValue(), buf);
        });
    }

    public <K, V> java.util.Map<K, V> readMap(Function<PacketByteBuf, K> keyReader,
                                           Function<PacketByteBuf, V> valueReader) {
        java.util.Map<K, V> values = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<K, V> entry : readList(buf -> java.util.Map.entry(
                keyReader.apply(buf), valueReader.apply(buf)))) {
            if (values.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                throw new DecoderException("Duplicate map key: " + entry.getKey());
        }
        return java.util.Map.copyOf(values);
    }

    public void writeBoolean(boolean value) {
        delegate.writeBoolean(value);
    }

    public boolean readBoolean() {
        return delegate.readBoolean();
    }

    // ---------- strings ----------

    public void writeString(String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new EncoderException("String too long: " + bytes.length + " bytes");
        }
        writeVarInt(bytes.length);
        delegate.writeBytes(bytes);
    }

    public String readString() {
        int length = readLength(MAX_STRING_BYTES, "string");
        byte[] bytes = new byte[length];
        delegate.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public void writeStringList(List<String> values) {
        writeList(values, (value, buf) -> buf.writeString(value));
    }

    public List<String> readStringList() {
        return readList(PacketByteBuf::readString);
    }

    // ---------- identifiers ----------

    public void writeIdentifier(Identifier id) {
        writeString(id == null ? "" : id.toString());
    }

    /** The identifier, or {@code null} when the field was written as empty. */
    public Identifier readIdentifierOrNull() {
        String value = readString();
        return value.isEmpty() ? null : Identifier.tryParse(value);
    }

    // ---------- collections ----------

    /**
     * Writes a length-prefixed list. The writer takes the element first so a
     * packet can pass {@code Record::encode} directly.
     */
    public <T> void writeList(List<T> values, BiConsumer<T, PacketByteBuf> writer) {
        List<T> list = values == null ? List.of() : values;
        if (list.size() > MAX_COLLECTION_SIZE) {
            throw new EncoderException("Collection too large: " + list.size());
        }
        writeVarInt(list.size());
        for (T value : list) {
            writer.accept(value, this);
        }
    }

    public <T> List<T> readList(Function<PacketByteBuf, T> reader) {
        int count = readLength(MAX_COLLECTION_SIZE, "collection");
        List<T> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(reader.apply(this));
        }
        return result;
    }

    public <T> void writeOptional(Optional<T> value, BiConsumer<T, PacketByteBuf> writer) {
        if (value.isPresent()) {
            writeBoolean(true);
            writer.accept(value.get(), this);
        } else {
            writeBoolean(false);
        }
    }

    public <T> Optional<T> readOptional(Function<PacketByteBuf, T> reader) {
        return readBoolean() ? Optional.ofNullable(reader.apply(this)) : Optional.empty();
    }

    // ---------- enum ----------

    public <E extends Enum<E>> void writeEnum(E value) {
        writeString(value == null ? "" : value.name());
    }

    public <E extends Enum<E>> E readEnum(Class<E> type, E fallback) {
        String name = readString();
        if (name.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Reads a length prefix and rejects anything negative, above {@code max} or
     * larger than the bytes actually left in the buffer.
     */
    private int readLength(int max, String what) {
        int length = readVarInt();
        if (length < 0 || length > max) {
            throw new DecoderException("Invalid " + what + " length: " + length);
        }
        // A string/collection can never need more than one byte per element, so a
        // length above the remaining bytes is corrupt by definition.
        if (length > readableBytes() + 1) {
            throw new DecoderException(what + " length " + length + " exceeds the remaining "
                    + readableBytes() + " bytes");
        }
        return length;
    }

    /** Thrown when a frame cannot be read; {@code Connection} turns it into a clean disconnect. */
    public static final class DecoderException extends RuntimeException {
        public DecoderException(String message) {
            super(message);
        }
    }

    /** Thrown when a value cannot be encoded. */
    public static final class EncoderException extends RuntimeException {
        public EncoderException(String message) {
            super(message);
        }
    }
}
