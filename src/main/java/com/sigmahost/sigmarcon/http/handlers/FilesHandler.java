package com.sigmahost.sigmarcon.http.handlers;

import com.sigmahost.sigmarcon.SigmaRCON;
import com.sigmahost.sigmarcon.http.PathSecurity;
import com.sigmahost.sigmarcon.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Handles all file management endpoints under /api/files:
 *   GET  /api/files?path=...            -> list a directory or read a file
 *   POST /api/files?path=...            -> overwrite a file's content (raw body)
 *   POST /api/files/create?path=&type=  -> create a file or directory
 *   POST /api/files/upload?path=...     -> upload raw bytes to a new/existing file
 *   GET  /api/files/download?path=...   -> download a file
 *   POST /api/files/zip?path=...        -> zip a file or folder in place
 *   DELETE /api/files?path=...          -> delete a file or folder
 */
public final class FilesHandler implements HttpHandler {

    private final SigmaRCON plugin;
    private final PathSecurity pathSecurity;

    public FilesHandler(SigmaRCON plugin, PathSecurity pathSecurity) {
        this.plugin = plugin;
        this.pathSecurity = pathSecurity;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String uriPath = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {
            if (uriPath.equals("/api/files") && method.equals("GET")) {
                listOrRead(exchange);
            } else if (uriPath.equals("/api/files") && method.equals("POST")) {
                writeFile(exchange);
            } else if (uriPath.equals("/api/files") && method.equals("DELETE")) {
                delete(exchange);
            } else if (uriPath.equals("/api/files/create") && method.equals("POST")) {
                create(exchange);
            } else if (uriPath.equals("/api/files/upload") && method.equals("POST")) {
                upload(exchange);
            } else if (uriPath.equals("/api/files/download") && method.equals("GET")) {
                download(exchange);
            } else if (uriPath.equals("/api/files/zip") && method.equals("POST")) {
                zip(exchange);
            } else if (uriPath.equals("/api/files/unzip") && method.equals("POST")) {
                unzip(exchange);
            } else if (uriPath.equals("/api/files/move") && method.equals("POST")) {
                move(exchange);
            } else if (uriPath.equals("/api/files/copy") && method.equals("POST")) {
                copy(exchange);
            } else {
                sendJson(exchange, 404, Map.of("error", "unknown files endpoint"));
            }
        } catch (SecurityException se) {
            sendJson(exchange, 403, Map.of("error", se.getMessage()));
        } catch (Exception e) {
            plugin.getLogger().warning("FilesHandler error: " + e);
            sendJson(exchange, 500, Map.of("error", "internal error"));
        }
    }

