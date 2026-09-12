package com.sigmahost.sigmarcon.http.handlers;

import com.sigmahost.sigmarcon.SigmaRCON;
import com.sigmahost.sigmarcon.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** POST /api/console  body: {"command":"say hello"} — runs a console command. */
public final class ConsoleHandler implements HttpHandler {

    private final SigmaRCON plugin;

    public ConsoleHandler(SigmaRCON plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJson(exchange, 405, Map.of("error", "use POST"));
            return;
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        exchange.getRequestBody().transferTo(buf);
        String command = extractCommand(buf.toString(StandardCharsets.UTF_8));

        if (command == null || command.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "missing 'command'"));
            return;
        }

        // Bukkit API must be touched on the main server thread, never from
        // the HTTP handler's own thread pool.
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                ConsoleCommandSender sender = Bukkit.getConsoleSender();
                future.complete(Bukkit.dispatchCommand(sender, command));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            boolean ok = future.get(5, TimeUnit.SECONDS);
            sendJson(exchange, 200, Map.of("ok", ok, "command", command));
        } catch (TimeoutException te) {
            sendJson(exchange, 202, Map.of("ok", true, "note", "command dispatched, confirmation timed out"));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", "command failed: " + e.getMessage()));
        }
    }

    /** Tiny extractor for {"command":"..."} without pulling in a JSON library. */
    private String extractCommand(String body) {
        int idx = body.indexOf("\"command\"");
        if (idx < 0) return body.trim(); // also accept a plain-text body
        int colon = body.indexOf(':', idx);
        int firstQuote = body.indexOf('"', colon + 1);
        int secondQuote = body.indexOf('"', firstQuote + 1);
        while (secondQuote > 0 && body.charAt(secondQuote - 1) == '\\') {
            secondQuote = body.indexOf('"', secondQuote + 1);
        }
        if (firstQuote < 0 || secondQuote < 0) return null;
        return body.substring(firstQuote + 1, secondQuote).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
