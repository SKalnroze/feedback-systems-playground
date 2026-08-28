import { fileURLToPath, URL } from "node:url";

import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  server: {
    port: 5173,
    // Proxying in dev means the app talks to a same-origin /api in every environment, so the
    // client never needs to know where the backend lives.
    proxy: {
      "/api": {
        target: process.env["FSP_API_URL"] ?? "http://localhost:8080",
        changeOrigin: true,
        // The dev proxy streams responses as they arrive, which server-sent events depend on.
        ws: false,
      },
    },
  },
  build: {
    outDir: "dist",
    sourcemap: true,
    rollupOptions: {
      output: {
        // Charting and the graph editor are large and change rarely; splitting them keeps the
        // main bundle small enough to parse quickly on a cold load.
        manualChunks: {
          echarts: ["echarts"],
          flow: ["@xyflow/react"],
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
