export interface ApiErrorDetails<T = unknown> {
  status?: number;
  data?: T;
}

interface ErrorLike {
  status?: number;
  data?: unknown;
  response?: { status?: number; data?: unknown };
}

const asObject = <T>(value: unknown): T | undefined =>
  value && typeof value === 'object' ? (value as T) : undefined;

export function apiErrorDetails<T = unknown>(error: unknown): ApiErrorDetails<T> {
  const e = error as ErrorLike | null | undefined;
  return {
    status: e?.status ?? e?.response?.status,
    data: asObject<T>(e?.data ?? e?.response?.data),
  };
}
