package com.pvzce.client.account;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.common.PvzceConstants;
import com.pvzce.launcher.PvzceVersions;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Launcher identity only; never used as a world/profile id or multiplayer authorization. */
public final class GameAccount implements AutoCloseable {
    static final String EXCHANGE = "/api/v1/game/login-code/exchange";
    static final String SESSION = "/api/v1/game/session";
    static final String LOGOUT = "/api/v1/game/logout";

    @FunctionalInterface
    interface Transport {
        JsonObject request(String method, String path, JsonObject body, String token) throws Failure;
    }

    /** Fixed local messages only, with no remote body/cause attached. */
    static final class Failure extends Exception {
        Failure(String message) { super(message, null, false, false); }
    }

    public enum State { OFFLINE, CONNECTING, ONLINE }
    public record View(State state, String message, String displayName, Instant expiresAt) { }

    private final Clock clock;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PvzceGameAccount");
        thread.setDaemon(true);
        return thread;
    });
    private Transport transport;
    private String token;
    private long userId;
    private Instant expiry;
    private Instant nextCheck;
    private boolean checking;
    private boolean closed;
    private long generation;
    private View view = new View(State.OFFLINE, "离线游戏", "", null);

    public GameAccount() { this(Clock.systemUTC()); }
    GameAccount(Clock clock) { this.clock = clock; }

    public synchronized View view() {
        expire();
        return view;
    }

    /** Only the launcher boundary calls this. The buffer is wiped before returning. */
    public void start(String api, char[] code, boolean invalid) {
        if (invalid) {
            if (code != null) Arrays.fill(code, '\0');
            synchronized (this) { offline("登录参数无效，本次离线游戏"); }
            return;
        }
        if (code == null) return;
        try {
            start(new GameLoginHttp(api), code);
        } catch (IllegalArgumentException e) {
            Arrays.fill(code, '\0');
            synchronized (this) { offline("登录地址无效，本次离线游戏"); }
        }
    }

    synchronized void start(Transport transport, char[] code) {
        if (closed || this.transport != null) throw new IllegalStateException("Login already started");
        this.transport = transport;
        char[] ticket = code.clone();
        Arrays.fill(code, '\0');
        view = new View(State.CONNECTING, "正在验证启动器账号，可继续离线游戏", "", null);
        long attempt = ++generation;
        worker.execute(() -> login(ticket, attempt));
    }

    private void login(char[] ticket, long attempt) {
        String issuedToken = null;
        boolean adopted = false;
        try {
            synchronized (this) {
                if (closed || attempt != generation) return;
            }
            String code = new String(ticket);
            Arrays.fill(ticket, '\0');
            JsonObject body = new JsonObject();
            body.addProperty("protocol", 1);
            body.addProperty("game_id", PvzceVersions.GAME_ID);
            body.addProperty("game_version", PvzceVersions.GAME_VERSION);
            body.addProperty("login_code", code);
            JsonObject exchange;
            try {
                exchange = transport.request("POST", EXCHANGE, body, null);
            } finally {
                body.remove("login_code");
            }
            if (!"Bearer".equals(text(exchange, "token_type"))) throw new Failure("游戏令牌类型无效");
            String candidate = text(exchange, "access_token");
            exchange.remove("access_token");
            if (candidate.isEmpty() || candidate.length() > PvzceConstants.GAME_TOKEN_MAX_LENGTH
                    || !candidate.chars().allMatch(c -> c >= 33 && c <= 126)) {
                throw new Failure("游戏令牌无效");
            }
            issuedToken = candidate;
            Identity issued = identity(exchange, code, issuedToken);
            synchronized (this) {
                if (closed || attempt != generation) return;
            }
            Identity verified = identity(transport.request("GET", SESSION, null, issuedToken), code, issuedToken);
            if (verified.id != issued.id || verified.expiry.isAfter(issued.expiry)) {
                throw new Failure("游戏会话身份不一致");
            }
            synchronized (this) {
                if (closed || attempt != generation) return;
                token = issuedToken;
                adopted = true;
                userId = verified.id;
                expiry = verified.expiry;
                nextCheck = clock.instant().plusSeconds(PvzceConstants.GAME_SESSION_CHECK_SECONDS);
                view = new View(State.ONLINE, "已登录", verified.name, expiry);
            }
        } catch (Failure e) {
            failed(attempt, e.getMessage());
        } catch (RuntimeException e) {
            failed(attempt, "登录响应无效");
        } finally {
            Arrays.fill(ticket, '\0');
            if (issuedToken != null && !adopted) {
                try {
                    transport.request("POST", LOGOUT, null, issuedToken);
                } catch (Failure | RuntimeException ignored) {
                    // Abandoned/failed handoff: best-effort revocation, never retain its token.
                }
            }
            issuedToken = null;
        }
    }

    private record Identity(long id, String name, Instant expiry) { }

    private Identity identity(JsonObject data, String code, String accessToken) throws Failure {
        Instant expires = Instant.parse(text(data, "expires_at"));
        Instant now = clock.instant();
        var scope = data.getAsJsonArray("scope");
        var user = data.getAsJsonObject("user");
        if (!user.get("id").isJsonPrimitive() || !user.get("id").getAsJsonPrimitive().isNumber()) {
            throw new Failure("游戏会话身份无效");
        }
        long id = user.get("id").getAsBigDecimal().longValueExact();
        String name = text(user, "display_name");
        if (id <= 0 || name.isBlank() || name.length() > PvzceConstants.GAME_DISPLAY_NAME_MAX_LENGTH
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                || (code != null && name.contains(code)) || name.contains(accessToken)
                || scope.size() != 1 || !scope.get(0).isJsonPrimitive()
                || !scope.get(0).getAsJsonPrimitive().isString() || !scope.get(0).getAsString().equals("game:session")
                || !expires.isAfter(now) || expires.isAfter(now.plusSeconds(PvzceConstants.GAME_SESSION_MAX_SECONDS))) {
            throw new Failure("游戏会话响应无效");
        }
        return new Identity(id, name, expires);
    }

    private static String text(JsonObject data, String key) throws Failure {
        JsonElement value = data.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new Failure("登录响应无效");
        }
        return value.getAsString();
    }

    /** Frame hook only schedules work; no HTTP or waits on the render thread. */
    public synchronized void tick() {
        expire();
        if (token == null || checking || clock.instant().isBefore(nextCheck)) return;
        checking = true;
        long attempt = generation;
        String currentToken = token;
        worker.execute(() -> {
            try {
                Identity verified = identity(transport.request("GET", SESSION, null, currentToken), null, currentToken);
                synchronized (this) {
                    if (closed || attempt != generation) return;
                    if (verified.id != userId || verified.expiry.isAfter(expiry)) throw new Failure("游戏会话身份不一致");
                    expiry = verified.expiry;
                    nextCheck = clock.instant().plusSeconds(PvzceConstants.GAME_SESSION_CHECK_SECONDS);
                    view = new View(State.ONLINE, "已登录", verified.name, expiry);
                    checking = false;
                }
            } catch (Failure e) {
                failed(attempt, e.getMessage());
            } catch (RuntimeException e) {
                failed(attempt, "游戏会话响应无效");
            }
        });
    }

    private synchronized void failed(long attempt, String message) {
        if (!closed && attempt == generation) offline(message + "，请从启动器重新登录");
    }

    private void expire() {
        if (token != null && !expiry.isAfter(clock.instant())) offline("游戏会话已过期，请从启动器重新登录");
    }

    private void offline(String message) {
        generation++;
        token = null;
        userId = 0;
        expiry = null;
        nextCheck = null;
        checking = false;
        view = new View(State.OFFLINE, message, "", null);
    }

    /** Immediately clears local identity. Server revocation runs once in the background. */
    public synchronized void logout() {
        String currentToken = token;
        offline("已退出账号，继续离线游戏");
        long attempt = generation;
        if (currentToken != null && !closed) worker.execute(() -> {
            try {
                transport.request("POST", LOGOUT, null, currentToken);
            } catch (Failure | RuntimeException e) {
                failed(attempt, "已离线，服务器退出未确认");
            }
        });
    }

    @Override public synchronized void close() {
        if (closed) return;
        logout();
        closed = true;
        if (transport instanceof GameLoginHttp http) worker.execute(http::close);
        worker.shutdown(); // queued logout is bounded by the HTTP timeout; daemon never delays exit
    }

    @Override public String toString() { return "GameAccount[redacted]"; }
}
