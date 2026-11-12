const fs = require('fs');
const path = require('path');

function loadCloudEnv() {
  const envPath = process.env.ENV_FILE
    ? path.resolve(__dirname, '..', process.env.ENV_FILE)
    : path.resolve(__dirname, '..', 'webview-src', '.env.production');
  const out = {};
  try {
    for (const line of fs.readFileSync(envPath, 'utf-8').split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Za-z0-9_]+)\s*=\s*(.*?)\s*$/);
      if (m && !line.trim().startsWith('#')) out[m[1]] = m[2];
    }
  } catch {
    return out;
  }
  return out;
}

const cloudEnv = loadCloudEnv();

const pick = (...keys) => {
  for (const k of keys) if (cloudEnv[k]) return cloudEnv[k];
  return '';
};

const cloudDefines = () => ({
  'process.env.CLOUD_GATEWAY_URL': JSON.stringify(pick('CLOUD_GATEWAY_URL', 'VITE_CLOUD_GATEWAY_URL')),
  'process.env.CLOUD_EDITOR_URL': JSON.stringify(pick('CLOUD_EDITOR_URL', 'VITE_CLOUD_EDITOR_URL')),
  'process.env.CLOUD_PLUGIN_URL': JSON.stringify(pick('CLOUD_PLUGIN_URL', 'VITE_CLOUD_PLUGIN_URL')),
});

module.exports = { pick, cloudDefines };
