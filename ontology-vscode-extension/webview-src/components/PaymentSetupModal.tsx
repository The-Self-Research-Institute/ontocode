import React, { useState, useMemo } from 'react';
import { Elements, PaymentElement, useStripe, useElements } from '@stripe/react-stripe-js';
import { loadStripe, StripeElementsOptions } from '@stripe/stripe-js';
import { X, Loader2, Shield, CheckCircle, CreditCard } from 'lucide-react';
import { usePlanPricing } from '../hooks/usePlanPricing';

function safeRemoveStorage(key: string): void { try { localStorage.removeItem(key); } catch {} }
function safeSetStorage(key: string, value: string): void { try { localStorage.setItem(key, value); } catch {} }

interface PaymentSetupModalProps {
    publishableKey: string;
    clientSecret: string;
    planName: string;
    interval: 'monthly' | 'annual';
    workspaceId: string;
    trialEligible?: boolean;
    currentStatus?: string;
    onConfirmed: (setupIntentId: string) => void | Promise<void>;
    onClose: () => void;
}

// ─── Inner form — must live inside <Elements> ────────────────────────────────

interface PaymentFormProps {
    planName: string;
    interval: 'monthly' | 'annual';
    workspaceId: string;
    trialEligible: boolean;
    currentStatus?: string;
    onConfirmed: (setupIntentId: string) => void | Promise<void>;
    onClose: () => void;
}

const PaymentForm: React.FC<PaymentFormProps> = ({ planName, interval, workspaceId, trialEligible, currentStatus, onConfirmed, onClose }) => {
    const stripe = useStripe();
    const elements = useElements();
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const { getDisplayPrice, trialPeriodDays } = usePlanPricing();
    const price = getDisplayPrice(planName, interval);
    const isEnterprisePlan = planName.toUpperCase() === 'ENTERPRISE';
    const showEnterpriseTrialEndingWarning = isEnterprisePlan && currentStatus?.toLowerCase() === 'trialing';

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!stripe || !elements) return;

        setSubmitting(true);
        setError(null);

        try {
        // Persist params so they survive a 3DS redirect
        safeSetStorage('pendingSubscription', JSON.stringify({ workspaceId, planName, interval }));

        const { error: confirmError, setupIntent } = await stripe.confirmSetup({
            elements,
            confirmParams: {
                return_url: window.location.href.split('?')[0],
            },
            redirect: 'if_required',
        });

        if (confirmError) {
            const isExpired =
                confirmError.code === 'setup_intent_unexpected_state' ||
                (confirmError.message ?? '').toLowerCase().includes('expired');
            setError(isExpired
                ? 'Payment session expired. Please close and try again.'
                : (confirmError.message ?? 'Payment setup failed. Please try again.'));
            safeRemoveStorage('pendingSubscription');
            return;
        }

        if (setupIntent?.status === 'succeeded') {
            // Combine all recovery data into one key — survives a network cut before /subscribe completes
            safeSetStorage('pendingPaymentRecovery', JSON.stringify({ setupIntentId: setupIntent.id, workspaceId, planName, interval }));
            safeRemoveStorage('pendingSubscription');
            await Promise.resolve(onConfirmed(setupIntent.id));
        } else {
            setError('Card setup did not complete. Please try again.');
        }
        } catch (subErr: unknown) {
            const msg = subErr instanceof Error ? subErr.message : 'Subscription could not be completed. Please try again.';
            setError(msg);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <form onSubmit={handleSubmit} className="space-y-5">
            {/* Plan summary */}
            <div className="flex items-center justify-between bg-white/5 border border-white/10 rounded-xl px-4 py-3">
                <div>
                    <p className="text-[11px] uppercase tracking-widest text-gray-400 mb-0.5">Selected plan</p>
                    <p className="text-white font-semibold">{planName.charAt(0) + planName.slice(1).toLowerCase()} Plan</p>
                </div>
                <div className="text-right">
                    <p className="text-purple-300 font-semibold">{price}</p>
                    <p className={`text-[11px] ${trialEligible ? 'text-green-400' : 'text-amber-300'}`}>
                        {trialEligible
                            ? `First ${trialPeriodDays} days free`
                            : isEnterprisePlan
                                ? 'No trial • charged immediately'
                                : 'Charged when activated'}
                    </p>
                </div>
            </div>

            {showEnterpriseTrialEndingWarning && (
                <div className="rounded-xl border border-amber-400/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-100">
                    Enterprise upgrades do not include a new trial. Your Enterprise plan will be charged when you confirm.
                </div>
            )}

            {/* Stripe Payment Element */}
            <div>
                <p className="flex items-center gap-2 text-sm text-gray-300 mb-2">
                    <CreditCard size={15} />
                    Card details
                </p>
                <PaymentElement options={{ layout: 'tabs' }} />
            </div>

            {error && (
                <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-xl text-sm">
                    {error}
                </div>
            )}

            {/* Trust line */}
            <div className="flex items-start gap-2 text-[11px] text-gray-400">
                <Shield size={13} className="text-green-400 mt-0.5 flex-shrink-0" />
                <span>Secured by Stripe. Paid plans renew automatically with reminders at 15, 7, and 1 day. Canceling blocks workspace access until renewed.</span>
            </div>
            <div className="hidden">
                <Shield size={13} className="text-green-400 mt-0.5 flex-shrink-0" />
                <span>
                    {trialEligible
                        ? `Secured by Stripe — card not charged for ${trialPeriodDays} days — cancel any time before trial ends`
                        : isEnterprisePlan
                            ? 'Secured by Stripe — Enterprise upgrades are charged immediately and do not include a new trial'
                        : 'Secured by Stripe — your trial has already been used and your card will be charged when activated'}
                </span>
            </div>

            {/* Actions */}
            <div className="flex gap-3 pt-1">
                <button
                    type="button"
                    onClick={onClose}
                    disabled={submitting}
                    className="flex-1 py-3 rounded-xl border border-white/20 bg-white/5 text-gray-300 font-medium hover:bg-white/10 transition-all disabled:opacity-40"
                >
                    Cancel
                </button>
                <button
                    type="submit"
                    disabled={submitting || !stripe || !elements}
                    className="flex-[2] py-3 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 text-white font-semibold hover:from-purple-700 hover:to-indigo-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg shadow-purple-600/30"
                >
                    {submitting ? (
                        <>
                            <Loader2 size={18} className="animate-spin" />
                            Setting up…
                        </>
                    ) : (
                        <>
                            <CheckCircle size={18} />
                            {trialEligible ? `Start ${trialPeriodDays}-day free trial` : 'Confirm and pay'}
                        </>
                    )}
                </button>
            </div>
        </form>
    );
};

