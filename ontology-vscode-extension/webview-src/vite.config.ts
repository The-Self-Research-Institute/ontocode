import path from 'path';
import { existsSync, readFileSync } from 'fs';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import istanbul from 'vite-plugin-istanbul';

function readExtensionPackage(): { version: string } {
  const candidates = [
    path.resolve(__dirname, '../package.json'),
    path.resolve(__dirname, 'extension-package.json'),
  ];
  for (const candidate of candidates) {
    if (existsSync(candidate)) {
      return JSON.parse(readFileSync(candidate, 'utf8'));
    }
  }
  return JSON.parse(readFileSync(path.resolve(__dirname, 'package.json'), 'utf8'));
}

const extensionPackage = readExtensionPackage();
const zlibShimPath = path.resolve(__dirname, '../src/zlib-shim.js');

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  return {
    // Use absolute paths from root for production (cloud server), relative for dev (VSCode webview)
    base: mode === 'production' ? '/' : './',
    server: {
      port: 3001,
      host: '0.0.0.0',
      proxy: {
        '/api': {
          target: env.VITE_API_PROXY_TARGET || 'http://localhost:80',
          changeOrigin: true,
        },
        '/ws': {
          target: env.VITE_API_PROXY_TARGET || 'http://localhost:80',
          changeOrigin: true,
          ws: true,
        },
      },
    },
    plugins: [
      react(),
      ...(process.env.COVERAGE === 'true' ? [istanbul({
        include: ['**/*.ts', '**/*.tsx'],
        exclude: ['node_modules/**', 'dist/**', 'plugins/**', '**/*.d.ts'],
        extension: ['.ts', '.tsx'],
        requireEnv: false,
        forceBuildInstrument: false,
      })] : []),
    ],
    define: {
      __APP_VERSION__: JSON.stringify(extensionPackage.version),
      'global': 'globalThis',
      // Note: no LLM API key is injected into the client bundle. AI insights use a
      // bring-your-own-key model — the user's key stays in their browser at runtime.
      // Provide zlib constants as global defines
      'process.env.Z_SYNC_FLUSH': '2',
      'process.env.Z_NO_FLUSH': '0',
      // Compile-time config for standalone (browser/cloud) mode.
      // Empty strings fall through to DEFAULTS in deploymentConfig.ts
      // (e.g. CLOUD_GATEWAY_URL → 'https://ontocodeapi.selfresearch.org').
      // window.__ONTOCODE_CONFIG__ injected by extension.ts is only read
      // by apiClient.ts (property access, not bare identifier) so this
      // define does NOT conflict with the runtime injection.
      '__ONTOCODE_CONFIG__': JSON.stringify({
        IS_WEB_EXTENSION: true,
        CLOUD_GATEWAY_URL: env.VITE_CLOUD_GATEWAY_URL || '',
        CLOUD_EDITOR_URL: env.VITE_CLOUD_EDITOR_URL || '',
        CLOUD_PLUGIN_URL: env.VITE_CLOUD_PLUGIN_URL || '',
        SELF_HOSTED_GATEWAY_URL: env.VITE_SELF_HOSTED_GATEWAY_URL || '',
      }),
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
        ...(existsSync(zlibShimPath) ? { zlib: zlibShimPath } : {}),
      }
    },
    build: {
      // Disable source maps in production to reduce size
      sourcemap: false,
      // Ensure lucide-react is not tree-shaken since plugins need it globally
      rollupOptions: {
        // Inline dynamic imports into a single JS bundle. VS Code webviews load
        // the main script from a rewritten webview-resource URI, but code-split
        // chunks are resolved at runtime against the vscode-webview:// document
        // origin (the <base> tag is intentionally removed for anchor navigation).
        // That makes lazy chunks 404 at runtime, so lazy routes (billing/upgrade,
        // desktop download, payment) crash with "Something went wrong". A single
        // bundle removes runtime chunk loading entirely and fixes those routes.
        output: {
          inlineDynamicImports: true,
        },
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
