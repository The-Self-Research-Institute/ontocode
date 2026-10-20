export function newOpId(): string {
  const cryptoApi = typeof crypto !== 'undefined' ? crypto : undefined;
  return cryptoApi?.randomUUID ? cryptoApi.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
