import path from 'path';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, '.', '');
    return {
      // Use relative paths for VS Code webview compatibility
      base: './',
      server: {
        port: 3001,
        host: '0.0.0.0',
      },
      plugins: [react()],
      define: {
        'process.env.API_KEY': JSON.stringify(env.GEMINI_API_KEY),
        'process.env.GEMINI_API_KEY': JSON.stringify(env.GEMINI_API_KEY),
        // Provide zlib constants as global defines
        'process.env.Z_SYNC_FLUSH': '2',
        'process.env.Z_NO_FLUSH': '0',
        // Inject runtime config for standalone (browser / cloud) mode
        // Set VITE_CLOUD_GATEWAY_URL in .env.production to override
        '__ONTOCODE_CONFIG__': JSON.stringify({
          IS_WEB_EXTENSION: true,
          CLOUD_GATEWAY_URL: env.VITE_CLOUD_GATEWAY_URL || 'https://ontocodeapi.selfresearch.org',
          SELF_HOSTED_GATEWAY_URL: env.VITE_SELF_HOSTED_GATEWAY_URL || 'http://localhost:80',
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
