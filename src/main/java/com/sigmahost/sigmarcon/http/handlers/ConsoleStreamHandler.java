package com.sigmahost.sigmarcon.http.handlers;

import com.sigmahost.sigmarcon.SigmaRCON;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;

/**
 * GET /api/console/stream — Server-Sent Events feed of live console lines
 * and chunk-lag alerts. Used instead of a raw WebSocket because the
 * built-in com.sun.net.httpserver has no WebSocket support. Works fine
 * with a browser's EventSource() or a fetch() streaming reader.
 */
public final class ConsoleStreamHandler implements HttpHandler {

    private final SigmaRCON plugin;

    public ConsoleStreamHandler(SigmaRCON plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!plugin.getConfig().getBoolean("console.stream-enabled", true)) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        OutputStream out = exchange.getResponseBody();
        ConsoleBroadcaster.subscribe(out);
        // Left open on purpose: com.sun.net.httpserver has no clean
        // disconnect event, so a dead subscriber is dropped the next
        // time a write to it fails, inside ConsoleBroadcaster.broadcast.
    }
}
