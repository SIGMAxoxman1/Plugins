package com.sigmahost.sigmarcon.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;

/** Wraps every route with Bearer-token auth + basic CORS handling. */
public final class SecurityWrapper implements HttpHandler {

    private final HttpHandler delegate;
    private final String apiToken;
    private final List<String> allowedOrigins;

    public SecurityWrapper(HttpHandler delegate, String apiToken, List<String> allowedOrigins) {
        this.delegate = delegate;
        this.apiToken = apiToken;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && (allowedOrigins.isEmpty() || allowedOrigins.contains(origin))) {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
        }
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Authorization, Content-Type");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        boolean tokenSet = apiToken != null && !apiToken.isBlank()
                && !apiToken.equals("CHANGE_ME_TO_A_LONG_RANDOM_STRING");

        if (!tokenSet || auth == null || !auth.equals("Bearer " + apiToken)) {
            byte[] body = "{\"error\":\"unauthorized\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }

        delegate.handle(exchange);
    }
}
