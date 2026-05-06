import React, { useState, useMemo } from 'react';
import { Elements, PaymentElement, useStripe, useElements } from '@stripe/react-stripe-js';
import { loadStripe, StripeElementsOptions } from '@stripe/stripe-js';
import { X, Loader2, CreditCard, XCircle, CheckCircle, Shield, AlertTriangle, Crown } from 'lucide-react';
import { getGatewayUrl } from '../config/deploymentConfig';
import { usePlanPricing } from '../hooks/usePlanPricing';

function safeGetStorage(key: string): string | null { try { return localStorage.getItem(key); } catch { return null; } }

async function fetchWithTimeout(url: string, options: RequestInit, timeoutMs = 15000): Promise<Response> {
    const controller = new AbortController();
    const id = setTimeout(() => controller.abort(), timeoutMs);
    try {
        return await fetch(url, { ...options, signal: controller.signal });
    } catch (err: any) {
        if (err?.name === 'AbortError') throw new Error('Request timed out. Check your connection and try again.');
        throw err;
    } finally {
        clearTimeout(id);
    }
}

interface Workspace {
    workspaceId: string;
    name: string;
    subscriptionPlan: string;
    billingStatus?: string;
    billingInterval?: string;
}

interface ManageSubscriptionModalProps {
    workspace: Workspace;
    onClose: () => void;
    onCancelled: () => void;
    onCompletePayment?: () => void;
    onUpgradePlan?: () => void;
}


function statusLabel(status?: string) {
    const s = (status ?? '').toUpperCase();
    if (s === 'TRIALING' || s === 'ACTIVE') return { label: s === 'TRIALING' ? 'Trial active' : 'Active', color: 'text-green-400 bg-green-400/10 border-green-400/30' };
    if (s === 'PAYMENT_FAILED') return { label: 'Payment failed', color: 'text-red-400 bg-red-400/10 border-red-400/30' };
    if (s === 'PAST_DUE' || s === 'UNPAID') return { label: 'Payment overdue', color: 'text-red-400 bg-red-400/10 border-red-400/30' };
    if (s === 'EXPIRED') return { label: 'Expired', color: 'text-red-400 bg-red-400/10 border-red-400/30' };
    if (s === 'CANCELED') return { label: 'Canceled', color: 'text-gray-400 bg-gray-400/10 border-gray-400/30' };
    return { label: s || 'Active', color: 'text-green-400 bg-green-400/10 border-green-400/30' };
}

// ─── Card update inner form ──────────────────────────────────────────────────

