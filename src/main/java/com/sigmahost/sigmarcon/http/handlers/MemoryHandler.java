package com.sigmahost.sigmarcon.http.handlers;

import com.sigmahost.sigmarcon.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** GET /api/memory — JVM heap usage in MB, for the dashboard's RAM gauge. */
public final class MemoryHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long allocatedMb = rt.totalMemory() / (1024 * 1024);

        byte[] body = JsonUtil.toJson(Map.of(
                "usedMb", usedMb,
                "allocatedMb", allocatedMb,
                "maxMb", maxMb
        )).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
