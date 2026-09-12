package com.sigmahost.sigmarcon.monitor;

import com.sigmahost.sigmarcon.SigmaRCON;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks each player's last known location, saved on quit and on a
 * periodic autosave while they're online, so /api/lastseen can answer
 * "where was this player last seen" even while they're offline.
 */
public final class LastSeenManager implements Listener {

    private final SigmaRCON plugin;
    private final File file;
    private YamlConfiguration data;
    private BukkitTask task;

    public LastSeenManager(SigmaRCON plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lastseen.yml");
    }

    public void load() {
        data = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    public void startAutosave() {
        int interval = plugin.getConfig().getInt("lastseen.save-interval-seconds", 60);
        long ticks = Math.max(20L, interval * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) record(p);
            saveNow();
        }, ticks, ticks);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        record(event.getPlayer());
        saveNow();
    }

    private void record(Player p) {
        Location loc = p.getLocation();
        String base = "players." + p.getUniqueId();
        data.set(base + ".name", p.getName());
        data.set(base + ".world", loc.getWorld() != null ? loc.getWorld().getName() : "unknown");
        data.set(base + ".x", loc.getX());
        data.set(base + ".y", loc.getY());
        data.set(base + ".z", loc.getZ());
        data.set(base + ".timestamp", System.currentTimeMillis());
    }

    public void saveNow() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("SigmaRCON: could not save lastseen.yml: " + e.getMessage());
        }
    }

    public Map<String, Object> getAll() {
        Map<String, Object> out = new HashMap<>();
        if (data.getConfigurationSection("players") == null) return out;
        for (String uuid : data.getConfigurationSection("players").getKeys(false)) {
            String base = "players." + uuid;
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", data.getString(base + ".name"));
            entry.put("world", data.getString(base + ".world"));
            entry.put("x", data.getDouble(base + ".x"));
            entry.put("y", data.getDouble(base + ".y"));
            entry.put("z", data.getDouble(base + ".z"));
            entry.put("timestamp", data.getLong(base + ".timestamp"));
            out.put(uuid, entry);
        }
        return out;
    }
}
