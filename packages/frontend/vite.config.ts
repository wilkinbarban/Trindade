import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:3000';

const appBase = process.env.VITE_APP_BASE || '/';

export default defineConfig({
  base: appBase,
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
      '/p': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        manualChunks: {
          'export-vendor': ['jspdf', 'html2canvas'],
        },
      },
    },
  },
});
