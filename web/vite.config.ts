import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Served by the Control Plane under /app/ (same origin as the API, so platform cookies and CSRF work).
// The Lab never loads here: it opens on the Lab Gateway's own origin.
export default defineConfig({
  base: "/app/",
  plugins: [react()],
  build: { outDir: "dist", sourcemap: false, assetsInlineLimit: 0 },
  server: {
    port: 5173,
    proxy: { "/v1": "http://127.0.0.1:8080", "/oauth2": "http://127.0.0.1:8080" },
  },
});
