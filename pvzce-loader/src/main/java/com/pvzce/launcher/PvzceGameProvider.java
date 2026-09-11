package com.pvzce.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.ExceptionUtil;

/**
 * GameProvider for PVZCE. Registered through
 * {@code META-INF/services/net.fabricmc.loader.impl.game.GameProvider} and used by
 * Fabric Loader's Knot launcher without any change to Knot itself.
 */
public final class PvzceGameProvider implements GameProvider {
    private static final String GAME_CLASS_RESOURCE = "com/pvzce/launcher/PvzceGame.class";
    private static final String DEFAULT_GAME_DIR = "~/.pvzce";
    private static final String[] GAME_SUBDIRS = {"mods", "resourcepacks", "datapacks", "config", "saves"};

    private EnvType envType;
    private Arguments arguments;
    private Path launchDirectory;
    private final GameTransformer entrypointTransformer = new GameTransformer();

    @Override
    public String getGameId() {
        return PvzceVersions.GAME_ID;
    }

    @Override
    public String getGameName() {
        return PvzceVersions.GAME_NAME;
    }

    @Override
    public String getRawGameVersion() {
        return PvzceVersions.GAME_VERSION;
    }

    @Override
    public String getNormalizedGameVersion() {
        return PvzceVersions.GAME_VERSION;
    }

    @Override
    public Collection<BuiltinMod> getBuiltinMods() {
        BuiltinModMetadata.Builder metadata = new BuiltinModMetadata.Builder(PvzceVersions.GAME_ID, PvzceVersions.GAME_VERSION)
                .setName(PvzceVersions.GAME_NAME)
                .setDescription("PVZ Community Edition game core.")
                .addLicense("GPL v3");
        // The game classes are already on the Knot class path; no separate path is needed.
        return Collections.singletonList(new BuiltinMod(Collections.emptyList(), metadata.build()));
    }

    @Override
    public String getEntrypoint() {
        return PvzceVersions.GAME_ENTRYPOINT;
    }

    @Override
    public Path getLaunchDirectory() {
        return launchDirectory;
    }

    @Override
    public boolean requiresUrlClassLoader() {
        return true;
    }

    @Override
    public Set<BuiltinTransform> getBuiltinTransforms(String className) {
        return Set.of();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean locateGame(FabricLauncher launcher, String[] args) {
        envType = launcher.getEnvironmentType();
        arguments = new Arguments();
        arguments.parse(args);

        String gameDir = arguments.get("gameDir");
        launchDirectory = Paths.get(expandUserHome(gameDir != null && !gameDir.isBlank() ? gameDir : DEFAULT_GAME_DIR))
                .toAbsolutePath().normalize();

        if (!containsGameClass(launcher.getClassPath())) {
            return false;
        }

        ensureGameDirectories();
        return true;
    }

    /** Expands a leading {@code ~} the same way shells do, and keeps all other paths untouched. */
    private static String expandUserHome(String path) {
        if ("~".equals(path)) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    /**
     * Fabric Loader's {@code DirectoryModCandidateFinder} creates {@code mods/} with
     * {@code Files.createDirectory}, which fails when the parent game directory does not
     * exist yet. Create the whole game directory tree defensively before
     * {@code FabricLoaderImpl.load()} runs.
     */
    private void ensureGameDirectories() {
        try {
            Files.createDirectories(launchDirectory);
            for (String sub : GAME_SUBDIRS) {
                Files.createDirectories(launchDirectory.resolve(sub));
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not create game directory " + launchDirectory, e);
        }
    }

    private static boolean containsGameClass(List<Path> classPath) {
        for (Path path : classPath) {
            if (Files.isDirectory(path)) {
                if (Files.isRegularFile(path.resolve(GAME_CLASS_RESOURCE.replace('/', path.getFileSystem().getSeparator().charAt(0))))) {
                    return true;
                }
            } else if (Files.isRegularFile(path)) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    ZipEntry entry = zip.getEntry(GAME_CLASS_RESOURCE);
                    if (entry != null) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // Not a readable zip; keep scanning.
                }
            }
        }

        return false;
    }

    @Override
    public void initialize(FabricLauncher launcher) {
        // Initialize the (empty) patch table so KnotClassDelegate.transform() is safe.
        entrypointTransformer.locateEntrypoints(launcher, List.of());
    }

    @Override
    public GameTransformer getEntrypointTransformer() {
        // No Minecraft entrypoint patches are required.
        return entrypointTransformer;
    }

    @Override
    public void unlockClassPath(FabricLauncher launcher) {
        // Everything is already on the class path.
    }

    @Override
    public void launch(ClassLoader loader) {
        try {
            Class<?> gameClass = loader.loadClass(getEntrypoint());
            MethodHandle invoker = MethodHandles.lookup().findStatic(gameClass, "main", MethodType.methodType(void.class, String[].class));
            invoker.invokeExact(arguments.toArray());
        } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
            throw FormattedException.ofLocalized("exception.pvzce.invokeFailure", e);
        } catch (Throwable t) {
            throw FormattedException.ofLocalized("exception.pvzce.generic", t);
        }
    }

    @Override
    public Arguments getArguments() {
        return arguments;
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        return arguments.toArray();
    }
}
