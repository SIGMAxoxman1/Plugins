package com.sigmahost.sigmarcon.http.handlers;

import com.sigmahost.sigmarcon.SigmaRCON;
import com.sigmahost.sigmarcon.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Player inventory management, all routed through the main server thread:
 *   GET  /api/inventory?player=NAME               -> full inventory (36 main + 4 armor + offhand)
 *   POST /api/inventory/clear-slot?player=&slot=   -> clear a single slot (the "take item" action)
 *   POST /api/inventory/give?player=&item=&count=  -> give an item by Material name
 */
public final class InventoryHandler implements HttpHandler {

    private final SigmaRCON plugin;

    public InventoryHandler(SigmaRCON plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

        try {
            if (path.equals("/api/inventory") && method.equals("GET")) {
                getInventory(exchange, query.get("player"));
            } else if (path.equals("/api/inventory/clear-slot") && method.equals("POST")) {
                clearSlot(exchange, query.get("player"), query.get("slot"));
            } else if (path.equals("/api/inventory/give") && method.equals("POST")) {
                giveItem(exchange, query.get("player"), query.get("item"), query.get("count"));
            } else {
                sendJson(exchange, 404, Map.of("error", "unknown inventory endpoint"));
            }
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", "internal error: " + e.getMessage()));
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

    private void getInventory(HttpExchange exchange, String playerName) throws Exception {
        if (playerName == null) { sendJson(exchange, 400, Map.of("error", "missing player")); return; }

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null) { future.complete(null); return; }
            PlayerInventory inv = p.getInventory();

            List<Map<String, Object>> main = new ArrayList<>();
            ItemStack[] contents = inv.getStorageContents();
            for (int i = 0; i < contents.length; i++) main.add(itemJson(i, contents[i]));

            List<Map<String, Object>> armor = new ArrayList<>();
            String[] armorSlots = { "boots", "leggings", "chestplate", "helmet" };
            ItemStack[] armorContents = inv.getArmorContents();
            for (int i = 0; i < armorContents.length; i++) {
                Map<String, Object> j = itemJson(i, armorContents[i]);
                Map<String, Object> withSlot = new HashMap<>(j);
                withSlot.put("armorSlot", armorSlots[i]);
                armor.add(withSlot);
            }

            future.complete(Map.of(
                    "player", p.getName(),
                    "main", main,
                    "armor", armor,
                    "offhand", itemJson(-1, inv.getItemInOffHand())
            ));
        });

        Map<String, Object> result = future.get(5, TimeUnit.SECONDS);
        if (result == null) { sendJson(exchange, 404, Map.of("error", "player not online")); return; }
        sendJson(exchange, 200, result);
    }

    private Map<String, Object> itemJson(int slot, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Map.of("slot", slot, "empty", true);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("slot", slot);
        m.put("empty", false);
        m.put("material", item.getType().name());
        m.put("count", item.getAmount());
        m.put("displayName", item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? item.getItemMeta().getDisplayName() : item.getType().name());
        return m;
    }

    private void clearSlot(HttpExchange exchange, String playerName, String slotStr) throws Exception {
        if (playerName == null || slotStr == null) { sendJson(exchange, 400, Map.of("error", "missing player or slot")); return; }
        int slot = Integer.parseInt(slotStr);

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null) { future.complete(false); return; }
            if (slot == -1) p.getInventory().setItemInOffHand(null);
            else p.getInventory().setItem(slot, null);
            future.complete(true);
        });

        boolean ok = future.get(5, TimeUnit.SECONDS);
        sendJson(exchange, ok ? 200 : 404, Map.of("ok", ok));
    }

    private void giveItem(HttpExchange exchange, String playerName, String itemName, String countStr) throws Exception {
        if (playerName == null || itemName == null) { sendJson(exchange, 400, Map.of("error", "missing player or item")); return; }
        int count = countStr != null ? Math.max(1, Math.min(64, Integer.parseInt(countStr))) : 1;

        Material material;
        try {
            material = Material.valueOf(itemName.toUpperCase());
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", "unknown material: " + itemName));
            return;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null) { future.complete(false); return; }
            p.getInventory().addItem(new ItemStack(material, count));
            future.complete(true);
        });

        boolean ok = future.get(5, TimeUnit.SECONDS);
        sendJson(exchange, ok ? 200 : 404, Map.of("ok", ok));
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
