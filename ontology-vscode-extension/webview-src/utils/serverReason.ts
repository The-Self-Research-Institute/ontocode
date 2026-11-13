export function serverReason(error: any, fallback: string): string {
  const reason =
    error?.data?.error ||
    error?.data?.message ||
    error?.response?.data?.error ||
    error?.response?.data?.message;
  return typeof reason === "string" && reason.trim() ? reason : fallback;
}
