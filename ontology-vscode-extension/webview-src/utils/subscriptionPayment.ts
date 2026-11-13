export type SubscribeResponse = {
  subscriptionId?: string;
  status?: string;
  requiresAction?: boolean;
  clientSecret?: string;
};

export type PaymentResult =
  | { kind: "paid" }
  | { kind: "pending" }
  | { kind: "failed"; message: string };

export type StripeActionHandler = {
  handleNextAction: (options: { clientSecret: string }) => Promise<{
    error?: { message?: string } | null;
  }>;
};

export type StripeLoader = (publishableKey: string) => Promise<StripeActionHandler | null>;

export const BANK_NOT_CONFIRMED_MESSAGE =
  "The bank did not confirm the payment. Try again or use a different card.";
export const CHECKOUT_RESTART_MESSAGE =
  "We could not finish the payment check. Start the checkout again from the plans page.";
export const PAYMENT_PENDING_MESSAGE =
  "Your payment is being confirmed. Your plan will turn on in a minute.";

const PAID_STATUSES = ["active", "trialing"];
const POLL_INTERVAL_MS = 2000;
const POLL_TIMEOUT_MS = 20000;

const isPaidStatus = (status: unknown) =>
  typeof status === "string" && PAID_STATUSES.includes(status.toLowerCase());

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

export async function waitForActiveSubscription(
  fetchStatus: () => Promise<string | null | undefined>,
  intervalMs: number = POLL_INTERVAL_MS,
  timeoutMs: number = POLL_TIMEOUT_MS,
): Promise<boolean> {
  const attempts = Math.max(1, Math.floor(timeoutMs / intervalMs));
  for (let i = 0; i < attempts; i++) {
    try {
      if (isPaidStatus(await fetchStatus())) {
        return true;
      }
    } catch {
    }
    await sleep(intervalMs);
  }
  return false;
}

export async function resolveSubscriptionPayment(
  response: SubscribeResponse | null | undefined,
  publishableKey: string,
  loadStripe: StripeLoader,
  fetchStatus: () => Promise<string | null | undefined>,
): Promise<PaymentResult> {
  if (!response?.requiresAction) {
    if (isPaidStatus(response?.status)) {
      return { kind: "paid" };
    }
    return { kind: "pending" };
  }
  if (!response.clientSecret) {
    return { kind: "failed", message: BANK_NOT_CONFIRMED_MESSAGE };
  }
  if (!publishableKey) {
    return { kind: "failed", message: CHECKOUT_RESTART_MESSAGE };
  }
  try {
    const stripe = await loadStripe(publishableKey);
    if (!stripe) {
      return { kind: "failed", message: BANK_NOT_CONFIRMED_MESSAGE };
    }
    const result = await stripe.handleNextAction({ clientSecret: response.clientSecret });
    if (result?.error) {
      return { kind: "failed", message: BANK_NOT_CONFIRMED_MESSAGE };
    }
  } catch {
    return { kind: "failed", message: BANK_NOT_CONFIRMED_MESSAGE };
  }
  const active = await waitForActiveSubscription(fetchStatus);
  return active ? { kind: "paid" } : { kind: "pending" };
}

export const defaultStripeLoader: StripeLoader = async (publishableKey) => {
  const { loadStripe } = await import("@stripe/stripe-js/pure");
  return (await loadStripe(publishableKey)) as unknown as StripeActionHandler | null;
};
