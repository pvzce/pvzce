package com.pvzce.common.resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface PvzcePack extends AutoCloseable {
    String name();

    InputStream open(String path) throws IOException;

    /** Lists relative paths below a prefix (without leading slash, directory separators normalized to '/'). */
    List<String> list(String prefix) throws IOException;

    @Override
    default void close() throws IOException {
    }
}
