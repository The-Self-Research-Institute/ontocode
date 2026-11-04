import crypto from 'crypto';
import os from 'os';

export const DESKTOP_E2E_PASSWORD =
  process.env.DESKTOP_E2E_PASSWORD ||
  crypto
    .createHash('sha256')
    .update(`ontocode-e2e:${os.hostname()}:${os.userInfo().username}`)
    .digest('hex')
    .slice(0, 32);
