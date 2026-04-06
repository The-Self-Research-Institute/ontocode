import path from 'path';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  return {
    // Use absolute paths from root for production (cloud server), relative for dev (VSCode webview)
    base: mode === 'production' ? '/' : './',
    server: {
      port: 3001,
      host: '0.0.0.0',
      // proxy: {
      //   '/api': {
      //     target: 'http://localhost:80',
      //     changeOrigin: true,
      //   },
      //   '/ws': {
      //     target: 'http://localhost:80',
      //     changeOrigin: true,
      //     ws: true,
      //   },
      // },
    },
    plugins: [react()],
    define: {
      'global': 'globalThis',
      'process.env.API_KEY': JSON.stringify(env.GEMINI_API_KEY),
      'process.env.GEMINI_API_KEY': JSON.stringify(env.GEMINI_API_KEY),
      // Provide zlib constants as global defines
      'process.env.Z_SYNC_FLUSH': '2',
      'process.env.Z_NO_FLUSH': '0',
      // Inject runtime config for standalone (browser / cloud) mode
      // In dev mode, use empty string so requests go through Vite proxy (avoids CORS)
      // Set VITE_CLOUD_GATEWAY_URL in .env.production to override
      '__ONTOCODE_CONFIG__': JSON.stringify({
        IS_WEB_EXTENSION: true,
        CLOUD_GATEWAY_URL: env.VITE_CLOUD_GATEWAY_URL || (mode === 'development' ? '' : 'https://ontocodeapi.selfresearch.org'),
        SELF_HOSTED_GATEWAY_URL: env.VITE_SELF_HOSTED_GATEWAY_URL || (mode === 'development' ? '' : 'http://localhost:80'),
      }),
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
        'zlib': path.resolve(__dirname, '../src/zlib-shim.js')
      }
    },
    build: {
      // Disable source maps in production to reduce size
      sourcemap: false,
      // Ensure lucide-react is not tree-shaken since plugins need it globally
      rollupOptions: {
        treeshake: {
          moduleSideEffects: (id) => {
            // Mark setupGlobals and lucide-react as having side effects
            if (id.includes('setupGlobals') || id.includes('lucide-react')) {
              return true;
            }
            return false;
          }
        }
      }
    },
    // Explicitly disable service worker in Vite
    workbox: false,
    // Disable manifest generation
    manifest: false
  };
});
