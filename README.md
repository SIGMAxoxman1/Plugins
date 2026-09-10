# SigmaRCON

HTTP API bridge for Paper Minecraft servers, built for sigma-host dashboards
(Cloudflare Pages/Workers or anything else that can call an HTTPS/HTTP API).

## Setup

1. Build via the included GitHub Actions workflow (push to `main`, or run it
   manually from the Actions tab) — grab `SigmaRCON.jar` from the workflow's
   artifacts.
2. Drop the jar in your server's `plugins/` folder and start the server once
   so it generates `plugins/SigmaRCON/config.yml`.
3. Edit `config.yml`:
   - Set `security.api-token` to a long random string.
   - Set `security.allowed-origins` to your site's origin
     (e.g. `https://yoursite.pages.dev`).
4. Restart the server.
5. From your Worker, call the API with the header:
   `Authorization: Bearer <your-api-token>`

## Endpoints

All endpoints below require the `Authorization: Bearer <token>` header
except `/api/health`.

| Method | Path                    | Purpose                                              |
|--------|-------------------------|-------------------------------------------------------|
| GET    | `/api/health`           | Unauthenticated reachability check                     |
| GET    | `/api/files?path=`      | List a directory, or read a file's content             |
| POST   | `/api/files?path=`      | Overwrite a file (raw body = new content)               |
| DELETE | `/api/files?path=`      | Delete a file or folder (recursive)                     |
| POST   | `/api/files/create?path=&type=file\|directory` | Create a new file or folder         |
| POST   | `/api/files/upload?path=` | Upload raw bytes to a file                            |
| GET    | `/api/files/download?path=` | Download a file                                     |
| POST   | `/api/files/zip?path=`  | Zip a file or folder in place                           |
| POST   | `/api/console`          | Body `{"command":"say hi"}` — run a console command     |
| GET    | `/api/console/stream`   | Server-Sent Events: live console log + chunk-lag alerts |
| GET    | `/api/players`          | Online players with live x/y/z/world                    |
| GET    | `/api/lastseen`         | Last known location per player, online or not            |
| GET    | `/api/chunks`           | Current TPS + any chunks flagged as high-load             |

All file paths are relative to the server's working directory
(`files.root-directory` in config.yml) and are sandboxed by `PathSecurity` —
`../` traversal and symlink escapes are blocked.

## Notes / honest limitations

- **Chunk lag detection is a heuristic, not real profiling.** Paper's public
  API doesn't expose true per-chunk MSPT. `ChunkLagMonitor` scores each
  loaded chunk by `entities + tileEntities*2` and flags anything over your
  configured threshold. For precise per-chunk timing, pair this with Spark
  (as discussed) — SigmaRCON's chunk data is a fast, dependency-free proxy,
  good enough to catch "something is very wrong over here" but not exact.
- **Live console is Server-Sent Events, not a raw WebSocket** — the JDK's
  built-in `com.sun.net.httpserver` has no WebSocket support, and SSE avoids
  pulling in an extra dependency. A `fetch()` stream reader or `EventSource`
  on the Worker/frontend side both work with it directly.
- **Console command execution and player lookups always hop onto the main
  server thread** via `Bukkit.getScheduler().runTask(...)`, since Bukkit API
  calls aren't thread-safe from the HTTP server's own thread pool.
