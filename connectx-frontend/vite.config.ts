import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  define: {
    global: 'window',
  },
  esbuild: {
    // Strip informational/debug logging from production builds while keeping
    // warn/error intact, so live issues can still be diagnosed from devtools.
    pure: mode === 'production' ? ['console.log', 'console.debug', 'console.trace'] : [],
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom'],
          stomp: ['@stomp/stompjs', 'sockjs-client'],
        },
      },
    },
  },
  server: {
    port: 5173,
    host: true,
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
}));
