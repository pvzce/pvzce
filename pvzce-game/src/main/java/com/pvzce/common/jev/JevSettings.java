package com.pvzce.common.jev;

/**
 * Where Jev lives and with which credential: the three values a level's opponent needs.
 *
 * <p>Jev is TypeSafe's System One decision model: one POST carrying a {@code state} and a
 * map of typed {@code questions} answers with typed choices, probabilities and confidences
 * instead of text. Several parties serve it and they do not agree on the URL or on the
 * outer envelope of the reply, which is why the URL is a setting rather than a constant
 * and why {@link JevDecision} parses defensively. The ones that were verified while this
 * was written:
 *
 * <ul>
 *   <li>OpenRouter - {@code https://openrouter.ai/api/alpha/decisions}, model
 *       {@code typesafe/jev-1.13}, answers at the top level;</li>
 *   <li>the JevAI community site - {@code https://www.jevai.org/api/v1/decisions}, a
 *       personal key as a bearer token, answers under {@code data.answers};</li>
 *   <li>the TypeSafe model gateway - {@code .../v1/systemone}, answers at the top
 *       level.</li>
 * </ul>
 *
 * <p>The key is the player's own and is only ever held in memory on the server: the client
 * keeps it in {@code config/pvzce-client.toml} and hands it over for the session (see
 * {@code JevSettingsC2S}). {@link #fromSystemProperties()} is the second door, for a
 * headless smoke run or a test that has no client to send anything.
 *
 * @param url   the full endpoint, e.g. {@code https://openrouter.ai/api/alpha/decisions}
 * @param model the provider's model name for Jev
 * @param key   the bearer token; never logged, never written into a save or a packet dump
 */
public record JevSettings(String url, String model, String key) {
    /** OpenRouter: the default because it was the one actually called while this was written. */
    public static final String DEFAULT_URL = "https://openrouter.ai/api/alpha/decisions";
    public static final String DEFAULT_MODEL = "typesafe/jev-1.13";

    /** The property names a headless run (or a test) uses instead of the client's settings. */
    public static final String PROPERTY_URL = "pvzce.jev.url";
    public static final String PROPERTY_MODEL = "pvzce.jev.model";
    public static final String PROPERTY_KEY = "pvzce.jev.key";

    /**
     * The environment variables a headless run may use instead.
     *
     * <p>Second choice behind the properties, and the better one for a real credential: a command
     * line is visible in {@code ps} and in every build log that quotes it, while an environment
     * variable is not. A test or a server that sets both gets the property.
     */
    public static final String ENV_URL = "PVZCE_JEV_URL";
    public static final String ENV_MODEL = "PVZCE_JEV_MODEL";
    public static final String ENV_KEY = "PVZCE_JEV_KEY";

    /** Nothing configured: no opponent, so every versus level falls back to its own policy. */
    public static final JevSettings NONE = new JevSettings("", "", "");

    public JevSettings {
        url = url == null ? "" : url.trim();
        key = key == null ? "" : key.trim();
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    /**
     * True when this is worth a request: a URL and a key.
     *
     * <p>The model has a default, so it cannot be the reason a call is impossible. An empty
     * URL or key is not a failure - it is the state every player is in before they paste a
     * key, and the level is expected to play with its own policy instead.
     */
    public boolean configured() {
        return !url.isEmpty() && !key.isEmpty();
    }

    /**
     * The settings a headless run was started with, or {@link #NONE}.
     *
     * <p>Read once at server start rather than per request: a property is a launch argument,
     * and re-reading the system property table on every decision would make the server's
     * behaviour depend on who wrote to it last.
     */
    public static JevSettings fromSystemProperties() {
        String url = launchValue(PROPERTY_URL, ENV_URL);
        String model = launchValue(PROPERTY_MODEL, ENV_MODEL);
        String key = launchValue(PROPERTY_KEY, ENV_KEY);
        if (url.isBlank() && key.isBlank()) {
            return NONE;
        }
        return new JevSettings(url, model, key);
    }

    /** A launch argument, or the environment variable of the same meaning, or empty. */
    private static String launchValue(String property, String environment) {
        String fromProperty = System.getProperty(property, "");
        if (!fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnvironment = System.getenv(environment);
        return fromEnvironment == null ? "" : fromEnvironment;
    }

    /**
     * A copy without the key, for anything that might be printed.
     *
     * <p>There is exactly one reason this exists: a log line or a diagnostic that names the
     * endpoint must not be the place the credential leaks into a bug report.
     */
    public JevSettings redacted() {
        return new JevSettings(url, model, key.isEmpty() ? "" : "***");
    }

    @Override
    public String toString() {
        return "JevSettings[url=" + url + ", model=" + model + ", key="
                + (key.isEmpty() ? "<none>" : "***") + "]";
    }
}
