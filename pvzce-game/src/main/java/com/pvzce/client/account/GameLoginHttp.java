package com.pvzce.client.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.common.PvzceConstants;
import com.pvzce.launcher.GameLoginArguments;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Isolated client: no ambient cookies, authenticator, proxy, redirects or wire logging. */
final class GameLoginHttp implements GameAccount.Transport, AutoCloseable {
    private final URI origin;
    private final HttpClient http;

    GameLoginHttp(String api) {
        origin = URI.create(GameLoginArguments.normalizeOrigin(api));
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(PvzceConstants.GAME_LOGIN_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
    }

    @Override
    public JsonObject request(String method, String path, JsonObject body, String token) throws GameAccount.Failure {
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(PvzceConstants.GAME_LOGIN_TIMEOUT_SECONDS))
                .header("Accept", "application/json").header("Cache-Control", "no-store");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        byte[] payload = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        var request = builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        var pending = http.sendAsync(request, info -> HttpResponse.BodySubscribers.limiting(
                HttpResponse.BodySubscribers.ofByteArray(), PvzceConstants.GAME_LOGIN_RESPONSE_BYTES));
        byte[] response = null;
        try {
            var result = pending.get(PvzceConstants.GAME_LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            response = result.body();
            if (path.equals(GameAccount.LOGOUT) && result.statusCode() == 204) return null;
            if (result.statusCode() != 200) throw new GameAccount.Failure(status(result.statusCode()));
            if (path.equals(GameAccount.LOGOUT)) throw new GameAccount.Failure("退出响应无效");
            String type = result.headers().firstValue("Content-Type").orElse("");
            if (!type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                throw new GameAccount.Failure("登录响应无效");
            }
            return JsonParser.parseString(new String(response, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("data");
        } catch (GameAccount.Failure e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameAccount.Failure("登录请求已取消");
        } catch (Exception e) {
            // Never retain the exception cause: it can contain headers or arbitrary response text.
            throw new GameAccount.Failure("网络超时或登录响应无效");
        } finally {
            pending.cancel(true);
            Arrays.fill(payload, (byte) 0);
            if (response != null) Arrays.fill(response, (byte) 0);
        }
    }

    private static String status(int status) {
        return switch (status) {
            case 400 -> "登录参数无效";
            case 401 -> "登录凭证已过期、使用或撤销";
            case 403 -> "账号或游戏权限不可用";
            case 404 -> "服务器尚未部署游戏登录";
            case 409 -> "登录状态冲突";
            case 429 -> "登录请求过于频繁";
            case 503 -> "服务器尚未配置游戏登录";
            default -> "登录服务暂不可用";
        };
    }

    @Override public void close() { http.shutdownNow(); }
    @Override public String toString() { return "GameLoginHttp[redacted]"; }
}
