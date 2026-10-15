package com.pvzce.common.workshop;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.resource.DirectoryPack;
import com.pvzce.common.resource.PackSelection;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** An unpublished source pack. Only build output lives in the pack discovery roots. */
public record Workspace(Path root, String name, String namespace) {
    public static final String DIRECTORY = "workspaces";
    public static final com.google.gson.Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public Workspace {
        root = root.toAbsolutePath().normalize();
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Workspace name is required");
        if (namespace == null || !namespace.matches("[a-z0-9_][a-z0-9_.-]*")
                || namespace.equals(".") || namespace.equals(".."))
            throw new IllegalArgumentException("Namespace must contain lowercase letters, digits, underscores, dots or hyphens");
    }

    public static Workspace create(Path gameDir, String name, String namespace) throws IOException {
        Workspace workspace = new Workspace(gameDir.resolve(DIRECTORY).resolve(namespace), name.trim(), namespace);
        Files.createDirectories(workspace.root.getParent());
        Files.createDirectory(workspace.root);
        workspace.rename(name);
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 1);
        pack.addProperty("description", name);
        JsonObject metadata = new JsonObject();
        metadata.add("pack", pack);
        workspace.write("pack.mcmeta", metadata);
        return workspace;
    }

    public Workspace rename(String name) throws IOException {
        Workspace updated = new Workspace(root, name.trim(), namespace);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", updated.name);
        metadata.addProperty("namespace", namespace);
        updated.write("workspace.json", metadata);
        if (Files.isRegularFile(root.resolve("pack.mcmeta"))) {
            JsonObject pack = JsonParser.parseString(Files.readString(root.resolve("pack.mcmeta"))).getAsJsonObject();
            pack.getAsJsonObject("pack").addProperty("description", updated.name());
            updated.write("pack.mcmeta", pack);
        }
        return updated;
    }

    public static List<Workspace> list(Path gameDir) throws IOException {
        Path directory = gameDir.resolve(DIRECTORY);
        if (!Files.isDirectory(directory)) return List.of();
        List<Workspace> result = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.sorted().toList()) {
                if (!Files.isRegularFile(path.resolve("workspace.json"))) continue;
                JsonObject json = JsonParser.parseString(Files.readString(path.resolve("workspace.json"))).getAsJsonObject();
                result.add(new Workspace(path, json.get("name").getAsString(), json.get("namespace").getAsString()));
            }
        }
        return List.copyOf(result);
    }

    public Path file(String relative) {
        Path requested = Path.of(relative);
        if (requested.isAbsolute()) throw new IllegalArgumentException("Expected a workspace-relative path");
        for (Path segment : requested) if (segment.toString().equals("..") || segment.toString().equals("."))
            throw new IllegalArgumentException("Dot path segments are not allowed");
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || file.equals(root)) throw new IllegalArgumentException("File must stay inside the workspace");
        for (Path part = file; part != null && part.startsWith(root); part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IllegalArgumentException("Workspace symlinks are not supported: " + part);
        return file;
    }

    public Path contentFile(String directory, Identifier id) {
        return file("data/" + id.namespace() + "/" + directory + "/" + id.path() + ".json");
    }

    public void write(String relative, com.google.gson.JsonElement json) throws IOException {
        Path file = file(relative);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".draft-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(json));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public List<String> files() throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).map(root::relativize)
                    .map(p -> p.toString().replace('\\', '/'))
                    .filter(p -> p.startsWith("data/") || p.startsWith("assets/"))
                    .sorted().toList();
        }
    }

    public String packName() { return "workshop-" + root.getFileName(); }

    /** Validates before touching the previous build, then installs with rollback on I/O failure. */
    public Path build(Path gameDir, java.util.function.Consumer<Workspace> validate) throws IOException {
        validate.accept(this);
        Path output = gameDir.toAbsolutePath().resolve("datapacks").resolve(packName());
        Files.createDirectories(output.getParent());
        Path stage = Files.createTempDirectory(gameDir, ".workshop-build-");
        Path backup = stage.resolveSibling(stage.getFileName() + ".previous");
        boolean existed = Files.exists(output);
        if (existed && (!Files.isRegularFile(output.resolve(".workshop-origin"))
                || !Files.readString(output.resolve(".workshop-origin")).equals(root.getFileName().toString()))) {
            deleteTree(stage);
            throw new IOException("Build target belongs to another pack: " + output);
        }
        try {
            copyPack(root, stage);
            Files.writeString(stage.resolve(".workshop-origin"), root.getFileName().toString());
            if (existed) Files.move(output, backup);
            try { Files.move(stage, output); }
            catch (IOException e) {
                if (existed) Files.move(backup, output);
                throw e;
            }
            try {
                if (!existed) {
                    PackSelection selection = PackSelection.load(gameDir);
                    selection.setEnabled(packName(), true);
                    if (!selection.save()) throw new IOException("Could not save pack selection");
                }
            } catch (IOException e) {
                deleteTree(output);
                if (existed) Files.move(backup, output);
                throw e;
            }
            return output;
        } finally {
            deleteTree(stage);
            deleteTree(backup);
        }
    }

    public static void copyPack(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (var paths = Files.walk(source)) {
            for (Path from : paths.toList()) {
                Path relative = source.relativize(from);
                String first = relative.getNameCount() == 0 ? "" : relative.getName(0).toString();
                if (!(first.equals("assets") || first.equals("data") || first.equals("pack.mcmeta"))) continue;
                if (Files.isSymbolicLink(from)) throw new IOException("Workspace symlinks are not supported: " + relative);
                Path to = destination.resolve(relative);
                if (Files.isDirectory(from)) Files.createDirectories(to);
                else Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
