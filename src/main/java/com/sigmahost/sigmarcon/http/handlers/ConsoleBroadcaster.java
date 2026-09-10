package com.sigmahost.sigmarcon.http.handlers;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared fan-out target for the console SSE stream and chunk-lag alerts. */
public final class ConsoleBroadcaster {

    private static final List<OutputStream> subscribers = new CopyOnWriteArrayList<>();

    private ConsoleBroadcaster() {}

    public static void subscribe(OutputStream out) {
        subscribers.add(out);
    }

    public static void broadcast(String line) {
        String payload = "data: " + line.replace("\n", " ") + "\n\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        for (OutputStream out : subscribers) {
            try {
                out.write(bytes);
                out.flush();
            } catch (IOException e) {
                subscribers.remove(out);
            }
        }
    }
}
