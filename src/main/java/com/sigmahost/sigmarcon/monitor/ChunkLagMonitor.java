package com.sigmahost.sigmarcon.monitor;

import com.sigmahost.sigmarcon.SigmaRCON;
import com.sigmahost.sigmarcon.http.handlers.ConsoleBroadcaster;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NOTE ON ACCURACY: Bukkit/Paper's public API does not expose true
 * per-chunk MSPT timing — that level of detail normally requires a real
 * profiler like Spark (as discussed). This monitor uses a practical proxy
 * instead: it counts entities + tile entities per loaded chunk and flags
 * any chunk whose score passes the configured threshold as "hot". It's a
 * heuristic, not a precise timing measurement — pair it with Spark if you
 * need exact per-chunk MSPT numbers.
 */
public final class ChunkLagMonitor {

    private final SigmaRCON plugin;
    private BukkitTask task;
    private final Map<String, ChunkReport> hotChunks = new ConcurrentHashMap<>();

    public ChunkLagMonitor(SigmaRCON plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int intervalSeconds = plugin.getConfig().getInt("chunklag.check-interval-seconds", 10);
        long ticks = Math.max(20L, intervalSeconds * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::scan, ticks, ticks);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void scan() {
        int threshold = plugin.getConfig().getInt("chunklag.warning-threshold-mspt", 50);
        hotChunks.clear();

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                int entities = chunk.getEntities().length;
                int tileEntities = chunk.getTileEntities().length;
                int score = entities + (tileEntities * 2);

                if (score >= threshold) {
                    String key = world.getName() + ":" + chunk.getX() + ":" + chunk.getZ();
                    hotChunks.put(key, new ChunkReport(world.getName(), chunk.getX(), chunk.getZ(), entities, tileEntities, score));

                    ConsoleBroadcaster.broadcast(
                            "[SigmaRCON] High load chunk: world=" + world.getName() +
                            " chunk=(" + chunk.getX() + "," + chunk.getZ() + ")" +
                            " entities=" + entities + " tileEntities=" + tileEntities + " score=" + score
                    );
                }
            }
        }
    }

    public List<Map<String, Object>> getHotChunksAsJson() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChunkReport r : hotChunks.values()) {
            list.add(Map.of(
                    "world", r.world(),
                    "chunkX", r.chunkX(),
                    "chunkZ", r.chunkZ(),
                    "entities", r.entities(),
                    "tileEntities", r.tileEntities(),
                    "score", r.score()
            ));
        }
        return list;
    }

    public double getCurrentTps() {
        try {
            return Bukkit.getServer().getTPS()[0];
        } catch (Throwable t) {
            return -1;
        }
    }

    private record ChunkReport(String world, int chunkX, int chunkZ, int entities, int tileEntities, int score) {}
}
