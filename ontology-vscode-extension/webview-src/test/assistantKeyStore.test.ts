import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  apiKeyPersistence,
  getApiKey,
  initApiKeyStore,
  onApiKeyChange,
  resetApiKeyStoreForTests,
  setApiKey,
} from "../services/assistantKeyStore";

const LEGACY = "ontocode_llm_api_key";

function secureBackend(initial = "") {
  let stored = initial;
  return {
    stored: () => stored,
    backend: {
      load: vi.fn(async () => ({ value: stored, secure: true })),
      save: vi.fn(async (key: string) => {
        stored = key;
        return { secure: true };
      }),
    },
  };
}

describe("assistantKeyStore", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => resetApiKeyStoreForTests(null));

  it("moves a key left in localStorage into the secure store and deletes the plain copy", async () => {
    localStorage.setItem(LEGACY, "sk-legacy");
    const secure = secureBackend();
    resetApiKeyStoreForTests(secure.backend);

    await initApiKeyStore(LEGACY);

    expect(getApiKey()).toBe("sk-legacy");
    expect(secure.stored()).toBe("sk-legacy");
    expect(localStorage.getItem(LEGACY)).toBeNull();
    expect(apiKeyPersistence()).toBe("secure");
  });

  it("loads a key already in the secure store", async () => {
    resetApiKeyStoreForTests(secureBackend("sk-saved").backend);

    await initApiKeyStore(LEGACY);

    expect(getApiKey()).toBe("sk-saved");
  });

  it("keeps the key for the session only when the store cannot encrypt it", async () => {
    resetApiKeyStoreForTests({
      load: async () => ({ value: "", secure: false }),
      save: async () => ({ secure: false }),
    });
    await initApiKeyStore(LEGACY);

    await setApiKey("sk-session");

    expect(getApiKey()).toBe("sk-session");
    expect(apiKeyPersistence()).toBe("session");
  });

  it("falls back to the session when the secure store fails", async () => {
    resetApiKeyStoreForTests({
      load: async () => {
        throw new Error("no keychain");
      },
      save: async () => {
        throw new Error("no keychain");
      },
    });
    await initApiKeyStore(LEGACY);

    await setApiKey("sk-x");

    expect(apiKeyPersistence()).toBe("session");
    expect(sessionStorage.getItem("ontocode.assistant.sessionKey")).toBe("sk-x");
  });

  it("tells listeners when the key loads and when it changes", async () => {
    resetApiKeyStoreForTests(secureBackend("sk-1").backend);
    const listener = vi.fn();
    onApiKeyChange(listener);

    await initApiKeyStore(LEGACY);
    await setApiKey("");

    expect(listener).toHaveBeenCalledTimes(2);
    expect(getApiKey()).toBe("");
  });
});
