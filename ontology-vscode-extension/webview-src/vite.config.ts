import path from 'path';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, '.', '');
    return {
      // Use relative paths for VS Code webview compatibility
      base: './',
      server: {
        port: 3000,
        host: '0.0.0.0',
      },
      plugins: [react()],
      define: {
        'process.env.API_KEY': JSON.stringify(env.GEMINI_API_KEY),
        'process.env.GEMINI_API_KEY': JSON.stringify(env.GEMINI_API_KEY)
      },
      resolve: {
        alias: {
          '@': path.resolve(__dirname, '.'),
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
