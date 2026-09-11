package com.pvzce.common.resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** The built-in pack served from the game classpath (directories in dev, fat jar in production). */
public final class ClasspathPack implements PvzcePack {
    private final ClassLoader classLoader;

    public ClasspathPack(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public String name() {
        return "pvzce";
    }

    @Override
    public InputStream open(String path) {
        return classLoader.getResourceAsStream(path);
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        // The classloader lookup needs a directory-style path, but every returned
        // entry is joined in the same shape DirectoryPack uses.
        String lookup = PackPaths.normalizePrefix(prefix);
        String resourcePath = lookup.isEmpty() ? "" : lookup + "/";
        List<String> result = new ArrayList<>();
        Enumeration<URL> urls = classLoader.getResources(resourcePath);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            collect(url, resourcePath, result);
        }
        return result.stream().distinct().sorted().toList();
    }

    private void collect(URL url, String prefix, List<String> result) throws IOException {
        URLConnection connection = url.openConnection();
        if (connection instanceof JarURLConnection jarConnection) {
            // Do NOT close this JarFile. JarURLConnection caches it (useCaches
            // defaults to true), so closing it closes the jar the classloader itself
            // is serving from - which breaks later getResourceAsStream calls in the
            // production fat-jar run. The cached handle is owned by the JVM.
            jarConnection.setUseCaches(true);
            collectJar(jarConnection.getJarFile(), prefix, result);
        } else if ("file".equals(url.getProtocol())) {
            Path root;
            try {
                root = Path.of(url.toURI());
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Invalid classpath resource URL: " + url, e);
            }
            if (Files.isDirectory(root)) {
                try (var paths = Files.walk(root)) {
                    paths.filter(Files::isRegularFile)
                            .map(root::relativize)
                            .map(Path::toString)
                            .map(p -> p.replace(File.separatorChar, '/'))
                            .filter(p -> !p.endsWith("/"))
                            .forEach(p -> result.add(PackPaths.join(prefix, p)));
                }
            }
        } else if ("jar".equals(url.getProtocol()) && !(connection instanceof JarURLConnection)) {
            String path = url.getPath();
            int bang = path.indexOf("!/");
            if (bang > 0) {
                try (JarFile jar = new JarFile(path.substring(0, bang))) {
                    collectJar(jar, prefix, result);
                }
            }
        }
    }

    private void collectJar(JarFile jar, String prefix, List<String> result) {
        List<String> found = new ArrayList<>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith(prefix)) {
                found.add(entry.getName());
            }
        }
        found.sort(String::compareTo);
        result.addAll(found);
    }
}
