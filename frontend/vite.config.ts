/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backend = 'http://localhost:8080';
// Keep the browser's Host header so Spring builds the OAuth redirect URI as localhost:5173.
const keepHost = { target: backend, changeOrigin: false };

// Same-origin in production (D-02): the dev server proxies backend paths to Spring.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': keepHost,
      '/oauth2': keepHost,
      '/login': keepHost,
      '/actuator': backend,
      '/ws': { target: backend, ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/setupTests.ts'],
  },
});
