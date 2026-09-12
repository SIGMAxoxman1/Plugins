package com.sigmahost.sigmarcon.http;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Confines every file operation to the configured root directory.
 * Blocks path traversal (../), absolute path escapes, and symlink escapes.
 */
public final class PathSecurity {

    private final Path root;

    public PathSecurity(String rootDirectory) {
        try {
            this.root = new File(rootDirectory).getCanonicalFile().toPath().normalize();
        } catch (IOException e) {
            throw new RuntimeException("SigmaRCON: could not resolve root directory: " + rootDirectory, e);
        }
    }

    /**
     * Resolves a user-supplied relative path against the root directory,
     * throwing SecurityException if the result escapes the root.
     * The target does not need to exist yet (safe for "create" operations),
     * but its resolved location must stay inside root.
     */
    public File resolve(String userPath) {
        if (userPath == null) userPath = "";

        String cleaned = userPath.replace("\\", "/");
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);

        Path candidate = root.resolve(cleaned).normalize();

        if (!candidate.equals(root) && !candidate.startsWith(root)) {
            throw new SecurityException("Path escapes root directory: " + userPath);
        }

        // If the target already exists, verify its *real* (symlink-resolved)
        // location also stays inside root — protects against a symlink
        // planted inside root that points outside it.
        if (Files.exists(candidate)) {
            try {
                Path real = candidate.toRealPath();
                if (!real.equals(root) && !real.startsWith(root)) {
                    throw new SecurityException("Path escapes root directory via symlink: " + userPath);
                }
            } catch (IOException e) {
                throw new SecurityException("Could not verify path safety: " + userPath);
            }
        }

        return candidate.toFile();
    }

    public Path getRoot() {
        return root;
    }
}
