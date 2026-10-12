import { defineConfig } from 'vitest/config';
import path from 'path';

export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./test/setup.ts'],
    include: ['test/**/*.test.ts', 'test/**/*.test.tsx'],
    coverage: {
      provider: 'v8',
      include: ['**/*.{ts,tsx}'],
      exclude: ['test/**', 'node_modules/**', 'dist/**', 'coverage/**', '**/*.d.ts', '*.config.ts'],
      reporter: ['lcov', 'text-summary'],
      reportsDirectory: 'coverage',
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './'),
    },
  },
});
