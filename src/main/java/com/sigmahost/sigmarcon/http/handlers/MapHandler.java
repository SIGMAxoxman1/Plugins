package com.sigmahost.sigmarcon.http.handlers;

import com.sigmahost.sigmarcon.SigmaRCON;
import com.sigmahost.sigmarcon.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * GET /api/map?world=&cx=&cz=&radius=&step=
 * Returns a grid of surface-block colors around (cx, cz) for a simple
 * top-down terrain view. This is a lightweight in-house renderer, not a
 * full map plugin like BlueMap/Dynmap — no caching, no tile pyramid.
 *
 * PERFORMANCE NOTE: sampling unloaded chunks forces them to load
 * synchronously on the main thread. Keep radius/step conservative
 * (defaults below) and don't poll this endpoint continuously — treat it
 * as "load on demand when the player opens the map", not a live feed.
 */
public final class MapHandler implements HttpHandler {

    private static final int MAX_RADIUS = 80;
    private static final int MIN_STEP = 1;

    private final SigmaRCON plugin;

    public MapHandler(SigmaRCON plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
        String worldName = q.getOrDefault("world", "world");
        int cx = parseIntSafe(q.get("cx"), 0);
        int cz = parseIntSafe(q.get("cz"), 0);
        int radius = Math.min(MAX_RADIUS, Math.max(8, parseIntSafe(q.get("radius"), 48)));
        int step = Math.max(MIN_STEP, parseIntSafe(q.get("step"), 2));

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) { future.complete(null); return; }

            List<String> rows = new ArrayList<>();
            for (int z = cz - radius; z <= cz + radius; z += step) {
                StringBuilder row = new StringBuilder();
                for (int x = cx - radius; x <= cx + radius; x += step) {
                    Block surface = findSurfaceBlock(world, x, z);
                    row.append(colorFor(surface.getType())).append(',');
                }
                rows.add(row.toString());
            }
            future.complete(Map.of(
                    "world", worldName,
                    "environment", world.getEnvironment().name(),
                    "cx", cx, "cz", cz, "radius", radius, "step", step,
                    "rows", rows
            ));
        });

        try {
            Map<String, Object> result = future.get(20, TimeUnit.SECONDS);
            if (result == null) { sendJson(exchange, 404, Map.of("error", "world not found")); return; }
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", "map scan failed or timed out"));
        }
    }

    private Block findSurfaceBlock(World world, int x, int z) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            for (int y = 100; y > world.getMinHeight(); y--) {
                Block b = world.getBlockAt(x, y, z);
                if (b.getType().isSolid() && b.getType() != Material.BEDROCK) return b;
            }
            return world.getBlockAt(x, world.getMinHeight(), z);
        }
        return world.getHighestBlockAt(x, z);
    }

    /** Maps a surface material to a short hex color code for the frontend canvas. */
    private String colorFor(Material m) {
        switch (m) {
            case GRASS_BLOCK: case SHORT_GRASS: case TALL_GRASS: return "4a7c3f";
            case SAND: case RED_SAND: return "ddc87a";
            case WATER: return "2f5c8f";
            case LAVA: return "cf4b17";
            case SNOW: case SNOW_BLOCK: case POWDER_SNOW: return "eef2f5";
            case ICE: case PACKED_ICE: case BLUE_ICE: return "a9d3e6";
            case STONE: case COBBLESTONE: case ANDESITE: case DIORITE: return "8a8a8a";
            case GRANITE: return "9c6b5a";
            case DIRT: case COARSE_DIRT: case ROOTED_DIRT: return "6b4a2f";
            case PODZOL: case MYCELIUM: return "5c4a3d";
            case NETHERRACK: return "5c2626";
            case SOUL_SAND: case SOUL_SOIL: return "40332a";
            case CRIMSON_NYLIUM: return "8c2b2b";
            case WARPED_NYLIUM: return "1f7a6c";
            case BASALT: case BLACKSTONE: return "3a3a3f";
            case END_STONE: return "d8d29c";
            case OBSIDIAN: return "16121f";
            case GRAVEL: return "78716a";
            case MUD: case CLAY: return "6f6f7a";
            default:
                if (m.name().contains("LOG") || m.name().contains("WOOD")) return "6b4a2f";
                if (m.name().contains("LEAVES")) return "3f6b2f";
                return m.isSolid() ? "6f6f6f" : "1c1f25";
        }
    }

    private Map<String, String> parseQuery(String raw) throws IOException {
        Map<String, String> map = new HashMap<>();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            map.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"), URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
        }
        return map;
    }

    private int parseIntSafe(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
