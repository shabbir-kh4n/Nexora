import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The browser calls /api on its own origin; Vite forwards it to the backend, so no CORS change is needed.
const backend = { "/api": { target: "http://127.0.0.1:8080", changeOrigin: false } };

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, strictPort: true, proxy: backend },
  preview: { proxy: backend },
});
