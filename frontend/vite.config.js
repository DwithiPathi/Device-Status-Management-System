import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development (`npm run dev`) /api calls are proxied to the status-service,
// exactly like nginx does in the Docker image.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.STATUS_URL ?? 'http://localhost:8080',
    },
  },
});
