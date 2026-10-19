package com.pvzce.launcher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Consumes launcher credentials before Fabric's map parser or any mod can observe them. */
public final class GameLoginArguments {
    private String api;
    private char[] code;
    private boolean invalid;
    private final String[] remaining;

    private GameLoginArguments(String[] args) {
        List<String> publicArgs = new ArrayList<>();
        List<String> secrets = new ArrayList<>();
        String rawApi = null;
        String rawCode = null;
        int apiCount = 0;
        int codeCount = 0;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            int equals = arg.indexOf('=');
            String key = equals < 0 ? arg : arg.substring(0, equals);
            if (!key.equals("--pvzceApiUrl") && !key.equals("--pvzceLoginCode")) {
                publicArgs.add(arg);
                continue;
            }
            String value = "";
            args[i] = "[redacted]";
            if (equals >= 0) {
                value = arg.substring(equals + 1);
            } else if (i + 1 < args.length && (!args[i + 1].startsWith("--")
                    || (key.equals("--pvzceLoginCode") && args[i + 1].matches("[A-Za-z0-9_-]{32,128}")))) {
                value = args[++i];
                args[i] = "[redacted]";
            }
            if (key.equals("--pvzceApiUrl")) {
                apiCount++;
                rawApi = value;
            } else {
                codeCount++;
                rawCode = value;
                if (!value.isEmpty()) secrets.add(value);
            }
        }
        // Also scrub aliases of a supplied code in unrelated arguments and JVM diagnostics.
        for (String secret : secrets) {
            publicArgs.replaceAll(value -> value.replace(secret, "[redacted]"));
            for (int i = 0; i < args.length; i++) args[i] = args[i].replace(secret, "[redacted]");
            String command = System.getProperty("sun.java.command");
            if (command != null) System.setProperty("sun.java.command", command.replace(secret, "[redacted]"));
        }
        remaining = publicArgs.toArray(String[]::new);
        if (apiCount == 0 && codeCount == 0) return;
        invalid = true;
        if (apiCount == 1 && codeCount == 1 && rawCode.matches("[A-Za-z0-9_-]{32,128}")) {
            try {
                api = normalizeOrigin(rawApi);
                code = rawCode.toCharArray();
                invalid = false;
            } catch (IllegalArgumentException ignored) {
                // External input: only a fixed offline status crosses into the game.
            }
        }
        // JDK wire diagnostics can dump JSON bodies and Authorization headers to stderr.
        System.clearProperty("jdk.httpclient.HttpClient.log");
        System.clearProperty("javax.net.debug");
        System.setProperty("jdk.httpclient.enableAllMethodRetry", "false");
        System.setProperty("jdk.internal.httpclient.debug", "false");
        System.setProperty("jdk.internal.httpclient.websocket.debug", "false");
        System.setProperty("jdk.internal.httpclient.hpack.debug", "false");
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
    }

    static GameLoginArguments consume(String[] args) {
        return new GameLoginArguments(args);
    }

    public static String normalizeOrigin(String raw) {
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean local = List.of("localhost", "127.0.0.1", "[::1]", "10.0.2.2").contains(host);
            if ((!scheme.equals("https") && !(scheme.equals("http") && local)) || host.isEmpty()
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException();
            }
            int port = uri.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
            return new URI(scheme, null, host, port, null, null, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid login origin");
        }
    }

    String[] remaining() { return remaining.clone(); }
    String api() { return api; }
    boolean invalid() { return invalid; }

    /** One transfer, no accessor on GameProvider or FabricLoader. Caller wipes after invocation. */
    char[] takeCode() {
        char[] result = code;
        code = null;
        return result;
    }

    void clear() {
        if (code != null) Arrays.fill(code, '\0');
        code = null;
        api = null;
    }

    @Override public String toString() { return "GameLoginArguments[redacted]"; }
}
