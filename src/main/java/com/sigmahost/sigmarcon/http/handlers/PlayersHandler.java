package com.sigmahost.sigmarcon.http.handlers;

import com.sigmahost.sigmarcon.SigmaRCON;
import com.sigmahost.sigmarcon.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** GET /api/players — current online players with live coordinates. */
public final class PlayersHandler implements HttpHandler {

    private final SigmaRCON plugin;

    public PlayersHandler(SigmaRCON plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location loc = p.getLocation();
                list.add(Map.of(
                        "name", p.getName(),
                        "uuid", p.getUniqueId().toString(),
                        "world", loc.getWorld() != null ? loc.getWorld().getName() : "unknown",
                        "x", loc.getX(),
                        "y", loc.getY(),
                        "z", loc.getZ(),
                        "yaw", loc.getYaw(),
                        "pitch", loc.getPitch()
                ));
            }
            future.complete(list);
        });

        try {
            List<Map<String, Object>> players = future.get(5, TimeUnit.SECONDS);
            byte[] body = JsonUtil.toJson(Map.of("players", players)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        } catch (Exception e) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        }
    }
}
