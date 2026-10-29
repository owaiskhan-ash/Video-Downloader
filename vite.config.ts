import { defineConfig } from "vite";

// Tauri expects a fixed port and a relative base so the built assets resolve
// from the custom protocol (tauri://localhost) rather than a web root.
export default defineConfig({
  base: "./",
  clearScreen: false,
  server: {
    host: "0.0.0.0", // required: the sandbox preview proxies an external host
    port: 5173,
    strictPort: true,
    // The dev preview is served through a proxied hostname. Without this Vite
    // rejects it with "Blocked request. This host is not allowed."
    allowedHosts: true,
    hmr: { protocol: "ws", host: "localhost" },
    // Relative /api calls are proxied to the Python dev server. The browser
    // must never dial localhost directly — it isn't running in the sandbox.
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8787",
        changeOrigin: true,
        // SSE streams must not be buffered by the proxy.
        configure: (proxy) => {
          proxy.on("proxyRes", (proxyRes) => {
            if (proxyRes.headers["content-type"]?.includes("text/event-stream")) {
              proxyRes.headers["cache-control"] = "no-cache, no-transform";
            }
          });
        },
      },
    },
  },
  preview: {
    host: "0.0.0.0",
    port: 4173,
    allowedHosts: true,
  },
  build: {
    target: "es2022",
    outDir: "dist",
    emptyOutDir: true,
    // Tauri's webview is a modern engine; no need to down-level or polyfill.
    sourcemap: false,
  },
});
