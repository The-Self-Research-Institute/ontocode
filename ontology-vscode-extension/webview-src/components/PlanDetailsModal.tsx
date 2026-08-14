import React, { useState, useMemo } from 'react';
import { Elements, PaymentElement, useStripe, useElements } from '@stripe/react-stripe-js';
// '/pure' entry: no eager script injection at import time — the default entry's
// module side-effect races React's commit on first lazy mount (removeChild crash).
import { loadStripe } from '@stripe/stripe-js/pure';
import type { StripeElementsOptions } from '@stripe/stripe-js';
import { X, Check, Crown, Zap, Sparkles, Users, HardDrive, Shield, Rocket, Loader2, CreditCard, CheckCircle, ArrowLeft } from 'lucide-react';
import { useSubscription } from '../hooks/useSubscription';
import { usePlanPricing } from '../hooks/usePlanPricing';
import { isInheritedPlanFeature, orderPlanFeatures } from '../utils/planFeatures';
import { billingErrorMessage, billingPost } from '../utils/billingApi';

interface PlanDetailsModalProps {
    isOpen: boolean;
    onClose: () => void;
    onUpgrade?: (planId: string) => void;
    isUpgrading?: boolean;
    workspaceId?: string;
    currentPlanOnly?: boolean;
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

        try {
            await billingPost('/api/billing/update-payment-method', { setupIntentId: setupIntent.id, workspaceId });
        } catch (err: any) {
            setError(billingErrorMessage(err, 'Failed to update payment method.'));
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
            <div className="flex items-center gap-2 text-xs text-slate-400">
                <Shield size={13} className="text-green-400 flex-shrink-0" />
                <span>Secured by Stripe — existing card replaced immediately</span>
            </div>
            <div className="flex gap-3">
                <button type="button" onClick={onCancel} disabled={submitting}
                    className="flex-1 py-3 rounded-xl border border-white/20 bg-white/5 text-slate-300 font-medium hover:bg-white/10 transition-all disabled:opacity-40">
                    Back
                </button>
                <button type="submit" disabled={submitting || !stripe || !elements}
                    className="flex-[2] py-3 rounded-xl bg-gradient-to-r from-violet-600 to-indigo-600 text-white font-semibold hover:from-violet-500 hover:to-indigo-500 transition-all disabled:opacity-50 flex items-center justify-center gap-2">
                    {submitting
                        ? <><Loader2 size={18} className="animate-spin" /> Saving…</>
                        : <><CheckCircle size={18} /> Save new card</>
                    }
                </button>
            </div>
        </form>
    );
};

// ─── Main modal ──────────────────────────────────────────────────────────────
type View = 'plans' | 'update-card' | 'done';

const PLANS = [
    {
        id: 'free',
        name: 'Free',
        icon: <Sparkles size={20} />,
        monthlyPrice: 0,
        annualPrice: 0,
        gradient: 'from-slate-500 to-slate-700',
        glowColor: 'shadow-slate-500/30',
        features: [
            'Up to 3 workspaces',
            'Up to 3 workspace members',
            'OWL/RDF ontology editing',
            'SPARQL query execution',
            'SWRL rule editor',
            'DL Query & reasoning',
            'Invite & manage members',
            'Community support',
        ],
    },
    {
        id: 'pro',
        name: 'Professional',
        icon: <Zap size={20} />,
        monthlyPrice: 59,
        annualPrice: 59,
        gradient: 'from-violet-500 to-indigo-600',
        glowColor: 'shadow-violet-500/40',
        badge: 'Most Popular',
        features: [
            'Up to 10 workspaces',
            'Up to 10 workspace members',
            'Everything in Free',
            'Role-based editing for members',
            'Export to multiple formats',
            'Priority email support',
        ],
    },
    {
        id: 'enterprise',
        name: 'Enterprise',
        icon: <Crown size={20} />,
        monthlyPrice: 299,
        annualPrice: 299,
        gradient: 'from-amber-500 to-orange-600',
        glowColor: 'shadow-amber-500/40',
        badge: 'Best Value',
        features: [
            'Unlimited workspace members',
            'Unlimited workspaces',
            'Everything in Professional',
            'Early access to new features',
            'Priority channel support',
        ],
    },
];

