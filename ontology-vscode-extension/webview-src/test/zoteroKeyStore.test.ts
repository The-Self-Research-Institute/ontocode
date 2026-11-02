import { describe, it, expect } from 'vitest';
import { ZOTERO_KEY_SECRET, ZoteroKeyStore } from '../../src/services/zoteroKeyStore';

function makeStore(initialSecret: string | undefined, initialSetting: string) {
  const secrets = new Map<string, string>();
  if (initialSecret) secrets.set(ZOTERO_KEY_SECRET, initialSecret);
  const setting = { value: initialSetting };
  const store = new ZoteroKeyStore(
    {
      get: async (key) => secrets.get(key),
      store: async (key, value) => {
        secrets.set(key, value);
      },
      delete: async (key) => {
        secrets.delete(key);
      },
    },
    {
      read: () => setting.value,
      clear: async () => {
        setting.value = '';
      },
    },
  );
  return { store, secrets, setting };
}

describe('ZoteroKeyStore', () => {
  it('moves a key from settings into secrets and clears the setting', async () => {
    const { store, secrets, setting } = makeStore(undefined, 'abc123');
    await store.load();
    expect(secrets.get(ZOTERO_KEY_SECRET)).toBe('abc123');
    expect(setting.value).toBe('');
    expect(store.get()).toBe('abc123');
  });

  it('keeps an existing secret and still clears the old setting', async () => {
    const { store, secrets, setting } = makeStore('fromSecrets', 'oldSetting');
    await store.load();
    expect(secrets.get(ZOTERO_KEY_SECRET)).toBe('fromSecrets');
    expect(setting.value).toBe('');
    expect(store.get()).toBe('fromSecrets');
  });

  it('reads the secret when nothing needs migrating', async () => {
    const { store } = makeStore('only', '');
    await store.load();
    expect(store.get()).toBe('only');
  });

  it('saves and clears without touching settings values', async () => {
    const { store, secrets, setting } = makeStore(undefined, '');
    await store.set('  newkey ');
    expect(store.get()).toBe('newkey');
    expect(secrets.get(ZOTERO_KEY_SECRET)).toBe('newkey');
    expect(setting.value).toBe('');
    await store.clear();
    expect(store.get()).toBe('');
    expect(secrets.has(ZOTERO_KEY_SECRET)).toBe(false);
  });
});
