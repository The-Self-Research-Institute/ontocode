import React, { useEffect, useMemo, useState } from 'react';
import { Elements, PaymentElement, useStripe, useElements } from '@stripe/react-stripe-js';
import { loadStripe, StripeElementsOptions } from '@stripe/stripe-js';
import { X, Loader2, CreditCard, XCircle, CheckCircle, Shield, AlertTriangle, Crown, ChevronLeft, Calendar, RefreshCw, History, Download, Monitor, ArrowLeftRight } from 'lucide-react';
import { getGatewayUrl } from '../config/deploymentConfig';
import { usePlanPricing } from '../hooks/usePlanPricing';
import { isDesktop } from '../utils/desktop';

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

interface BillingManagementProps {
    workspace: Workspace;
    onBack: () => void;
    onCancelled: () => void;
    onCompletePayment?: () => void;
    onUpgradePlan?: () => void;
    /**
     * True only for the workspace owner / account holder. Drives the
     * visibility of destructive billing actions (cancel, update card).
     * Members and admins should never see Cancel — the backend also
     * enforces this so a forged request is still rejected.
     */
    isOwner?: boolean;
}

interface PaymentHistoryItem {
    invoiceId: string;
    number?: string;
    status?: string;
    amountPaid?: string;
    amountDue?: string;
    currency?: string;
    createdAt?: string;
    periodStart?: string;
    periodEnd?: string;
    hostedInvoiceUrl?: string;
    invoicePdf?: string;
    description?: string;
}

interface BillingSummary {
    planName?: string;
    status?: string;
    billingInterval?: string;
    autoRenewEnabled?: boolean;
    cancelAtPeriodEnd?: boolean;
    currentPeriodEnd?: string;
    canceledAt?: string;
    pendingBillingInterval?: string;
    pendingBillingIntervalDate?: string;
    paymentHistory?: PaymentHistoryItem[];
    defaultPaymentMethod?: {
        pmId?: string;
        last4?: string;
        brand?: string;
        expMonth?: number;
        expYear?: number;
        type?: string;
    };
    backupPaymentMethods?: Array<{
        pmId?: string;
        last4?: string;
        brand?: string;
        expMonth?: number;
        expYear?: number;
        type?: string;
    }>;
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

function formatBillingDate(iso?: string, fallback = 'Not available') {
    if (!iso) return fallback;
    const value = new Date(iso);
    if (Number.isNaN(value.getTime())) return fallback;
    return value.toLocaleDateString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
    });
}

// ─── Payment method display row ─────────────────────────────────────────────

const PaymentMethodRow: React.FC<{ pm: { last4?: string; brand?: string; expMonth?: number; expYear?: number; type?: string } }> = ({ pm }) => {
    const isLink = pm.type === 'link' || pm.brand?.toLowerCase() === 'link';
    if (isLink) {
        return (
            <div className="flex items-center gap-2">
                <div className="text-sm font-semibold text-white">Stripe Link</div>
                <span className="text-xs text-slate-500">Digital wallet</span>
            </div>
        );
    }
    return (
        <div className="flex items-center gap-3">
            <div className="text-sm font-semibold text-white capitalize">
                {pm.brand || 'Card'} ●●●● {pm.last4}
            </div>
            {pm.expMonth && pm.expYear && (
                <span className="text-xs text-slate-500">Expires {pm.expMonth}/{pm.expYear}</span>
            )}
        </div>
    );
};

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

        setSubmitting(false);
        onSuccess();
    };

    return (
        <form onSubmit={handleSubmit} className="space-y-6 bg-white/5 border border-white/10 rounded-2xl p-6">
            <h3 className="text-lg font-semibold text-white flex items-center gap-2">
                <CreditCard size={20} className="text-purple-400" />
                Enter New Card Details
            </h3>
            <PaymentElement options={{ layout: 'tabs' }} />
            {error && (
                <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-xl text-sm">{error}</div>
            )}
            <div className="flex items-center gap-2 text-xs text-gray-400 bg-black/20 p-3 rounded-lg border border-white/5">
                <Shield size={14} className="text-green-400 flex-shrink-0" />
                <span>Your payment information is encrypted and secured by Stripe. Existing card will be replaced immediately.</span>
            </div>
            <div className="flex gap-4">
                <button type="button" onClick={onCancel} disabled={submitting}
                    className="flex-1 py-3 rounded-xl border border-white/20 bg-white/5 text-gray-300 font-medium hover:bg-white/10 transition-all disabled:opacity-40">
                    Cancel
                </button>
                <button type="submit" disabled={submitting || !stripe || !elements}
                    className="flex-[2] py-3 rounded-xl bg-gradient-to-r from-purple-600 to-indigo-600 text-white font-semibold hover:from-purple-700 hover:to-indigo-700 transition-all disabled:opacity-50 flex items-center justify-center gap-2 shadow-lg shadow-purple-900/20">
                    {submitting ? <><Loader2 size={18} className="animate-spin" /> Saving…</> : <><CheckCircle size={18} /> Save new card</>}
                </button>
            </div>
        </form>
    );
};

