import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";

// Dev-only proxy: the browser talks to /api/* on the SAME origin Vite
// serves from, and Vite forwards that to the real FastAPI backend. This
// sidesteps CORS entirely during development (no need to add CORS
// middleware to the Python app just for local dev) - see src/api/client.ts
// for how VITE_API_BASE_URL controls this in production, where the built
// static files are served BY FastAPI itself (see README), so there's no
// cross-origin request happening there either.
export default defineConfig({
  plugins: [react()],
  resolve: {
    // Mirrors tsconfig.json's "paths" entry - that config only makes
    // tsc/your editor understand "@/..." imports, it does NOT affect
    // actual bundling, which is Rollup's job under the hood. Without
    // this too, `tsc -b` passes clean but `vite build` fails to
    // resolve every "@/..." import.
    //
    // __dirname isn't available here - this file is loaded as an ES
    // module (package.json has "type": "module"), so the path has to
    // be derived from import.meta.url instead.
    alias: [{ find: "@", replacement: fileURLToPath(new URL("./src", import.meta.url)) }],
  },
  server: {
    proxy: {
      "/api": {
        target: process.env.VITE_DEV_API_TARGET ?? "http://localhost:8000",
        changeOrigin: true,
        rewrite: (urlPath) => urlPath.replace(/^\/api/, ""),
      },
    },
  },
});
