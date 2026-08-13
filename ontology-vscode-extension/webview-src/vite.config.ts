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

  const prodSafe = (v?: string) =>
    mode === 'production' && v && /^https?:\/\/(localhost|127\.0\.0\.1)([:/]|$)/i.test(v) ? '' : v || '';
  return {

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

        '/plugin-service': {
          target: env.VITE_API_PROXY_TARGET || 'http://localhost:80',
          changeOrigin: true,
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

      'process.env.Z_SYNC_FLUSH': '2',
      'process.env.Z_NO_FLUSH': '0',

      '__ONTOCODE_CONFIG__': JSON.stringify({
        IS_WEB_EXTENSION: true,
        CLOUD_GATEWAY_URL: prodSafe(env.VITE_CLOUD_GATEWAY_URL),
        CLOUD_EDITOR_URL: prodSafe(env.VITE_CLOUD_EDITOR_URL),
        CLOUD_PLUGIN_URL: prodSafe(env.VITE_CLOUD_PLUGIN_URL),
        SELF_HOSTED_GATEWAY_URL: env.VITE_SELF_HOSTED_GATEWAY_URL || '',
      }),
    },
    resolve: {
      alias: {
        '@': path.resolve(__dirname, '.'),
        ...(existsSync(zlibShimPath) ? { zlib: zlibShimPath } : {}),
      }
    },

    optimizeDeps: {
      esbuildOptions: {
        define: {
          global: 'globalThis',
        },
      },
    },
    build: {

      sourcemap: false,

      rollupOptions: {

        output: {
          inlineDynamicImports: true,
        },
        treeshake: {
          moduleSideEffects: (id) => {

            if (id.includes('setupGlobals') || id.includes('lucide-react')) {
              return true;
            }
            return false;
          }
        }
      }
    },

    workbox: false,

    manifest: false
  };
});
