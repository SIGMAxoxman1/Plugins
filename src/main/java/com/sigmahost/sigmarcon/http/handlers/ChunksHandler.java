package com.sigmahost.sigmarcon.http.handlers;

import com.sigmahost.sigmarcon.monitor.ChunkLagMonitor;
import com.sigmahost.sigmarcon.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** GET /api/chunks — current TPS plus any chunks flagged as high-load. */
public final class ChunksHandler implements HttpHandler {

    private final ChunkLagMonitor chunkLagMonitor;

    public ChunksHandler(ChunkLagMonitor chunkLagMonitor) {
        this.chunkLagMonitor = chunkLagMonitor;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, Object> payload = Map.of(
                "tps", chunkLagMonitor.getCurrentTps(),
                "hotChunks", chunkLagMonitor.getHotChunksAsJson()
        );
        byte[] body = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
