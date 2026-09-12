package com.sigmahost.sigmarcon;

import com.sigmahost.sigmarcon.http.HttpApiServer;
import com.sigmahost.sigmarcon.http.handlers.ConsoleBroadcaster;
import com.sigmahost.sigmarcon.monitor.ChunkLagMonitor;
import com.sigmahost.sigmarcon.monitor.LastSeenManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class SigmaRCON extends JavaPlugin {

    private HttpApiServer httpApiServer;
    private ChunkLagMonitor chunkLagMonitor;
    private LastSeenManager lastSeenManager;
    private Handler consoleForwarder;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String token = getConfig().getString("security.api-token", "");
        if (token == null || token.isBlank() || token.equals("CHANGE_ME_TO_A_LONG_RANDOM_STRING")) {
            getLogger().warning("=================================================");
            getLogger().warning("SigmaRCON: api-token is still the default value.");
            getLogger().warning("Edit plugins/SigmaRCON/config.yml and restart, or");
            getLogger().warning("every request to the API will be rejected with 401.");
            getLogger().warning("=================================================");
        }

        lastSeenManager = new LastSeenManager(this);
        lastSeenManager.load();
        getServer().getPluginManager().registerEvents(lastSeenManager, this);
        lastSeenManager.startAutosave();

        chunkLagMonitor = new ChunkLagMonitor(this);
        chunkLagMonitor.start();

        // Forward server console log lines into the SSE stream at /api/console/stream
        consoleForwarder = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String msg = record.getMessage();
                if (msg != null) ConsoleBroadcaster.broadcast(msg);
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger.getLogger("").addHandler(consoleForwarder);

        try {
            httpApiServer = new HttpApiServer(this, chunkLagMonitor, lastSeenManager);
            httpApiServer.start();
            getLogger().info("SigmaRCON API listening on " +
                    getConfig().getString("server.bind-address") + ":" +
                    getConfig().getInt("server.port"));
        } catch (IOException e) {
            getLogger().severe("SigmaRCON failed to start HTTP server: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (httpApiServer != null) httpApiServer.stop();
        if (chunkLagMonitor != null) chunkLagMonitor.stop();
        if (lastSeenManager != null) {
            lastSeenManager.stop();
            lastSeenManager.saveNow();
        }
        if (consoleForwarder != null) Logger.getLogger("").removeHandler(consoleForwarder);
    }
}
