export default {
  content: [
    './index.html',
    './*.{ts,tsx}',
    './components/**/*.{ts,tsx}',
    './contexts/**/*.{ts,tsx}',
    './hooks/**/*.{ts,tsx}',
    './custom-hook/**/*.{ts,tsx}',
    './services/**/*.{ts,tsx}',
    './utils/**/*.{ts,tsx}',
    './config/**/*.{ts,tsx}',
    './plugins/**/*.{ts,tsx}',
    '../../plugins/*/src/**/*.{ts,tsx}',
  ],
  theme: { extend: {} },
  plugins: [],
};
