package com.sigmahost.sigmarcon.http;

import com.sigmahost.sigmarcon.SigmaRCON;
import com.sigmahost.sigmarcon.http.handlers.*;
import com.sigmahost.sigmarcon.monitor.ChunkLagMonitor;
import com.sigmahost.sigmarcon.monitor.LastSeenManager;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;

/** Wires up all /api/* routes on the built-in JDK HTTP server. */
public final class HttpApiServer {

    private final HttpServer server;

    public HttpApiServer(SigmaRCON plugin, ChunkLagMonitor chunkLagMonitor, LastSeenManager lastSeenManager) throws IOException {
        String bind = plugin.getConfig().getString("server.bind-address", "0.0.0.0");
        int port = plugin.getConfig().getInt("server.port", 6031);
        String apiToken = plugin.getConfig().getString("security.api-token", "");
        List<String> allowedOrigins = plugin.getConfig().getStringList("security.allowed-origins");
        PathSecurity pathSecurity = new PathSecurity(plugin.getConfig().getString("files.root-directory", "."));

        this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));

        register("/api/files", new FilesHandler(plugin, pathSecurity), apiToken, allowedOrigins);
        register("/api/console", new ConsoleHandler(plugin), apiToken, allowedOrigins);
        register("/api/console/stream", new ConsoleStreamHandler(plugin), apiToken, allowedOrigins);
        register("/api/players", new PlayersHandler(plugin), apiToken, allowedOrigins);
        register("/api/chunks", new ChunksHandler(chunkLagMonitor), apiToken, allowedOrigins);
        register("/api/lastseen", new LastSeenHandler(lastSeenManager), apiToken, allowedOrigins);

        // Unauthenticated health check, handy for the dashboard to confirm reachability.
        server.createContext("/api/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private void register(String path, HttpHandler handler, String apiToken, List<String> allowedOrigins) {
        server.createContext(path, new SecurityWrapper(handler, apiToken, allowedOrigins));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }
}
