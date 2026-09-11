package com.pvzce.common.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ListTag implements Tag {
    private final List<Tag> tags = new ArrayList<>();

    /** Appends an element; null is rejected so a mixed list cannot fail at write time. */
    public void add(Tag tag) {
        if (tag == null) {
            throw new IllegalArgumentException("ListTag element must not be null");
        }
        tags.add(tag);
    }

    public List<Tag> values() {
        return Collections.unmodifiableList(tags);
    }

    public int size() {
        return tags.size();
    }

    public Tag get(int index) {
        return tags.get(index);
    }

    public CompoundTag getCompound(int index) {
        return (CompoundTag) tags.get(index);
    }

    @Override
    public byte getId() {
        return LIST;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        byte elementType = tags.isEmpty() ? END : tags.get(0).getId();
        output.writeByte(elementType);
        output.writeInt(tags.size());
        for (Tag tag : tags) {
            if (tag.getId() != elementType) {
                throw new IOException("Mixed tag types in ListTag");
            }
            tag.write(output);
        }
    }
}
