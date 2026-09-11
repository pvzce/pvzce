package com.pvzce.common.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Declares a packet's fields once, so its writer and reader cannot drift apart.
 *
 * <p>Every packet used to spell its field order twice - once in {@code encode} and
 * once in {@code decode} - a handful of lines apart, with no compiler help. That
 * is how a field gets written but never read, read with the wrong type, or moved
 * on one side only; the resulting failure is a silent cross-packet desync at
 * runtime, not a compile error. Here each field's getter, writer and reader are
 * declared together, and {@link Codec#encode}/{@link Codec#decode} walk the same
 * list, so the two orders are the same order by construction.
 *
 * <p>Packets with one or two fields are still hand-written; a structure pays for
 * itself from about three fields up.
 */
public final class PacketStruct<T> {
    /** A paired encoder/decoder for one value type. */
    public interface Codec<T> {
        void encode(T value, PacketByteBuf buf);

        T decode(PacketByteBuf buf);
    }

    private record Field<T>(Function<T, Object> getter, BiConsumer<PacketByteBuf, Object> writer,
                            Function<PacketByteBuf, Object> reader) {
    }

    private final List<Field<T>> fields;
    private final Function<List<Object>, T> factory;

    private PacketStruct(List<Field<T>> fields, Function<List<Object>, T> factory) {
        this.fields = List.copyOf(fields);
        this.factory = factory;
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Convenience codec for packets whose field list is a single primitive. */
    public static <T> Codec<T> of(BiConsumer<T, PacketByteBuf> writer, Function<PacketByteBuf, T> reader) {
        return new Codec<>() {
            @Override
            public void encode(T value, PacketByteBuf buf) {
                writer.accept(value, buf);
            }

            @Override
            public T decode(PacketByteBuf buf) {
                return reader.apply(buf);
            }
        };
    }

    public static final class Builder<T> {
        private final List<Field<T>> fields = new ArrayList<>();

        /**
         * Declares one field: how to read it off the object, how to write it and how
         * to read it back. All three sit on the same line so they cannot disagree.
         */
        @SuppressWarnings("unchecked")
        public <V> Builder<T> field(Function<T, V> getter, BiConsumer<PacketByteBuf, V> writer,
                                    Function<PacketByteBuf, V> reader) {
            fields.add(new Field<>((Function<T, Object>) getter,
                    (BiConsumer<PacketByteBuf, Object>) writer,
                    (Function<PacketByteBuf, Object>) reader));
            return this;
        }

        /** Field whose value is itself a {@link PacketStruct.Codec}. */
        public <V> Builder<T> nested(Function<T, V> getter, Codec<V> codec) {
            return field(getter, (buf, value) -> codec.encode(value, buf), codec::decode);
        }

        /** Length-prefixed list field. */
        public <V> Builder<T> list(Function<T, List<V>> getter, BiConsumer<V, PacketByteBuf> elementWriter,
                                   Function<PacketByteBuf, V> elementReader) {
            return field(getter,
                    (buf, values) -> buf.writeList(values, elementWriter),
                    buf -> buf.readList(elementReader));
        }

        /** Length-prefixed list of strings. */
        public Builder<T> stringList(Function<T, List<String>> getter) {
            return field(getter, PacketByteBuf::writeStringList, PacketByteBuf::readStringList);
        }

        /** Builds the codec; {@code factory} receives the decoded values in field order. */
        public Codec<T> build(Function<List<Object>, T> factory) {
            PacketStruct<T> struct = new PacketStruct<>(fields, factory);
            return struct.codec();
        }
    }

    private Codec<T> codec() {
        return new Codec<>() {
            @Override
            public void encode(T value, PacketByteBuf buf) {
                for (Field<T> field : fields) {
                    field.writer().accept(buf, field.getter().apply(value));
                }
            }

            @Override
            public T decode(PacketByteBuf buf) {
                List<Object> values = new ArrayList<>(fields.size());
                for (Field<T> field : fields) {
                    values.add(field.reader().apply(buf));
                }
                return factory.apply(values);
            }
        };
    }
}
