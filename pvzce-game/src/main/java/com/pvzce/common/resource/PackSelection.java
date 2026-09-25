package com.pvzce.common.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The external packs the player switched off, keyed by directory name, stored in
 * {@code config/pvzce-packs.json}.
 *
 * <p>Deliberately not a field of {@code PvzceClientConfig}: that file is the client's alone, and
 * the resource manager is built once per side <b>pointing at the same game directory</b>. A list
 * only the client could read would leave the server loading a pack the player had switched off -
 * the level definitions it pushes would then disagree with the art the client refused to load.
 *
 * <p>The file is the source of truth, not a cache: {@link PvzceResourceManager#reload()} re-reads
 * it every time, because the write and the read happen on opposite sides of the connection.
 */
public final class PackSelection {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Resources");
    /** Where the list lives, relative to the game directory. */
    public static final String FILE_NAME = "config/pvzce-packs.json";
    private static final String DISABLED_KEY = "disabled";

    private final Path file;
    private final Set<String> disabled = new LinkedHashSet<>();

    private PackSelection(Path file) {
        this.file = file;
    }

    /** Reads the list beside the other config files; a missing file means "everything on". */
    public static PackSelection load(Path gameDir) {
        PackSelection selection = new PackSelection(gameDir.resolve(FILE_NAME));
        if (!Files.isRegularFile(selection.file)) {
            return selection;
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(selection.file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement names = root.get(DISABLED_KEY);
            if (names != null && names.isJsonArray()) {
                for (JsonElement name : names.getAsJsonArray()) {
                    if (name.isJsonPrimitive()) {
                        selection.disabled.add(name.getAsString());
                    }
                }
            }
        } catch (Exception e) {
            // A list nobody can read must not switch the game's content off: the fallback is
            // "everything enabled", which looks like a fresh install rather than like a
            // corrupted one, and the next toggle rewrites the file.
            LOGGER.warn("Could not read " + selection.file + "; every pack stays enabled", e);
        }
        return selection;
    }

    /** True when this directory name is switched off. */
    public boolean isDisabled(String packName) {
        return packName != null && disabled.contains(packName);
    }

    /** True when this directory name is loaded; a nameless entry is never disabled. */
    public boolean isEnabled(String packName) {
        return !isDisabled(packName);
    }

    /** The switched-off directory names, in the order they were written. */
    public List<String> disabled() {
        return List.copyOf(disabled);
    }

    /** The file this list was read from and is written back to. */
    public Path file() {
        return file;
    }

    /**
     * Switches one pack on or off.
     *
     * <p>Disabling {@link ClasspathPack#NAME} is refused rather than ignored: the built-in pack is
     * the game's own content, and a list that could name it would leave nothing to load.
     *
     * @throws IllegalArgumentException when asked to disable the built-in pack
     */
    public void setEnabled(String packName, boolean enabled) {
        if (packName == null || packName.isBlank()) {
            return;
        }
        if (!enabled && isBuiltIn(packName)) {
            throw new IllegalArgumentException("The built-in pack '" + packName + "' cannot be disabled");
        }
        if (enabled) {
            disabled.remove(packName);
        } else {
            disabled.add(packName);
        }
    }

    /** True for the one pack that ships with the game and is always loaded. */
    public static boolean isBuiltIn(String packName) {
        return ClasspathPack.NAME.equals(packName);
    }

    /**
     * Writes the list back.
     *
     * @return whether the file was written; a failure is logged and reported to the caller,
     *         because a toggle that only changed memory would come back on the next start
     */
    public boolean save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            JsonArray names = new JsonArray();
            for (String name : disabled) {
                names.add(name);
            }
            root.add(DISABLED_KEY, names);
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            LOGGER.error("Could not write the pack list to " + file, e);
            return false;
        }
    }
}
