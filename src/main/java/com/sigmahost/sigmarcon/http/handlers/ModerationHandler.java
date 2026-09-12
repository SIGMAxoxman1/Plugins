package com.sigmahost.sigmarcon.http.handlers;

import com.sigmahost.sigmarcon.SigmaRCON;
import com.sigmahost.sigmarcon.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * POST /api/moderation/tempban   body: {"player":"Name","minutes":60,"reason":"..."}
 * Kick/ban/ban-ip/kill/gamemode are all real vanilla commands already
 * reachable through /api/console, so this handler only covers the one
 * thing vanilla can't do on its own: a ban with an automatic expiry.
 */
public final class ModerationHandler implements HttpHandler {

    private final SigmaRCON plugin;

    public ModerationHandler(SigmaRCON plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST") || !exchange.getRequestURI().getPath().equals("/api/moderation/tempban")) {
            sendJson(exchange, 404, Map.of("error", "POST /api/moderation/tempban is the only moderation endpoint; kick/ban/ban-ip/kill/gamemode go through /api/console"));
            return;
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        exchange.getRequestBody().transferTo(buf);
        String body = buf.toString(StandardCharsets.UTF_8);

        String playerName = extractString(body, "player");
        String reason = extractString(body, "reason");
        int minutes = extractInt(body, "minutes", 60);

        if (playerName == null) { sendJson(exchange, 400, Map.of("error", "missing player")); return; }
        if (reason == null || reason.isBlank()) reason = "Temporarily banned";

        String finalReason = reason;
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Date expiry = new Date(System.currentTimeMillis() + minutes * 60_000L);
            Bukkit.getBanList(BanList.Type.NAME).addBan(playerName, finalReason, expiry, "SigmaRCON");
            Player p = Bukkit.getPlayerExact(playerName);
            if (p != null) p.kickPlayer(finalReason + " (temp-banned for " + minutes + " min)");
            future.complete(true);
        });

        try {
            future.get(5, TimeUnit.SECONDS);
            sendJson(exchange, 200, Map.of("ok", true, "player", playerName, "minutes", minutes));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", "failed to apply temp ban"));
        }
    }

    private String extractString(String body, String key) {
        int idx = body.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = body.indexOf(':', idx);
        int firstQuote = body.indexOf('"', colon + 1);
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) return null;
        return body.substring(firstQuote + 1, secondQuote);
    }

    private int extractInt(String body, String key, int fallback) {
        int idx = body.indexOf("\"" + key + "\"");
        if (idx < 0) return fallback;
        int colon = body.indexOf(':', idx);
        int end = colon + 1;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) end++;
        try { return Integer.parseInt(body.substring(colon + 1, end).trim()); } catch (Exception e) { return fallback; }
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] respBody = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, respBody.length);
        exchange.getResponseBody().write(respBody);
        exchange.close();
    }
}