// ─── Main Page ──────────────────────────────────────────────────────────────

type View = 'info' | 'update-card' | 'cancel-confirm' | 'cancelling' | 'done';

const BillingManagement: React.FC<BillingManagementProps> = ({ workspace, onBack, onCancelled, onCompletePayment, onUpgradePlan, isOwner = false }) => {
    const [view, setView] = useState<View>('info');
    const [error, setError] = useState<string | null>(null);
    const [detailsError, setDetailsError] = useState<string | null>(null);
    const [billingSummary, setBillingSummary] = useState<BillingSummary | null>(null);
    const [loadingSummary, setLoadingSummary] = useState(true);
    const [setupClientSecret, setSetupClientSecret] = useState<string | null>(null);
    const [setupPublishableKey, setSetupPublishableKey] = useState<string>('');
    const [cardUpdated, setCardUpdated] = useState(false);
    const [enablingAutoRenew, setEnablingAutoRenew] = useState(false);
    const [switchingInterval, setSwitchingInterval] = useState(false);
    const [intervalSwitchError, setIntervalSwitchError] = useState<string | null>(null);
    const [intervalSwitchSuccess, setIntervalSwitchSuccess] = useState<string | null>(null);
    const [usingBackupPmId, setUsingBackupPmId] = useState<string | null>(null);
    const [backupPmError, setBackupPmError] = useState<string | null>(null);
    const [downloadingLicense, setDownloadingLicense] = useState(false);
    const [licenseError, setLicenseError] = useState<string | null>(null);
    const { getDisplayPrice } = usePlanPricing();

    const downloadLicense = async () => {
        setLicenseError(null);
        setDownloadingLicense(true);
        try {
            const headers = { Authorization: `Bearer ${safeGetStorage('authToken') ?? ''}` };
            let res = await fetchWithTimeout(`${getGatewayUrl()}/api/billing/license/download`, { method: 'GET', headers });
            if (!res.ok && res.status !== 401) {
                res = await fetchWithTimeout(`${window.location.origin}/api/billing/license/download`, { method: 'GET', headers });
            }
            if (!res.ok) {
                const msg = res.status === 503
                    ? 'License downloads are not enabled on this server yet.'
                    : `Could not download license (HTTP ${res.status}).`;
                throw new Error(msg);
            }
            const blob = await res.blob();
            const disposition = res.headers.get('Content-Disposition') || '';
            const match = disposition.match(/filename="?([^"]+)"?/);
            const fileName = match ? match[1] : `ontocode-${(workspace.subscriptionPlan || 'free').toLowerCase()}.lic`;
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            a.remove();
            URL.revokeObjectURL(url);
        } catch (e: any) {
            setLicenseError(e?.message || 'Failed to download license file.');
        } finally {
            setDownloadingLicense(false);
        }
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
                    borderRadius: '12px',
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

    const loadBillingSummary = async () => {
        setLoadingSummary(true);
        setDetailsError(null);
        try {
            let res: Response;
            try {
                res = await fetchWithTimeout(`${getGatewayUrl()}/api/billing/subscription/details`, {
                    method: 'GET',
                    headers: authHeaders,
                });
                if (!res.ok && res.status === 404) {
                    res = await fetchWithTimeout(`${window.location.origin}/api/billing/subscription/details`, {
                        method: 'GET',
                        headers: authHeaders,
                    });
                }
            } catch (err: any) {
                throw new Error(err.message || 'Failed to load billing details.');
            }

            if (res.status === 401 || res.status === 403) {
                throw new Error('Your session has expired. Please sign in again.');
            }

            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
                throw new Error(data?.error ?? 'Failed to load billing details.');
            }

            setBillingSummary(data);
        } catch (err: any) {
            setDetailsError(err.message || 'Failed to load billing details.');
        } finally {
            setLoadingSummary(false);
        }
    };

    useEffect(() => {
        loadBillingSummary();
    }, [workspace.workspaceId]);

    useEffect(() => {
        setIntervalSwitchSuccess(null);
        setIntervalSwitchError(null);
    }, [billingSummary?.billingInterval]);

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
            // Bug #50: cancellation is ALWAYS account-level (Model B). The
            // backend treats an empty workspaceId as "cancel my account
            // subscription" and propagates the change to every workspace
            // owned by the user. We never pass the workspace id even when
            // navigated from a workspace context.
            const body = JSON.stringify({ workspaceId: '' });
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

    const enableAutoRenew = async () => {
        setError(null);
        setEnablingAutoRenew(true);
        try {
            let res: Response;
            try {
                res = await fetchWithTimeout(`${getGatewayUrl()}/api/billing/auto-renew/enable`, { method: 'POST', headers: authHeaders });
                if (!res.ok && res.status === 404) {
                    res = await fetchWithTimeout(`${window.location.origin}/api/billing/auto-renew/enable`, { method: 'POST', headers: authHeaders });
                }
            } catch (err: any) {
                throw new Error(err.message || 'Network error. Please check your connection and try again.');
            }
            if (res.status === 401 || res.status === 403) throw new Error('Your session has expired. Please sign in again.');
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data?.error ?? 'Failed to turn on auto-renewal');
            setBillingSummary((prev) => prev ? { ...prev, autoRenewEnabled: true, cancelAtPeriodEnd: false, canceledAt: '' } : prev);
        } catch (err: any) {
            setError(err.message);
        } finally {
            setEnablingAutoRenew(false);
        }
    };


    const switchBillingInterval = async (newInterval: 'monthly' | 'annual') => {
        setIntervalSwitchError(null);
        setIntervalSwitchSuccess(null);
        setSwitchingInterval(true);
        try {
            let res: Response;
            try {
                res = await fetchWithTimeout(`${getGatewayUrl()}/api/billing/change-interval`, {
                    method: 'POST', headers: authHeaders, body: JSON.stringify({ interval: newInterval }),
                });
                if (!res.ok && res.status === 404) {
                    res = await fetchWithTimeout(`${window.location.origin}/api/billing/change-interval`, {
                        method: 'POST', headers: authHeaders, body: JSON.stringify({ interval: newInterval }),
                    });
                }
            } catch (err: any) {
                throw new Error(err.message || 'Network error. Please check your connection and try again.');
            }
            if (res.status === 401 || res.status === 403) throw new Error('Your session has expired. Please sign in again.');
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data?.error ?? 'Failed to switch billing interval.');
            const nextDate = data.effectiveDate ? formatBillingDate(data.effectiveDate) : '';
            if (data.cancelledPending) {
                setIntervalSwitchSuccess(`Downgrade cancelled. Your plan will continue as annual.`);
            } else if (data.pending) {
                setIntervalSwitchSuccess(`Your plan will switch to monthly on ${nextDate}. No charge until then.`);
            } else if (newInterval === 'monthly') {
                setIntervalSwitchSuccess(`Switched to monthly billing. Next charge on ${nextDate}.`);
            } else {
                setIntervalSwitchSuccess(`Switched to annual billing. Next renewal on ${nextDate}.`);
            }
            await loadBillingSummary();
        } catch (err: any) {
            setIntervalSwitchError(err.message);
        } finally {
            setSwitchingInterval(false);
        }
    };

    const useBackupPaymentMethod = async (pmId: string) => {
        setBackupPmError(null);
        setUsingBackupPmId(pmId);
        try {
            let res: Response;
            const body = JSON.stringify({ paymentMethodId: pmId });
            try {
                res = await fetchWithTimeout(`${getGatewayUrl()}/api/billing/use-payment-method`, {
                    method: 'POST', headers: authHeaders, body,
                });
                if (!res.ok && res.status === 404) {
                    res = await fetchWithTimeout(`${window.location.origin}/api/billing/use-payment-method`, {
                        method: 'POST', headers: authHeaders, body,
                    });
                }
            } catch (err: any) {
                throw new Error(err.message || 'Network error. Please check your connection and try again.');
            }
            if (res.status === 401 || res.status === 403) throw new Error('Your session has expired. Please sign in again.');
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data?.error ?? 'Failed to update payment method.');
            await loadBillingSummary();
        } catch (err: any) {
            setBackupPmError(err.message);
        } finally {
            setUsingBackupPmId(null);
        }
    };

    const plan = (billingSummary?.planName || workspace.subscriptionPlan || '').toUpperCase();
    const isTopPlan = plan === 'ENTERPRISE';
    const interval = billingSummary?.billingInterval || workspace.billingInterval || 'monthly';
    const price = getDisplayPrice(plan, interval);
    const summaryStatus = billingSummary?.status || workspace.billingStatus;
    const { label: statusText, color: statusColor } = statusLabel(summaryStatus);
    const nextRenewalLabel =
        billingSummary?.autoRenewEnabled
            ? 'Next auto-renewal'
            : 'Access until';
    const renewalDateLabel = formatBillingDate(billingSummary?.currentPeriodEnd);
    const paymentHistory = billingSummary?.paymentHistory || [];
    const planDisplayName = plan ? `OntoCode ${plan.charAt(0)}${plan.slice(1).toLowerCase()}` : 'Subscription';
    const autoRenewEnabled = billingSummary?.autoRenewEnabled !== false && billingSummary?.cancelAtPeriodEnd !== true;

    return (
        // Fill the viewport exactly, then split into a fixed header and a
        // scrollable content area. `min-h-screen` + `overflow-y-auto` on
        // the same node is a known anti-pattern: the node grows past the
        // viewport so its own scrollbar never engages.
        <div className="dark h-screen flex flex-col bg-[#0f172a] text-slate-200">
            {/* Header (does not scroll) */}
            <div className="flex-shrink-0 z-50 bg-[#0f172a]/80 backdrop-blur-xl border-b border-white/10 px-6 py-4 flex items-center justify-between shadow-lg">
                <div className="flex items-center gap-4">
                    <button onClick={onBack}
                        className="w-10 h-10 flex items-center justify-center rounded-full bg-white/5 hover:bg-white/10 text-gray-400 hover:text-white transition-all group"
                        title="Go back">
                        <ChevronLeft size={24} className="group-hover:-translate-x-0.5 transition-transform" />
                    </button>
                    <div>
                        <h1 className="text-xl font-bold text-white tracking-tight">Billing & Subscription</h1>
                        <p className="text-xs text-gray-400 font-medium uppercase tracking-widest">{workspace.name}</p>
                    </div>
                </div>
                <div className="hidden md:flex items-center gap-2 px-4 py-1.5 rounded-full bg-purple-500/10 border border-purple-500/20 text-purple-300 text-xs font-semibold">
                    <Shield size={14} />
                    Secure Billing Portal
                </div>
            </div>

            {/* Scrollable body. `overscroll-contain` keeps wheel events
                from bubbling past this view to whatever rendered us. */}
            <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain w-full">
                <div className="w-full max-w-4xl mx-auto p-6 md:p-10 space-y-10 pb-24">
                
                {/* Error Banner */}
                {detailsError && (
                    <div className="bg-amber-500/10 border border-amber-400/30 text-amber-300 px-6 py-4 rounded-2xl text-sm flex items-start gap-3 shadow-lg animate-in fade-in slide-in-from-top-2">
                        <AlertTriangle size={20} className="flex-shrink-0" />
                        <div>
                            <p className="font-semibold">Notice</p>
                            <p className="opacity-90">{detailsError}</p>
                        </div>
                    </div>
                )}

                {/* ── Main View ── */}
                {view === 'info' && (
                    <div className="space-y-8 animate-in fade-in slide-in-from-bottom-4 duration-500">
                        
                        {/* Current Plan Card */}
                        <div className="relative overflow-hidden bg-gradient-to-br from-slate-900 to-indigo-950 border border-white/15 rounded-3xl p-8 shadow-2xl">
                            <div className="absolute top-0 right-0 p-8 opacity-5">
                                <Crown size={120} />
                            </div>
                            
                            <div className="relative flex flex-col md:flex-row md:items-center justify-between gap-6">
                                <div className="space-y-2">
                                    <div className="flex items-center gap-3">
                                        <span className="text-xs font-bold uppercase tracking-[0.2em] text-purple-400">Current Plan</span>
                                        <span className={`inline-flex items-center gap-1.5 text-[10px] font-bold px-2.5 py-0.5 rounded-full border ${statusColor} uppercase tracking-wider`}>
                                            <span className="w-1 h-1 rounded-full bg-current" />
                                            {statusText}
                                        </span>
                                    </div>
                                    <h2 className="text-4xl font-black text-white">{planDisplayName}</h2>
                                    <p className="text-slate-400 font-medium flex items-center gap-2">
                                        <Calendar size={16} />
                                        {interval.charAt(0).toUpperCase() + interval.slice(1)} billing cycle
                                    </p>
                                </div>
                                <div className="text-left md:text-right space-y-1">
                                    <div className="text-4xl font-bold text-white">{price || '—'}</div>
                                    <p className="text-slate-400 text-sm">Next payment due on <span className="text-slate-200 font-semibold">{renewalDateLabel}</span></p>
                                </div>
                            </div>

                            <div className="mt-8 pt-8 border-t border-white/10 grid grid-cols-1 md:grid-cols-2 gap-6">
                                <div className="space-y-2">
                                    <div className="flex items-center gap-2 text-sm font-semibold text-slate-300">
                                        <RefreshCw size={16} className="text-purple-400" />
                                        Auto-Renewal Status
                                    </div>
                                    <p className="text-sm text-slate-400 leading-relaxed">
                                        {autoRenewEnabled 
                                            ? 'Your subscription will automatically renew. A reminder will be sent before the charge.' 
                                            : 'Auto-renewal is turned off. Your access will expire at the end of the current period.'}
                                    </p>
                                    <div className={`text-sm font-bold ${autoRenewEnabled ? 'text-green-400' : 'text-amber-400'}`}>
                                        {autoRenewEnabled ? 'Renewal Enabled' : 'Renewal Disabled'}
                                    </div>
                                </div>
                                <div className="space-y-2">
                                    <div className="flex items-center gap-2 text-sm font-semibold text-slate-300">
                                        <CreditCard size={16} className="text-purple-400" />
                                        Payment Method on File
                                    </div>
                                    {billingSummary?.defaultPaymentMethod ? (
                                        <div className="space-y-2">
                                            <div className="flex items-center gap-2">
                                                <span className="text-[10px] text-slate-500 uppercase tracking-wide font-bold">Default</span>
                                                <PaymentMethodRow pm={billingSummary.defaultPaymentMethod} />
                                            </div>
                                            {billingSummary.backupPaymentMethods?.map((pm, i) => (
                                                <div key={i} className="flex items-center justify-between gap-2">
                                                    <div className="flex items-center gap-2">
                                                        <span className="text-[10px] text-slate-500 uppercase tracking-wide">Backup</span>
                                                        <PaymentMethodRow pm={pm} />
                                                    </div>
                                                    {isOwner && pm.pmId && (
                                                        <button
                                                            onClick={() => useBackupPaymentMethod(pm.pmId!)}
                                                            disabled={usingBackupPmId === pm.pmId}
                                                            className="text-[11px] px-2.5 py-1 rounded-lg bg-purple-500/10 border border-purple-500/20 text-purple-300 hover:bg-purple-500/20 transition-all disabled:opacity-50 flex items-center gap-1 flex-shrink-0"
                                                        >
                                                            {usingBackupPmId === pm.pmId
                                                                ? <><Loader2 size={10} className="animate-spin" /> Applying…</>
                                                                : summaryStatus?.toLowerCase() === 'past_due'
                                                                    ? 'Use this card'
                                                                    : 'Make default'}
                                                        </button>
                                                    )}
                                                </div>
                                            ))}
                                            {backupPmError && (
                                                <p className="text-xs text-red-400 mt-1">{backupPmError}</p>
                                            )}
                                        </div>
                                    ) : (
                                        <p className="text-sm text-slate-400 leading-relaxed">
                                            {loadingSummary ? 'Loading...' : 'No payment method on file.'}
                                        </p>
                                    )}
                                </div>
                            </div>
                        </div>

                        {/* Quick Actions Grid */}
                        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4 items-stretch">
                             {/* Complete Payment - Only if pending */}
                             {(workspace.billingStatus === 'PENDING' || workspace.billingStatus === 'PAYMENT_FAILED') && onCompletePayment && (
                                <button onClick={onCompletePayment}
                                    className="h-full min-h-[180px] flex flex-col items-start gap-4 p-6 rounded-2xl bg-purple-600 hover:bg-purple-500 text-white transition-all shadow-lg shadow-purple-900/20 group">
                                    <div className="w-12 h-12 rounded-xl bg-white/20 flex items-center justify-center group-hover:scale-110 transition-transform">
                                        <CreditCard size={24} />
                                    </div>
                                    <div className="text-left">
                                        <p className="font-bold text-lg">Complete Payment</p>
                                        <p className="text-sm text-purple-100 opacity-80 mt-1 text-balance">Activate your workspace and features now.</p>
                                    </div>
                                </button>
                            )}

                            {/* Update Card — owner only (only the account
                                holder has a Stripe customer record on file) */}
                            {isOwner && (
                                <button onClick={startCardUpdate}
                                    className="h-full min-h-[180px] flex flex-col items-start gap-4 p-6 rounded-2xl bg-white/5 border border-white/10 hover:border-purple-500/50 hover:bg-white/10 text-white transition-all group">
                                    <div className="w-12 h-12 rounded-xl bg-purple-500/20 flex items-center justify-center text-purple-400 group-hover:scale-110 transition-transform">
                                        <CreditCard size={24} />
                                    </div>
                                    <div className="text-left">
                                        <p className="font-bold text-lg">Update Payment</p>
                                        <p className="text-sm text-slate-400 mt-1 text-balance">Change your card on file for future renewals.</p>
                                    </div>
                                </button>
                            )}

                            {/* Upgrade Plan — owner only; members can't change billing */}
                            {isOwner && (
                                <button onClick={onUpgradePlan}
                                    className="h-full min-h-[180px] flex flex-col items-start gap-4 p-6 rounded-2xl bg-white/5 border border-white/10 hover:border-indigo-500/50 hover:bg-white/10 text-white transition-all group">
                                    <div className="w-12 h-12 rounded-xl bg-indigo-500/20 flex items-center justify-center text-indigo-400 group-hover:scale-110 transition-transform">
                                        <Crown size={24} />
                                    </div>
                                    <div className="text-left">
                                        <p className="font-bold text-lg">{isTopPlan ? 'View Plans' : 'Upgrade Plan'}</p>
                                        <p className="text-sm text-slate-400 mt-1 text-balance">Explore more advanced features and options.</p>
                                    </div>
                                </button>
                            )}

                            {/* Switch billing interval — owner only, paid active/trialing plans */}
                            {isOwner && plan !== 'FREE' && (summaryStatus?.toLowerCase() === 'active' || summaryStatus?.toLowerCase() === 'trialing') && (() => {
                                const pendingMonthly = billingSummary?.pendingBillingInterval === 'monthly';
                                const pendingDate = billingSummary?.pendingBillingIntervalDate
                                    ? formatBillingDate(billingSummary.pendingBillingIntervalDate) : '';

                                if (pendingMonthly) {
                                    // State: annual with pending monthly downgrade
                                    return (
                                        <div className="h-full min-h-[180px] flex flex-col items-start gap-4 p-6 rounded-2xl bg-white/5 border border-amber-500/30 text-white">
                                            <div className="w-12 h-12 rounded-xl bg-amber-500/20 flex items-center justify-center text-amber-400">
                                                <ArrowLeftRight size={24} />
                                            </div>
                                            <div className="text-left flex-1">
                                                <p className="font-bold text-lg">Billing Change Scheduled</p>
                                                <p className="text-sm text-amber-300 mt-1">
                                                    Renewing as <strong>monthly</strong> on {pendingDate}. No charge until then.
                                                </p>
                                                <button
                                                    onClick={() => switchBillingInterval('annual')}
                                                    disabled={switchingInterval}
                                                    className="mt-3 text-xs text-slate-400 underline hover:text-white transition-colors disabled:opacity-50"
                                                >
                                                    {switchingInterval ? 'Cancelling…' : 'Cancel — keep annual'}
                                                </button>
                                            </div>
                                            {intervalSwitchSuccess && (
                                                <p className="text-xs text-green-400">{intervalSwitchSuccess}</p>
                                            )}
                                            {intervalSwitchError && (
                                                <p className="text-xs text-red-400">{intervalSwitchError}</p>
                                            )}
                                        </div>
                                    );
                                }

                                // State: monthly (upgrade to annual) or annual without pending (schedule downgrade)
                                return (
                                    <button
                                        onClick={() => switchBillingInterval(interval === 'annual' ? 'monthly' : 'annual')}
                                        disabled={switchingInterval}
                                        className="h-full min-h-[180px] flex flex-col items-start gap-4 p-6 rounded-2xl bg-white/5 border border-white/10 hover:border-blue-500/50 hover:bg-white/10 text-white transition-all group disabled:opacity-60 disabled:cursor-not-allowed"
                                    >
                                        <div className="w-12 h-12 rounded-xl bg-blue-500/20 flex items-center justify-center text-blue-400 group-hover:scale-110 transition-transform">
                                            {switchingInterval ? <Loader2 size={24} className="animate-spin" /> : <ArrowLeftRight size={24} />}
                                        </div>
                                        <div className="text-left">
                                            <p className="font-bold text-lg">
                                                {interval === 'annual' ? 'Switch to Monthly' : 'Switch to Annual'}
                                            </p>
                                            <p className="text-sm text-slate-400 mt-1 text-balance">
                                                {interval === 'annual'
                                                    ? 'No charge now. Your annual plan continues until renewal, then switches to monthly.'
                                                    : "Switch to annual and save 20%. You'll be charged the annual price difference today."}
                                            </p>
                                            {intervalSwitchSuccess && (
                                                <p className="text-xs text-green-400 mt-2">{intervalSwitchSuccess}</p>
                                            )}
                                            {intervalSwitchError && (
                                                <p className="text-xs text-red-400 mt-2">{intervalSwitchError}</p>
                                            )}
                                        </div>
                                    </button>
                                );
                            })()}

                            {/* Cancel/restore auto-renewal — owner only; backend re-checks. Bug #42. */}
                            {isOwner && (
                                <button
                                    onClick={autoRenewEnabled ? () => setView('cancel-confirm') : enableAutoRenew}
                                    disabled={enablingAutoRenew}
                                    className={`h-full min-h-[180px] flex flex-col items-start gap-4 p-6 rounded-2xl bg-white/5 border border-white/10 text-white transition-all group disabled:opacity-60 disabled:cursor-not-allowed ${
                                        autoRenewEnabled
                                            ? 'hover:border-red-500/50 hover:bg-red-500/5'
                                            : 'hover:border-green-500/50 hover:bg-green-500/5'
                                    }`}
                                >
                                    <div className={`w-12 h-12 rounded-xl flex items-center justify-center group-hover:scale-110 transition-transform ${
                                        autoRenewEnabled ? 'bg-red-500/20 text-red-400' : 'bg-green-500/20 text-green-400'
                                    }`}>
                                        {enablingAutoRenew ? <Loader2 size={24} className="animate-spin" /> : autoRenewEnabled ? <XCircle size={24} /> : <RefreshCw size={24} />}
                                    </div>
                                    <div className="text-left">
                                        <p className={`font-bold text-lg ${autoRenewEnabled ? 'text-red-300' : 'text-green-300'}`}>
                                            {autoRenewEnabled ? 'Cancel Plan' : 'Turn On Auto-Renewal'}
                                        </p>
                                        <p className="text-sm text-slate-400 mt-1 text-balance">
                                            {autoRenewEnabled
                                                ? 'Stop your subscription at the end of the cycle.'
                                                : 'Keep your plan active and renew automatically.'}
                                        </p>
                                    </div>
                                </button>
                            )}

                            {/* Desktop License — hidden from billing page, preserved for future use */}
                            {!isDesktop() && (
                                <button onClick={downloadLicense} disabled={downloadingLicense}
                                    className="hidden h-full min-h-[180px] flex flex-col items-start gap-4 p-6 rounded-2xl bg-white/5 border border-white/10 hover:border-emerald-500/50 hover:bg-white/10 text-white transition-all group disabled:opacity-60 disabled:cursor-not-allowed">
                                    <div className="w-12 h-12 rounded-xl bg-emerald-500/20 flex items-center justify-center text-emerald-400 group-hover:scale-110 transition-transform">
                                        {downloadingLicense ? <Loader2 size={24} className="animate-spin" /> : <Monitor size={24} />}
                                    </div>
                                    <div className="text-left">
                                        <p className="font-bold text-lg flex items-center gap-1.5">
                                            <Download size={16} /> Desktop License
                                        </p>
                                        <p className="text-sm text-slate-400 mt-1 text-balance">
                                            Download a license file to activate OntoCode Desktop on your computer.
                                        </p>
                                        {licenseError && (
                                            <p className="text-xs text-red-400 mt-2">{licenseError}</p>
                                        )}
                                    </div>
                                </button>
                            )}

                            {/* Bug #50: billing is account-level (Model B).
                                Members of someone else's workspace shouldn't
                                land here at all — the host gates navigation
                                so only the account holder reaches this page.
                                The previous "Read-only billing view" notice
                                conflated workspace ownership with account
                                ownership and was misleading; it has been
                                removed. */}
                        </div>

                        {/* Payment History Section */}
                        <div className="space-y-6">
                            <div className="flex items-center justify-between">
                                <h3 className="text-xl font-bold text-white flex items-center gap-3">
                                    <History size={22} className="text-purple-400" />
                                    Payment History
                                </h3>
                                {!loadingSummary && paymentHistory.length > 0 && (
                                    <span className="text-xs font-bold text-slate-500 uppercase tracking-widest">{paymentHistory.length} Invoices Found</span>
                                )}
                            </div>

                            {loadingSummary ? (
                                <div className="flex flex-col items-center justify-center py-20 bg-white/5 border border-dashed border-white/10 rounded-3xl gap-4">
                                    <Loader2 size={32} className="text-purple-500 animate-spin" />
                                    <p className="text-slate-400 font-medium">Fetching your invoices...</p>
                                </div>
                            ) : paymentHistory.length === 0 ? (
                                <div className="text-center py-16 bg-white/5 border border-dashed border-white/10 rounded-3xl">
                                    <div className="w-16 h-16 bg-white/5 rounded-full flex items-center justify-center mx-auto mb-4 text-slate-600">
                                        <History size={32} />
                                    </div>
                                    <p className="text-slate-400 font-medium">No invoice history available yet.</p>
                                    <p className="text-slate-500 text-sm mt-1">New invoices will appear here after your first charge.</p>
                                </div>
                            ) : (
                                <div className="grid gap-4">
                                    {paymentHistory.map((item) => {
                                        const invoiceLink = item.hostedInvoiceUrl || item.invoicePdf;
                                        const amount = item.amountPaid && item.amountPaid !== '0.00'
                                            ? `${item.currency || 'USD'} ${item.amountPaid}`
                                            : `${item.currency || 'USD'} ${item.amountDue || '0.00'}`;
                                        return (
                                            <div key={item.invoiceId}
                                                className="group flex flex-col md:flex-row md:items-center justify-between gap-4 p-5 rounded-2xl bg-white/5 border border-white/10 hover:bg-white/10 transition-all">
                                                <div className="flex items-start gap-4">
                                                    <div className="w-10 h-10 rounded-lg bg-slate-800 flex items-center justify-center text-slate-400 group-hover:text-purple-400 transition-colors">
                                                        <CreditCard size={18} />
                                                    </div>
                                                    <div>
                                                        <p className="font-bold text-white group-hover:text-purple-200 transition-colors">
                                                            {item.description || item.number || 'Subscription Charge'}
                                                        </p>
                                                        <p className="text-xs text-slate-500 mt-1 font-medium">
                                                            {formatBillingDate(item.createdAt)}{item.periodEnd ? ` • Period ends ${formatBillingDate(item.periodEnd)}` : ''}
                                                        </p>
                                                    </div>
                                                </div>
                                                <div className="flex items-center justify-between md:justify-end gap-6 pl-14 md:pl-0">
                                                    <div className="text-left md:text-right">
                                                        <p className="text-lg font-bold text-white">{amount}</p>
                                                        <p className="text-[10px] font-black uppercase tracking-widest text-slate-500">{item.status || 'PROCESSED'}</p>
                                                    </div>
                                                    {invoiceLink && (
                                                        <a href={invoiceLink} target="_blank" rel="noreferrer"
                                                            className="px-4 py-2 rounded-xl bg-white/5 border border-white/10 text-xs font-bold text-slate-300 hover:bg-white/20 hover:text-white transition-all flex items-center gap-2">
                                                            Invoice
                                                        </a>
                                                    )}
                                                </div>
                                            </div>
                                        );
                                    })}
                                </div>
                            )}
                        </div>
                    </div>
                )}

                {/* ── Update Card View ── */}
                {view === 'update-card' && (
                    <div className="max-w-2xl mx-auto animate-in fade-in slide-in-from-bottom-4 duration-500">
                        {stripePromise && elementsOptions ? (
                            <Elements stripe={stripePromise} options={elementsOptions}>
                                <UpdateCardForm
                                    workspaceId={workspace.workspaceId}
                                    onSuccess={() => { setView('info'); setCardUpdated(true); setSetupClientSecret(null); loadBillingSummary(); }}
                                    onCancel={() => { setView('info'); setSetupClientSecret(null); }}
                                />
                            </Elements>
                        ) : (
                            <div className="flex flex-col items-center py-20 gap-4">
                                <Loader2 size={40} className="text-purple-500 animate-spin" />
                                <p className="text-slate-400 font-medium">Initializing secure portal...</p>
                            </div>
                        )}
                    </div>
                )}

                {/* ── Cancel Confirmation View ── */}
                {view === 'cancel-confirm' && (
                    <div className="max-w-xl mx-auto space-y-8 animate-in fade-in zoom-in-95 duration-300">
                        <div className="text-center space-y-4">
                            <div className="w-20 h-20 rounded-3xl bg-red-500/10 flex items-center justify-center mx-auto text-red-500 border border-red-500/20">
                                <AlertTriangle size={40} />
                            </div>
                            <h2 className="text-3xl font-black text-white">Cancel Subscription?</h2>
                            <p className="text-slate-400 text-lg">We're sorry to see you go. Here's what will happen if you cancel:</p>
                        </div>

                        <div className="bg-white/5 border border-white/10 rounded-3xl p-8 space-y-6">
                            <div className="flex items-start gap-4">
                                <div className="w-8 h-8 rounded-full bg-red-500/20 flex items-center justify-center text-red-400 flex-shrink-0">
                                    <X size={16} />
                                </div>
                                <div>
                                    <p className="font-bold text-white">Paid access will be blocked</p>
                                    <p className="text-sm text-slate-400 mt-1">You will lose access to <span className="text-white font-semibold">{workspace.name}</span> at the end of your billing period.</p>
                                </div>
                            </div>
                            <div className="flex items-start gap-4">
                                <div className="w-8 h-8 rounded-full bg-green-500/20 flex items-center justify-center text-green-400 flex-shrink-0">
                                    <CheckCircle size={16} />
                                </div>
                                <div>
                                    <p className="font-bold text-white">Your data is safe</p>
                                    <p className="text-sm text-slate-400 mt-1">We won't delete your workspace or data. You can renew at any time to regain access.</p>
                                </div>
                            </div>
                        </div>

                        {error && (
                            <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-6 py-4 rounded-2xl text-sm font-medium animate-bounce">
                                {error}
                            </div>
                        )}

                        <div className="flex flex-col md:flex-row gap-4 pt-4">
                            <button onClick={() => setView('info')}
                                className="flex-1 py-4 rounded-2xl bg-white/5 border border-white/10 text-white font-bold text-lg hover:bg-white/10 transition-all shadow-xl">
                                Keep My Plan
                            </button>
                            <button onClick={confirmCancel}
                                className="flex-1 py-4 rounded-2xl bg-red-600 hover:bg-red-700 text-white font-bold text-lg transition-all shadow-xl shadow-red-900/20">
                                Confirm Cancellation
                            </button>
                        </div>
                    </div>
                )}

                {/* ── Progress Indicators ── */}
                {view === 'cancelling' && (
                    <div className="flex flex-col items-center py-32 gap-6 animate-pulse">
                        <div className="relative">
                            <Loader2 size={64} className="text-purple-500 animate-spin" />
                            <div className="absolute inset-0 flex items-center justify-center">
                                <RefreshCw size={24} className="text-purple-300" />
                            </div>
                        </div>
                        <p className="text-white text-2xl font-bold tracking-tight">Processing request…</p>
                        <p className="text-slate-500 font-medium">Please don't close your browser.</p>
                    </div>
                )}

                {/* ── Success View ── */}
                {view === 'done' && (
                    <div className="max-w-md mx-auto py-12 text-center space-y-8 animate-in fade-in zoom-in-95 duration-500">
                        <div className="relative">
                            <div className="absolute inset-0 bg-green-500/20 blur-3xl rounded-full scale-150"></div>
                            <div className="relative w-24 h-24 rounded-full bg-green-500 flex items-center justify-center mx-auto text-white shadow-2xl shadow-green-900/40">
                                <CheckCircle size={48} />
                            </div>
                        </div>
                        <div className="space-y-3">
                            <h2 className="text-3xl font-black text-white">All Set!</h2>
                            <p className="text-slate-400 text-lg leading-relaxed">Your subscription has been successfully updated. We've sent a confirmation to your email.</p>
                        </div>
                        <button onClick={onCancelled}
                            className="w-full py-4 rounded-2xl bg-gradient-to-r from-green-600 to-emerald-600 text-white font-bold text-lg hover:from-green-700 hover:to-emerald-700 transition-all shadow-xl shadow-green-900/20">
                            Back to Workspace
                        </button>
                    </div>
                )}
                </div>
            </div>

            {/* Success Toast for Card Update */}
            {cardUpdated && view === 'info' && (
                <div className="fixed bottom-8 left-1/2 -translate-x-1/2 bg-green-600 text-white px-8 py-4 rounded-2xl shadow-2xl flex items-center gap-3 animate-in fade-in slide-in-from-bottom-8 duration-500 z-[60]">
                    <CheckCircle size={20} />
                    <span className="font-bold">Payment method updated successfully!</span>
                    <button onClick={() => setCardUpdated(false)} className="ml-4 hover:opacity-70 transition-opacity">
                        <X size={18} />
                    </button>
                </div>
            )}
        </div>
    );
};

export default BillingManagement;
