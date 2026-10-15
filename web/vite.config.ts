import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev-mode proxy sends API, SSE, and git traffic to the Clojure server;
// in production the Clojure server serves the built files itself.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": { target: "http://localhost:8070" },
      "/r": { target: "http://localhost:8070" },
    },
  },
});
