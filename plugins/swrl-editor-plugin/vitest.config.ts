import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.tsx'],
  },
  resolve: {
    dedupe: ['react', 'react-dom'],
  },
});
