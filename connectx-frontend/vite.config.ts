import { defineConfig, Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

// Stamps public/sw.js's BUILD_VERSION placeholder with a hash of this build's
// actual output filenames, so the service worker's bytes genuinely change
// whenever — and only whenever — the frontend build output changes. Browsers
// detect a new SW purely by byte-diffing the script file itself, so without
// this the SW would be byte-identical across ordinary app deploys and no
// update would ever be detected (see BUILD_VERSION's comment in sw.js).
// Two hooks are needed: generateBundle sees the finished in-memory bundle
// (for the content fingerprint) before anything is written; closeBundle
// fires only after Vite has finished writing chunks AND copying publicDir
// (which is where dist/sw.js first appears), so that's the safe point to
// rewrite it.
function stampServiceWorkerVersion(): Plugin {
  let version = '';
  let outDir = 'dist';
  return {
    name: 'connectx-stamp-sw-version',
    apply: 'build',
    generateBundle(options, bundle) {
      outDir = options.dir || outDir;
      const fingerprint = Object.keys(bundle).sort().join('|');
      version = createHash('sha256').update(fingerprint).digest('hex').slice(0, 10);
    },
    closeBundle() {
      const swPath = path.join(outDir, 'sw.js');
      if (!version || !existsSync(swPath)) return;
      const contents = readFileSync(swPath, 'utf8');
      writeFileSync(swPath, contents.replace('__BUILD_VERSION__', version));
    },
  };
}

export default defineConfig(({ mode }) => ({
  plugins: [react(), stampServiceWorkerVersion()],
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