    private String queryParam(HttpExchange exchange, String key) throws UnsupportedEncodingException {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            String k = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
            String v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
            if (k.equals(key)) return v;
        }
        return null;
    }

    private void listOrRead(HttpExchange exchange) throws IOException {
        String rel = Optional.ofNullable(queryParam(exchange, "path")).orElse("");
        File target = pathSecurity.resolve(rel);

        if (!target.exists()) {
            sendJson(exchange, 404, Map.of("error", "not found"));
            return;
        }

        if (target.isDirectory()) {
            List<Map<String, Object>> entries = new ArrayList<>();
            File[] children = target.listFiles();
            if (children != null) {
                for (File f : children) {
                    entries.add(Map.of(
                            "name", f.getName(),
                            "type", f.isDirectory() ? "directory" : "file",
                            "size", f.isFile() ? f.length() : 0,
                            "modified", f.lastModified()
                    ));
                }
            }
            sendJson(exchange, 200, Map.of("path", rel, "type", "directory", "entries", entries));
        } else {
            long maxPreview = 2 * 1024 * 1024; // don't try to inline huge files
            if (target.length() > maxPreview) {
                sendJson(exchange, 200, Map.of(
                        "path", rel, "type", "file", "size", target.length(),
                        "note", "file too large for inline preview, use /api/files/download"
                ));
                return;
            }
            String content = Files.readString(target.toPath(), StandardCharsets.UTF_8);
            sendJson(exchange, 200, Map.of("path", rel, "type", "file", "size", target.length(), "content", content));
        }
    }

    private void writeFile(HttpExchange exchange) throws IOException {
        String rel = queryParam(exchange, "path");
        if (rel == null) { sendJson(exchange, 400, Map.of("error", "missing path")); return; }
        File target = pathSecurity.resolve(rel);
        byte[] body = exchange.getRequestBody().readAllBytes();
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(body);
        }
        sendJson(exchange, 200, Map.of("ok", true, "path", rel, "bytesWritten", body.length));
    }

    private void create(HttpExchange exchange) throws IOException {
        String rel = queryParam(exchange, "path");
        String type = Optional.ofNullable(queryParam(exchange, "type")).orElse("file");
        if (rel == null) { sendJson(exchange, 400, Map.of("error", "missing path")); return; }
        File target = pathSecurity.resolve(rel);

        boolean ok;
        if (type.equalsIgnoreCase("directory")) {
            ok = target.mkdirs();
        } else {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            ok = target.createNewFile();
        }
        sendJson(exchange, ok ? 200 : 409, Map.of("ok", ok, "path", rel));
    }

    private void upload(HttpExchange exchange) throws IOException {
        String rel = queryParam(exchange, "path");
        if (rel == null) { sendJson(exchange, 400, Map.of("error", "missing path")); return; }

        long maxBytes = plugin.getConfig().getLong("files.max-upload-size-mb", 50) * 1024L * 1024L;
        File target = pathSecurity.resolve(rel);
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();

        long written = 0;
        try (InputStream in = exchange.getRequestBody(); OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                written += n;
                if (written > maxBytes) {
                    sendJson(exchange, 413, Map.of("error", "file exceeds max-upload-size-mb"));
                    target.delete();
                    return;
                }
                out.write(buf, 0, n);
            }
        }
        sendJson(exchange, 200, Map.of("ok", true, "path", rel, "bytesWritten", written));
    }

    private void download(HttpExchange exchange) throws IOException {
        String rel = queryParam(exchange, "path");
        if (rel == null) { sendJson(exchange, 400, Map.of("error", "missing path")); return; }
        File target = pathSecurity.resolve(rel);
        if (!target.isFile()) { sendJson(exchange, 404, Map.of("error", "not found")); return; }

        exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + target.getName() + "\"");
        exchange.sendResponseHeaders(200, target.length());
        try (InputStream in = new FileInputStream(target); OutputStream out = exchange.getResponseBody()) {
            in.transferTo(out);
        }
    }

    private void zip(HttpExchange exchange) throws IOException {
        String rel = queryParam(exchange, "path");
        if (rel == null) { sendJson(exchange, 400, Map.of("error", "missing path")); return; }
        File source = pathSecurity.resolve(rel);
        if (!source.exists()) { sendJson(exchange, 404, Map.of("error", "not found")); return; }

        File zipTarget = new File(source.getParentFile(), source.getName() + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipTarget))) {
            if (source.isDirectory()) {
                Path basePath = source.toPath();
                Files.walk(basePath).filter(Files::isRegularFile).forEach(p -> {
                    try {
                        String entryName = basePath.relativize(p).toString().replace("\\", "/");
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } else {
                zos.putNextEntry(new ZipEntry(source.getName()));
                Files.copy(source.toPath(), zos);
                zos.closeEntry();
            }
        }

        sendJson(exchange, 200, Map.of("ok", true, "zipFile", zipTarget.getName()));
    }

    private void delete(HttpExchange exchange) throws IOException {
        String rel = queryParam(exchange, "path");
        if (rel == null) { sendJson(exchange, 400, Map.of("error", "missing path")); return; }
        File target = pathSecurity.resolve(rel);
        boolean ok = deleteRecursively(target);
        sendJson(exchange, ok ? 200 : 404, Map.of("ok", ok, "path", rel));
    }

    private boolean deleteRecursively(File f) {
        if (!f.exists()) return false;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        return f.delete();
    }

    private void move(HttpExchange exchange) throws IOException {
        String from = queryParam(exchange, "from");
        String to = queryParam(exchange, "to");
        if (from == null || to == null) { sendJson(exchange, 400, Map.of("error", "missing from or to")); return; }
        File src = pathSecurity.resolve(from);
        File dest = pathSecurity.resolve(to);
        if (!src.exists()) { sendJson(exchange, 404, Map.of("error", "source not found")); return; }
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();
        boolean ok = src.renameTo(dest);
        sendJson(exchange, ok ? 200 : 409, Map.of("ok", ok));
    }

    private void copy(HttpExchange exchange) throws IOException {
        String from = queryParam(exchange, "from");
        String to = queryParam(exchange, "to");
        if (from == null || to == null) { sendJson(exchange, 400, Map.of("error", "missing from or to")); return; }
        File src = pathSecurity.resolve(from);
        File dest = pathSecurity.resolve(to);
        if (!src.exists()) { sendJson(exchange, 404, Map.of("error", "source not found")); return; }
        File parent = dest.getParentFile();
        if (parent != null) parent.mkdirs();

        if (src.isDirectory()) {
            Path srcPath = src.toPath();
            Path destPath = dest.toPath();
            Files.walk(srcPath).forEach(p -> {
                try {
                    Path target = destPath.resolve(srcPath.relativize(p));
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } else {
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void unzip(HttpExchange exchange) throws IOException {
        String rel = queryParam(exchange, "path");
        if (rel == null) { sendJson(exchange, 400, Map.of("error", "missing path")); return; }
        File zipFile = pathSecurity.resolve(rel);
        if (!zipFile.isFile()) { sendJson(exchange, 404, Map.of("error", "not found")); return; }

        File destDir = new File(zipFile.getParentFile(), zipFile.getName().replaceAll("\\.zip$", ""));
        destDir.mkdirs();
        Path destRoot = destDir.toPath().normalize();

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new FileInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = destRoot.resolve(entry.getName()).normalize();
                // zip-slip guard: refuse any entry that would escape the destination folder
                if (!entryPath.startsWith(destRoot)) {
                    throw new SecurityException("Zip entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
        sendJson(exchange, 200, Map.of("ok", true, "extractedTo", destDir.getName()));
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] body = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
