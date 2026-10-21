import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// The dev server proxies the API to a running runtime: `nanomuse serve` on its
// default port, or wherever NANOMUSE_API points (e.g. NANOMUSE_API=127.0.0.1:8799).
const api = (process.env.NANOMUSE_API ?? "127.0.0.1:8787").replace(/^https?:\/\//, "");

// The production build is written straight into the Python package so that
// `pip install nanomuse` ships the app and `nanomuse serve` can serve it.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: "../vendor/nanomuse/server/static",
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    proxy: {
      "/api": `http://${api}`,
      "/ws": { target: `ws://${api}`, ws: true },
    },
  },
});