const UpdateCardForm: React.FC<{
    workspaceId: string;
    onSuccess: () => void;
    onCancel: () => void;
}> = ({ workspaceId, onSuccess, onCancel }) => {
    const stripe = useStripe();
    const elements = useElements();
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!stripe || !elements) return;

        setSubmitting(true);
        setError(null);

        const { error: confirmError, setupIntent } = await stripe.confirmSetup({
            elements,
            confirmParams: { return_url: window.location.href.split('?')[0] },
            redirect: 'if_required',
        });

        if (confirmError) {
            setError(confirmError.message ?? 'Card update failed. Please try again.');
            setSubmitting(false);
            return;
        }

        if (setupIntent?.status !== 'succeeded') {
            setError('Card setup did not complete. Please try again.');
            setSubmitting(false);
            return;
        }

        // Call backend to attach the new card to the subscription
        const headers = {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${safeGetStorage('authToken') ?? ''}`,
        };
        const body = JSON.stringify({ setupIntentId: setupIntent.id, workspaceId });
        let res: Response;
        try {
            res = await fetchWithTimeout(`${getGatewayUrl()}/api/billing/update-payment-method`, { method: 'POST', headers, body });
            if (!res.ok && res.status === 404) {
                res = await fetchWithTimeout(`${window.location.origin}/api/billing/update-payment-method`, { method: 'POST', headers, body });
            }
        } catch (err: any) {
            setError(err.message || 'Network error. Please check your connection and try again.');
            setSubmitting(false);
            return;
        }

        if (res.status === 401 || res.status === 403) {
            setError('Your session has expired. Please sign in again.');
            setSubmitting(false);
            return;
        }

        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
            setError(data?.error ?? 'Failed to update payment method.');
            setSubmitting(false);
            return;
        }

        onSuccess();
    };

    return (
        <form onSubmit={handleSubmit} className="space-y-4">
            <PaymentElement options={{ layout: 'tabs' }} />
            {error && (
                <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-xl text-sm">{error}</div>
            )}
            <div className="flex items-center gap-2 text-[11px] text-gray-400">
                <Shield size={13} className="text-green-400 flex-shrink-0" />
                <span>Secured by Stripe — existing card replaced immediately</span>
            </div>
            <div className="flex gap-3">
                <button type="button" onClick={onCancel} disabled={submitting}
                    className="flex-1 py-3 rounded-xl border border-white/20 bg-white/5 text-gray-300 font-medium hover:bg-white/10 transition-all disabled:opacity-40">
                    Back
                </button>
                <button type="submit" disabled={submitting || !stripe || !elements}
                    className="flex-[2] py-3 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 text-white font-semibold hover:from-purple-700 hover:to-indigo-700 transition-all disabled:opacity-50 flex items-center justify-center gap-2">
                    {submitting ? <><Loader2 size={18} className="animate-spin" /> Saving…</> : <><CheckCircle size={18} /> Save new card</>}
                </button>
            </div>
        </form>
    );
};

// ─── Main modal ──────────────────────────────────────────────────────────────

type View = 'info' | 'update-card' | 'cancel-confirm' | 'cancelling' | 'done';

const ManageSubscriptionModal: React.FC<ManageSubscriptionModalProps> = ({ workspace, onClose, onCancelled, onCompletePayment, onUpgradePlan }) => {
    const [view, setView] = useState<View>('info');
    const [error, setError] = useState<string | null>(null);
    const [setupClientSecret, setSetupClientSecret] = useState<string | null>(null);
    const [setupPublishableKey, setSetupPublishableKey] = useState<string>('');
    const [cardUpdated, setCardUpdated] = useState(false);
    const { getDisplayPrice } = usePlanPricing();

    const handleClose = () => {
        setView('info');
        setError(null);
        setSetupClientSecret(null);
        setSetupPublishableKey('');
        setCardUpdated(false);
        onClose();
    };

    const stripePromise = useMemo(
        () => (setupPublishableKey ? loadStripe(setupPublishableKey) : null),
        [setupPublishableKey],
    );

    const elementsOptions: StripeElementsOptions | undefined = setupClientSecret
        ? {
            clientSecret: setupClientSecret,
            appearance: {
                theme: 'night',
                variables: {
                    colorPrimary: '#8b5cf6',
                    colorBackground: '#1e1b4b',
                    colorText: '#e2e8f0',
                    colorTextSecondary: '#94a3b8',
                    colorDanger: '#f87171',
                    borderRadius: '10px',
                },
                rules: {
                    '.Input': { backgroundColor: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.15)', color: '#e2e8f0', boxShadow: 'none' },
                    '.Input:focus': { border: '1px solid #8b5cf6', boxShadow: '0 0 0 3px rgba(139,92,246,0.25)' },
                    '.Label': { color: '#94a3b8', fontWeight: '500' },
                    '.Tab': { backgroundColor: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.12)', color: '#94a3b8' },
                    '.Tab--selected': { backgroundColor: 'rgba(139,92,246,0.15)', border: '1px solid rgba(139,92,246,0.5)', color: '#c4b5fd' },
                },
            },
        }
        : undefined;

    const authHeaders = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${safeGetStorage('authToken') ?? ''}`,
    };

    const startCardUpdate = async () => {
        setError(null);
        try {
            let res: Response;
            try {
                res = await fetchWithTimeout(`${getGatewayUrl()}/api/billing/setup`, { method: 'POST', headers: authHeaders });
                if (!res.ok && res.status === 404) {
                    res = await fetchWithTimeout(`${window.location.origin}/api/billing/setup`, { method: 'POST', headers: authHeaders });
                }
            } catch (err: any) {
                throw new Error(err.message || 'Network error. Please check your connection.');
            }
            if (res.status === 401 || res.status === 403) throw new Error('Your session has expired. Please sign in again.');
            const data = await res.json().catch(() => ({}));
            if (!res.ok || !data?.clientSecret) throw new Error(data?.error ?? 'Failed to create payment setup');
            setSetupPublishableKey(data.stripePublishableKey);
            setSetupClientSecret(data.clientSecret);
            setView('update-card');
        } catch (err: any) {
            setError(err.message);
        }
    };

    const confirmCancel = async () => {
        setView('cancelling');
        setError(null);
        try {
            const body = JSON.stringify({ workspaceId: workspace.workspaceId });
            let res: Response;
            try {
                res = await fetchWithTimeout(`${getGatewayUrl()}/api/billing/cancel-workspace`, { method: 'POST', headers: authHeaders, body });
                if (!res.ok && res.status === 404) {
                    res = await fetchWithTimeout(`${window.location.origin}/api/billing/cancel-workspace`, { method: 'POST', headers: authHeaders, body });
                }
            } catch (err: any) {
                throw new Error(err.message || 'Network error. Please check your connection and try again.');
            }
            if (res.status === 401 || res.status === 403) throw new Error('Your session has expired. Please sign in again.');
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data?.error ?? 'Failed to cancel subscription');
            setView('done');
        } catch (err: any) {
            setError(err.message);
            setView('info');
        }
    };


    const plan = workspace.subscriptionPlan?.toUpperCase() ?? '';
    const interval = workspace.billingInterval ?? 'monthly';
    const price = getDisplayPrice(plan, interval);
    const { label: statusText, color: statusColor } = statusLabel(workspace.billingStatus);

    return (
        <div className="fixed inset-0 z-[100] bg-black/70 backdrop-blur-sm flex items-center justify-center p-6">
            <div className="dark-surface relative w-full max-w-xl bg-gradient-to-b from-slate-900 to-indigo-950 border border-white/15 rounded-2xl shadow-2xl overflow-hidden">

                {/* Header */}
                <div className="flex items-center justify-between px-8 py-6 border-b border-white/10">
                    <div>
                        <h2 className="text-xl font-bold text-white">
                            {view === 'update-card' ? 'Update payment method' :
                             view === 'cancel-confirm' || view === 'cancelling' ? 'Cancel subscription' :
                             view === 'done' ? 'Subscription canceled' :
                             'Manage subscription'}
                        </h2>
                        {view === 'info' && <p className="text-sm text-gray-400 mt-1">{workspace.name}</p>}
                    </div>
                    <button onClick={handleClose}
                        className="w-9 h-9 flex items-center justify-center rounded-full bg-white/10 hover:bg-white/20 text-gray-400 hover:text-white transition-colors"
                        aria-label="Close">
                        <X size={18} />
                    </button>
                </div>

                <div className="px-8 py-7 space-y-5">

                    {/* ── Info view ── */}
                    {view === 'info' && (
                        <>
                            {/* Plan card */}
                            <div className="flex items-center justify-between bg-white/5 border border-white/10 rounded-xl px-5 py-4">
                                <div>
                                    <p className="text-xs uppercase tracking-widest text-gray-400 mb-1">Current plan</p>
                                    <p className="text-white text-lg font-semibold">{plan.charAt(0) + plan.slice(1).toLowerCase()} Plan</p>
                                    <p className="text-sm text-gray-400 mt-0.5 capitalize">{interval} billing</p>
                                </div>
                                <div className="text-right">
                                    {price && <p className="text-purple-300 text-lg font-semibold">{price}</p>}
                                    <span className={`inline-flex items-center gap-1.5 text-xs font-medium px-3 py-1 rounded-full border mt-2 ${statusColor}`}>
                                        <span className="w-1.5 h-1.5 rounded-full bg-current" />
                                        {statusText}
                                    </span>
                                </div>
                            </div>

                            <div className="bg-white/5 border border-white/10 rounded-xl px-5 py-3 text-xs text-gray-300 leading-relaxed">
                                Paid plans renew automatically. Renewal reminders are sent 15, 7, and 1 day before renewal. Expired or canceled subscriptions block workspace access until renewed.
                            </div>

                            {error && (
                                <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-5 py-3.5 rounded-xl text-sm">{error}</div>
                            )}

                            {cardUpdated && (
                                <div className="bg-green-500/10 border border-green-400/30 text-green-400 px-5 py-3.5 rounded-xl text-sm flex items-center gap-2">
                                    <CheckCircle size={16} /> Payment method updated successfully.
                                </div>
                            )}

                            {/* Actions */}
                            <div className="space-y-3">
                                {/* PENDING / PAYMENT_FAILED — show complete payment first */}
                                {(workspace.billingStatus === 'PENDING' || workspace.billingStatus === 'PAYMENT_FAILED') && onCompletePayment && (
                                    <button onClick={onCompletePayment}
                                        className="w-full flex items-center gap-4 px-5 py-4 rounded-xl bg-purple-500/10 border border-purple-400/30 text-white hover:bg-purple-500/20 hover:border-purple-400/60 transition-all">
                                        <div className="w-11 h-11 rounded-lg bg-purple-500/30 flex items-center justify-center flex-shrink-0">
                                            <CreditCard size={20} className="text-purple-300" />
                                        </div>
                                        <div className="text-left">
                                            <p className="font-semibold text-base text-purple-200">Complete payment</p>
                                            <p className="text-sm text-gray-400 mt-0.5">Activate your workspace now</p>
                                        </div>
                                    </button>
                                )}

                                {/* Active subscription — update card */}
                                {workspace.billingStatus !== 'PENDING' && workspace.billingStatus !== 'PAYMENT_FAILED' && (
                                    <button onClick={startCardUpdate}
                                        className="w-full flex items-center gap-4 px-5 py-4 rounded-xl bg-white/5 border border-white/10 text-white hover:bg-white/10 hover:border-purple-400/40 transition-all">
                                        <div className="w-11 h-11 rounded-lg bg-purple-500/20 flex items-center justify-center flex-shrink-0">
                                            <CreditCard size={20} className="text-purple-400" />
                                        </div>
                                        <div className="text-left">
                                            <p className="font-semibold text-base">Update payment method</p>
                                            <p className="text-sm text-gray-400 mt-0.5">Replace the card on file</p>
                                        </div>
                                    </button>
                                )}

                                {onUpgradePlan && (
                                    <button onClick={onUpgradePlan}
                                        className="w-full flex items-center gap-4 px-5 py-4 rounded-xl bg-indigo-500/10 border border-indigo-400/30 text-white hover:bg-indigo-500/20 hover:border-indigo-400/60 transition-all">
                                        <div className="w-11 h-11 rounded-lg bg-indigo-500/30 flex items-center justify-center flex-shrink-0">
                                            <Crown size={20} className="text-indigo-300" />
                                        </div>
                                        <div className="text-left">
                                            <p className="font-semibold text-base text-indigo-200">Upgrade plan</p>
                                            <p className="text-sm text-gray-400 mt-0.5">Explore more advanced features</p>
                                        </div>
                                    </button>
                                )}

                                <button onClick={() => setView('cancel-confirm')}
                                    className="w-full flex items-center gap-4 px-5 py-4 rounded-xl bg-white/5 border border-white/10 text-white hover:bg-red-500/10 hover:border-red-400/40 transition-all">
                                    <div className="w-11 h-11 rounded-lg bg-red-500/20 flex items-center justify-center flex-shrink-0">
                                        <XCircle size={20} className="text-red-400" />
                                    </div>
                                    <div className="text-left">
                                        <p className="font-semibold text-base text-red-300">Cancel subscription</p>
                                        <p className="text-sm text-gray-400 mt-0.5">Workspace access is blocked after cancellation</p>
                                    </div>
                                </button>
                            </div>
                        </>
                    )}

                    {/* ── Update card view ── */}
                    {view === 'update-card' && stripePromise && elementsOptions && (
                        <Elements stripe={stripePromise} options={elementsOptions}>
                            <UpdateCardForm
                                workspaceId={workspace.workspaceId}
                                onSuccess={() => { setView('info'); setCardUpdated(true); setSetupClientSecret(null); }}
                                onCancel={() => { setView('info'); setSetupClientSecret(null); }}
                            />
                        </Elements>
                    )}

                    {/* ── Cancel confirm view ── */}
                    {view === 'cancel-confirm' && (
                        <>
                            <div className="flex items-start gap-4 bg-amber-500/10 border border-amber-400/20 rounded-xl p-5">
                                <AlertTriangle size={26} className="text-amber-400 flex-shrink-0 mt-0.5" />
                                <div className="text-sm text-gray-300 space-y-2">
                                    <p className="font-semibold text-white text-base">Canceling blocks paid workspace access.</p>
                                    <p className="text-sm">Access to <span className="text-white font-medium">{workspace.name}</span> will be blocked until you renew the existing plan. Your workspace data is retained.</p>
                                </div>
                            </div>
                            {error && (
                                <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-5 py-3.5 rounded-xl text-sm">{error}</div>
                            )}
                            <div className="flex gap-3 pt-1">
                                <button onClick={() => setView('info')}
                                    className="flex-1 py-3.5 rounded-xl border border-white/20 bg-white/5 text-gray-300 font-medium text-base hover:bg-white/10 transition-all">
                                    Keep subscription
                                </button>
                                <button onClick={confirmCancel}
                                    className="flex-1 py-3.5 rounded-xl bg-red-600 text-white font-semibold text-base hover:bg-red-700 transition-all">
                                    Cancel now
                                </button>
                            </div>
                        </>
                    )}

                    {/* ── Cancelling spinner ── */}
                    {view === 'cancelling' && (
                        <div className="flex flex-col items-center py-10 gap-4">
                            <Loader2 size={44} className="text-purple-400 animate-spin" />
                            <p className="text-gray-300 text-base">Canceling subscription…</p>
                        </div>
                    )}

                    {/* ── Done view ── */}
                    {view === 'done' && (
                        <>
                            <div className="flex flex-col items-center py-8 gap-4 text-center">
                                <div className="w-20 h-20 rounded-full bg-amber-500/20 flex items-center justify-center">
                                    <CheckCircle size={38} className="text-amber-400" />
                                </div>
                                <p className="text-white font-semibold text-lg">Subscription canceled</p>
                                <p className="text-gray-400 text-sm max-w-sm">Workspace access for <span className="text-white">{workspace.name}</span> is blocked until you renew the existing plan.</p>
                            </div>
                            <button onClick={onCancelled}
                                className="w-full py-3.5 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 text-white font-semibold text-base hover:from-purple-700 hover:to-indigo-700 transition-all">
                                Done
                            </button>
                        </>
                    )}
                </div>
            </div>
        </div>
    );
};

export default ManageSubscriptionModal;