const PlanDetailsModal: React.FC<PlanDetailsModalProps> = ({
    isOpen,
    onClose,
    onUpgrade,
    isUpgrading = false,
    workspaceId = '',
    currentPlanOnly = false,
}) => {
    const subscription = useSubscription();
    const { getPricing } = usePlanPricing();
    const plans = PLANS.map(plan => {
        const live = getPricing(plan.id);
        return {
            ...plan,
            monthlyPrice: live.monthlyPrice,
            annualPrice: live.annualPrice,
            features: live.features.length ? live.features : plan.features,
        };
    });
    const [selectedPlan, setSelectedPlan] = useState<string | null>(null);
    const [view, setView] = useState<View>('plans');
    const [billingInterval, setBillingInterval] = useState<'monthly' | 'annual'>('monthly');
    const [setupClientSecret, setSetupClientSecret] = useState<string | null>(null);
    const [setupPublishableKey, setSetupPublishableKey] = useState<string>('');
    const [error, setError] = useState<string | null>(null);

    const stripePromise = useMemo(
        () => (setupPublishableKey ? loadStripe(setupPublishableKey) : null),
        [setupPublishableKey],
    );

    const elementsOptions: StripeElementsOptions | undefined = useMemo(
        () => setupClientSecret
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
            : undefined,
        [setupClientSecret],
    );

    if (!isOpen) return null;

    const currentPlan = plans.find(p => p.id === subscription.plan) || plans[0];
    const getPlanRank = (id: string) => {
        const ranks = { free: 1, pro: 2, enterprise: 3 };
        return ranks[id.toLowerCase() as keyof typeof ranks] || 0;
    };
    const getAnnualSavings = (plan: typeof plans[number]) => {
        if (plan.monthlyPrice === 0) return 0;
        return (plan.monthlyPrice - plan.annualPrice) * 12;
    };
    const getDiscountPercent = (plan: typeof plans[number]) => {
        if (plan.monthlyPrice === 0) return 0;
        return Math.round(((plan.monthlyPrice - plan.annualPrice) / plan.monthlyPrice) * 100);
    };
    const currentRank = getPlanRank(subscription.plan);
    const availablePlans = plans.filter(plan => currentRank >= 2 ? getPlanRank(plan.id) >= currentRank : true);
    const discountPercentages = availablePlans
        .filter(plan => plan.monthlyPrice > 0)
        .map(getDiscountPercent);
    const maxDiscount = discountPercentages.length > 0 ? Math.max(...discountPercentages) : 0;
    const currentIntervalLabel = billingInterval === 'annual' ? 'Annual' : 'Monthly';

    const handleClose = () => {
        setView('plans');
        setError(null);
        setSetupClientSecret(null);
        setSetupPublishableKey('');
        onClose();
    };

    const startCardUpdate = async () => {
        setError(null);
        try {
            const data = await billingPost<{ clientSecret?: string; stripePublishableKey?: string }>('/api/billing/setup');
            if (!data?.clientSecret) throw new Error('Failed to create payment setup');
            setSetupPublishableKey(data.stripePublishableKey || '');
            setSetupClientSecret(data.clientSecret);
            setView('update-card');
        } catch (err: any) {
            setError(billingErrorMessage(err, 'Failed to create payment setup'));
        }
    };

    return (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
            <div className="bg-gradient-to-br from-slate-900 via-violet-950/40 to-slate-900 border border-white/10 rounded-2xl shadow-2xl max-w-5xl w-full h-[90vh] max-h-[760px] flex flex-col overflow-hidden">

                {/* ── Header ── */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-white/10 flex-shrink-0">
                    <div className="flex items-center gap-3">
                        {view !== 'plans' && (
                            <button
                                onClick={() => { setView('plans'); setSetupClientSecret(null); setSetupPublishableKey(''); }}
                                className="p-1.5 hover:bg-white/10 rounded-lg transition-colors text-slate-400 hover:text-white"
                            >
                                <ArrowLeft size={18} />
                            </button>
                        )}
                        <div>
                            <h2 className="text-xl font-bold text-white">
                                {view === 'plans' && 'Subscription Plan'}
                                {view === 'update-card' && 'Update Payment Method'}
                                {view === 'done' && 'Card Updated'}
                            </h2>
                            {view === 'plans' && (
                                <p className="text-xs text-slate-400 mt-0.5">
                                    Current plan: <span className="text-violet-300 font-semibold">{currentPlan.name}</span>
                                </p>
                            )}
                        </div>
                    </div>
                    <button onClick={handleClose} className="p-2 hover:bg-white/10 rounded-lg transition-colors text-slate-400 hover:text-white">
                        <X size={18} />
                    </button>
                </div>

                <div className="overflow-y-auto flex-1">

                    {/* ── Plans view ── */}
                    {view === 'plans' && (
                        <div className="p-6 space-y-6">

                            {/* Billing interval toggle (controls displayed price + upgrade intent) */}
                            {!currentPlanOnly && <div className="flex justify-center">
                                <div className="flex items-center bg-white/5 border border-white/10 rounded-xl p-1">
                                    <button
                                        onClick={() => setBillingInterval('monthly')}
                                        className={`px-4 py-1.5 rounded-lg text-sm font-medium transition-all ${
                                            billingInterval === 'monthly'
                                                ? 'bg-violet-600 text-white shadow-sm'
                                                : 'text-slate-400 hover:text-white'
                                        }`}
                                    >
                                        Monthly
                                    </button>
                                    <button
                                        onClick={() => setBillingInterval('annual')}
                                        className={`px-4 py-1.5 rounded-lg text-sm font-medium transition-all flex items-center gap-2 ${
                                            billingInterval === 'annual'
                                                ? 'bg-violet-600 text-white shadow-sm'
                                                : 'text-slate-400 hover:text-white'
                                        }`}
                                    >
                                        Annual
                                        {maxDiscount > 0 && (
                                            <span className={`text-xs font-semibold px-1.5 py-0.5 rounded-full ${
                                                billingInterval === 'annual'
                                                    ? 'bg-green-500 text-white'
                                                    : 'bg-green-600/30 text-green-400'
                                            }`}>
                                                Save {maxDiscount}%
                                            </span>
                                        )}
                                    </button>
                                </div>
                            </div>}

                            {/* Current plan stats card */}
                            <div className={`rounded-xl bg-gradient-to-br ${currentPlan.gradient} p-px`}>
                                <div className="rounded-xl bg-slate-900/90 p-5">
                                    <div className="flex items-center justify-between mb-4">
                                        <div className="flex items-center gap-3">
                                            <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${currentPlan.gradient} flex items-center justify-center text-white shadow-lg`}>
                                                {currentPlan.icon}
                                            </div>
                                            <div>
                                                <p className="text-xs text-slate-400">Active plan</p>
                                                <h3 className="text-base font-bold text-white">{currentPlan.name}</h3>
                                            </div>
                                        </div>
                                        {!currentPlanOnly && (
                                            <button
                                                onClick={startCardUpdate}
                                                className="flex items-center gap-1.5 px-3 py-1.5 bg-white/5 hover:bg-white/10 border border-white/15 text-slate-300 hover:text-white rounded-lg transition-all text-xs font-medium"
                                            >
                                                <CreditCard size={13} />
                                                Update payment method
                                            </button>
                                        )}
                                    </div>
                                    <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                                        <div className="bg-white/5 rounded-lg p-3">
                                            <Users size={16} className="text-violet-400 mb-1.5" />
                                            <p className="text-[11px] text-slate-400">Workspace Members</p>
                                            <p className="text-base font-bold text-white">
                                                {subscription.limits.maxTeamMembers === Infinity ? 'Unlimited' : subscription.limits.maxTeamMembers}
                                            </p>
                                        </div>
                                        <div className="bg-white/5 rounded-lg p-3">
                                            <HardDrive size={16} className="text-blue-400 mb-1.5" />
                                            <p className="text-[11px] text-slate-400">Storage</p>
                                            <p className="text-base font-bold text-white">
                                                {subscription.limits.storageGB === Infinity ? 'Unlimited' : `${subscription.limits.storageGB} GB`}
                                            </p>
                                        </div>
                                        <div className="bg-white/5 rounded-lg p-3">
                                            <Shield size={16} className="text-green-400 mb-1.5" />
                                            <p className="text-[11px] text-slate-400">Collaboration</p>
                                            <p className="text-base font-bold text-white">
                                                {subscription.limits.hasAdvancedCollaboration ? 'Advanced' : subscription.limits.hasBasicCollaboration ? 'Basic' : 'None'}
                                            </p>
                                        </div>
                                        <div className="bg-white/5 rounded-lg p-3">
                                            <Rocket size={16} className="text-orange-400 mb-1.5" />
                                            <p className="text-[11px] text-slate-400">Workspaces</p>
                                            <p className="text-base font-bold text-white">
                                                {subscription.limits.maxWorkspaces === Infinity ? 'Unlimited' : subscription.limits.maxWorkspaces}
                                            </p>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            {error && (
                                <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-xl text-sm">{error}</div>
                            )}

                            {currentPlanOnly && (
                                <div className="bg-white/5 border border-white/10 rounded-xl p-4">
                                    <h3 className="text-xs font-semibold text-slate-400 mb-3 uppercase tracking-widest">Included In Your Plan</h3>
                                    <ul className="grid md:grid-cols-2 gap-2">
                                        {orderPlanFeatures(currentPlan.features).map((feature, index) => (
                                            <li key={index} className="flex items-start gap-2">
                                                <Check size={13} className="text-green-400 flex-shrink-0 mt-0.5" />
                                                <span className={`text-xs leading-relaxed ${isInheritedPlanFeature(feature) ? 'font-semibold text-white' : 'text-slate-300'}`}>
                                                    {feature}
                                                </span>
                                            </li>
                                        ))}
                                    </ul>
                                </div>
                            )}

                            {/* No-Downgrade Policy Warning */}
                            {!currentPlanOnly && subscription.plan && subscription.plan.toLowerCase() !== 'free' && (
                                <div className="bg-amber-500/10 border border-amber-500/30 rounded-xl p-4 flex items-start gap-3">
                                    <Shield size={18} className="text-amber-400 flex-shrink-0 mt-0.5" />
                                    <div>
                                        <p className="text-sm font-semibold text-amber-200">Strict Plan Policy</p>
                                        <p className="text-xs text-amber-200/80 mt-1 leading-relaxed">
                                            As part of our premium service policy, downgrading from <strong>{currentPlan.name}</strong> to a lower plan tier is not permitted. You can continue with your current plan or upgrade to a higher tier.
                                        </p>
                                    </div>
                                </div>
                            )}

                            {!currentPlanOnly && <div className="bg-white/5 border border-white/10 rounded-xl p-4 flex items-start gap-3">
                                <Shield size={18} className="text-violet-300 flex-shrink-0 mt-0.5" />
                                <p className="text-xs text-slate-300 leading-relaxed">
                                    Paid plans renew automatically on the selected interval. Renewal reminders are sent 15, 7, and 1 day before renewal. Expired or canceled paid subscriptions block workspace access until renewed.
                                </p>
                            </div>}

                            {/* Plan comparison */}
                            {!currentPlanOnly && <div>
                                <h3 className="text-xs font-semibold text-slate-400 mb-3 uppercase tracking-widest">Compare Plans</h3>
                                <div className="grid md:grid-cols-3 gap-4">
                                    {availablePlans.map((plan) => {
                                        const isCurrent = plan.id === subscription.plan;
                                        const displayPrice = billingInterval === 'annual' ? plan.annualPrice : plan.monthlyPrice;
                                        const savings = getAnnualSavings(plan);
                                        const secondary = plan.monthlyPrice === 0
                                            ? 'Free forever'
                                            : billingInterval === 'annual'
                                                ? `$${(plan.annualPrice * 12)}/yr`
                                                : `$${plan.monthlyPrice}/mo`;

                                        return (
                                            <div
                                                key={plan.id}
                                                className={`relative rounded-xl border-2 p-5 transition-all flex flex-col ${
                                                    isCurrent
                                                        ? `border-violet-400 bg-white/8 shadow-lg ${plan.glowColor}`
                                                        : 'border-white/10 bg-white/4 hover:border-white/25 hover:bg-white/6'
                                                }`}
                                            >
                                                {plan.badge && (
                                                    <div className="absolute -top-3 left-1/2 -translate-x-1/2">
                                                        <span className={`bg-gradient-to-r ${plan.gradient} text-white px-3 py-0.5 rounded-full text-xs font-bold shadow-lg`}>
                                                            {plan.badge}
                                                        </span>
                                                    </div>
                                                )}
                                                {isCurrent && (
                                                    <div className="absolute top-3 right-3 w-5 h-5 rounded-full bg-violet-500 flex items-center justify-center">
                                                        <Check size={11} className="text-white" />
                                                    </div>
                                                )}

                                                <div className="flex items-center gap-2.5 mb-3">
                                                    <div className={`w-8 h-8 rounded-lg bg-gradient-to-br ${plan.gradient} flex items-center justify-center text-white shadow-md`}>
                                                        {plan.icon}
                                                    </div>
                                                    <div>
                                                        <h4 className="text-sm font-bold text-white leading-tight">{plan.name}</h4>
                                                        <p className="text-xs text-slate-400">
                                                            {secondary}
                                                        </p>
                                                    </div>
                                                </div>
                                                {plan.monthlyPrice > 0 && (
                                                    <div className="mb-3">
                                                        <div className="flex items-baseline gap-1">
                                                            <span className="text-2xl font-extrabold text-white">${displayPrice}</span>
                                                            <span className="text-slate-400 text-xs">/ mo</span>
                                                        </div>
                                                        {billingInterval === 'annual' && (
                                                            <p className="text-[11px] text-slate-500">
                                                                Billed annually
                                                                {savings > 0 && (
                                                                    <span className="ml-1.5 text-green-400 font-medium">
                                                                        Save {getDiscountPercent(plan)}%
                                                                    </span>
                                                                )}
                                                            </p>
                                                        )}
                                                        {billingInterval === 'monthly' && (
                                                            <p className="text-[11px] text-slate-500">
                                                                Billed monthly
                                                                {savings > 0 && (
                                                                    <span className="ml-1.5 text-green-400 font-medium">
                                                                        Save {getDiscountPercent(plan)}% with annual
                                                                    </span>
                                                                )}
                                                            </p>
                                                        )}
                                                    </div>
                                                )}

                                                <ul className="space-y-1.5 mb-4 flex-1">
                                                    {orderPlanFeatures(plan.features).map((f, i) => (
                                                        <li key={i} className="flex items-start gap-1.5">
                                                            <Check size={12} className="text-green-400 flex-shrink-0 mt-0.5" />
                                                            <span className={`text-xs leading-relaxed ${isInheritedPlanFeature(f) ? 'font-semibold text-white' : 'text-slate-300'}`}>{f}</span>
                                                        </li>
                                                    ))}
                                                </ul>

                                                {(() => {
                                                    const getPlanRank = (id: string) => {
                                                        const ranks = { free: 1, pro: 2, enterprise: 3 };
                                                        return ranks[id.toLowerCase() as keyof typeof ranks] || 0;
                                                    };
                                                    const currentRank = getPlanRank(subscription.plan);
                                                    const planRank = getPlanRank(plan.id);
                                                    const isUpgrade = planRank > currentRank;
                                                    const isDowngrade = planRank < currentRank;

                                                    if (isCurrent) {
                                                        return (
                                                            <button disabled className="w-full py-2 bg-violet-600/20 text-violet-300 rounded-lg text-sm font-semibold cursor-not-allowed border border-violet-500/30">
                                                                Current Plan
                                                            </button>
                                                        );
                                                    }

                                                    if (isDowngrade && currentRank > 1) {
                                                        return (
                                                            <button disabled className="w-full py-2 bg-slate-800 text-slate-500 rounded-lg text-sm font-semibold cursor-not-allowed border border-white/5 opacity-50">
                                                                Downgrade Restricted
                                                            </button>
                                                        );
                                                    }

                                                    return (
                                                        <button
                                                            onClick={() => {
                                                                if (onUpgrade && !isUpgrading) {
                                                                    setSelectedPlan(plan.id);
                                                                    onUpgrade(plan.id);
                                                                }
                                                            }}
                                                            disabled={isUpgrading}
                                                            className={`w-full py-2 bg-gradient-to-r ${plan.gradient} text-white rounded-lg text-sm font-semibold hover:opacity-90 transition-opacity disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2`}
                                                        >
                                                            {isUpgrading && selectedPlan === plan.id
                                                                ? <><Loader2 size={14} className="animate-spin" /> Processing…</>
                                                                : `${isUpgrade ? 'Upgrade' : 'Select'} to ${plan.name} (${currentIntervalLabel})`
                                                            }
                                                        </button>
                                                    );
                                                })()}
                                            </div>
                                        );
                                    })}
                                </div>
                            </div>}
                        </div>
                    )}

                    {/* ── Update card view ── */}
                    {view === 'update-card' && stripePromise && elementsOptions && (
                        <div className="p-6">
                            <p className="text-sm text-slate-400 mb-5">
                                Enter your new card details. Your existing payment method will be replaced immediately.
                            </p>
                            <Elements stripe={stripePromise} options={elementsOptions}>
                                <UpdateCardForm
                                    workspaceId={workspaceId}
                                    onSuccess={() => setView('done')}
                                    onCancel={() => {
                                        setView('plans');
                                        setSetupClientSecret(null);
                                        setSetupPublishableKey('');
                                    }}
                                />
                            </Elements>
                        </div>
                    )}

                    {/* ── Done view ── */}
                    {view === 'done' && (
                        <div className="p-6 flex flex-col items-center gap-4 py-14">
                            <div className="w-16 h-16 rounded-full bg-green-500/20 flex items-center justify-center">
                                <CheckCircle size={32} className="text-green-400" />
                            </div>
                            <div className="text-center">
                                <h3 className="text-lg font-bold text-white">Payment method updated</h3>
                                <p className="text-sm text-slate-400 mt-1">Your new card has been saved and will be used for future billing.</p>
                            </div>
                            <button
                                onClick={handleClose}
                                className="px-6 py-2.5 bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white rounded-xl font-semibold text-sm transition-all"
                            >
                                Done
                            </button>
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
};

export default PlanDetailsModal;
