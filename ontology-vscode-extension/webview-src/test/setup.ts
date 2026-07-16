import { vi } from 'vitest';

vi.mock('../config/deploymentConfig', () => ({
  getGatewayUrl: () => 'http://localhost:8080',
  getStoredDeploymentType: () => 'cloud',
}));

vi.mock('../utils/desktop', () => ({
  isDesktop: () => false,
}));

Object.defineProperty(window, 'vscode', {
  value: { postMessage: () => {} },
  writable: true,
});

Object.defineProperty(window, 'electronAPI', {
  value: undefined,
  writable: true,
});

Object.defineProperty(window, '__ONTOCODE_CONFIG__', {
  value: {
    IS_WEB_EXTENSION: true,
    CLOUD_GATEWAY_URL: '',
    SELF_HOSTED_GATEWAY_URL: '',
  },
  writable: true,
});

Object.defineProperty(window, '__DESKTOP_API_URL__', {
  value: undefined,
  writable: true,
});
