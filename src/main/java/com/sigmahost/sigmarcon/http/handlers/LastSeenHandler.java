package com.sigmahost.sigmarcon.http.handlers;

import com.sigmahost.sigmarcon.monitor.LastSeenManager;
import com.sigmahost.sigmarcon.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** GET /api/lastseen — last known location for every player ever tracked (online or not). */
public final class LastSeenHandler implements HttpHandler {

    private final LastSeenManager lastSeenManager;

    public LastSeenHandler(LastSeenManager lastSeenManager) {
        this.lastSeenManager = lastSeenManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        byte[] body = JsonUtil.toJson(Map.of("players", lastSeenManager.getAll())).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