// ─── Modal shell ─────────────────────────────────────────────────────────────

const PaymentSetupModal: React.FC<PaymentSetupModalProps> = ({
    publishableKey,
    clientSecret,
    planName,
    interval,
    workspaceId,
    trialEligible = true,
    currentStatus,
    onConfirmed,
    onClose,
}) => {
    const stripePromise = useMemo(() => loadStripe(publishableKey), [publishableKey]);
    const { trialPeriodDays } = usePlanPricing();

    const options: StripeElementsOptions = {
        clientSecret,
        appearance: {
            theme: 'night',
            variables: {
                colorPrimary: '#8b5cf6',
                colorBackground: '#1e1b4b',
                colorText: '#e2e8f0',
                colorTextSecondary: '#94a3b8',
                colorDanger: '#f87171',
                borderRadius: '10px',
                fontFamily: 'system-ui, -apple-system, sans-serif',
                spacingUnit: '4px',
            },
            rules: {
                '.Input': {
                    backgroundColor: 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.15)',
                    color: '#e2e8f0',
                    boxShadow: 'none',
                },
                '.Input:focus': {
                    border: '1px solid #8b5cf6',
                    boxShadow: '0 0 0 3px rgba(139,92,246,0.25)',
                    outline: 'none',
                },
                '.Label': {
                    color: '#94a3b8',
                    fontWeight: '500',
                },
                '.Tab': {
                    backgroundColor: 'rgba(255,255,255,0.04)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    color: '#94a3b8',
                },
                '.Tab--selected': {
                    backgroundColor: 'rgba(139,92,246,0.15)',
                    border: '1px solid rgba(139,92,246,0.5)',
                    color: '#c4b5fd',
                },
            },
        },
    };

    return (
        <div className="fixed inset-0 z-[100] bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
            <div className="dark-surface relative w-full max-w-md h-[85vh] max-h-[720px] bg-gradient-to-b from-slate-900 to-indigo-950 border border-white/15 rounded-2xl shadow-2xl overflow-hidden flex flex-col">

                {/* Header */}
                <div className="flex items-center justify-between px-6 py-5 border-b border-white/10 flex-shrink-0">
                    <div>
                        <h2 className="text-lg font-bold text-white">
                            {trialEligible ? 'Start your free trial' : 'Activate your plan'}
                        </h2>
                        <p className="text-xs text-gray-400 mt-0.5">
                            {trialEligible
                                ? `Card saved securely — charged only after ${trialPeriodDays} days`
                                : 'Your trial was already used — your card will be charged when activated'}
                        </p>
                    </div>
                    <button
                        onClick={onClose}
                        className="w-8 h-8 flex items-center justify-center rounded-full bg-white/10 hover:bg-white/20 text-gray-400 hover:text-white transition-colors"
                        aria-label="Close"
                    >
                        <X size={16} />
                    </button>
                </div>

                {/* Form */}
                <div className="px-6 py-6 overflow-y-auto flex-1">
                    <Elements stripe={stripePromise} options={options}>
                        <PaymentForm
                            planName={planName}
                            interval={interval}
                            workspaceId={workspaceId}
                            trialEligible={trialEligible}
                            currentStatus={currentStatus}
                            onConfirmed={onConfirmed}
                            onClose={onClose}
                        />
                    </Elements>
                </div>
            </div>
        </div>
    );
};

export default PaymentSetupModal;
