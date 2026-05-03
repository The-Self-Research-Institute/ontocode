import React, { useState } from 'react';
import { Check, X, Sparkles, Zap, Crown, ArrowRight, LogOut, Bug, Star, Shield, Gift } from 'lucide-react';
import ReportIssueModal from './ReportIssueModal';
import { usePlanPricing } from '../hooks/usePlanPricing';

interface SubscriptionPlanSelectionProps {
    username: string;
    workspaceId: string;
    workspaceName: string;
    onPlanSelected: (planId: string, interval: "monthly" | "annual") => void;
    onSkip: () => void;
    onLogout: () => void;
}

type BillingInterval = "monthly" | "annual";

interface Plan {
    id: string;
    name: string;
    icon: React.ReactNode;
    monthlyPrice: number;
    annualPrice: number;
    description: string;
    tagline: string;
    features: string[];
    limitations?: string[];
    popular?: boolean;
    gradient: string;
    glowColor: string;
    badge?: string;
}

const PLANS: Plan[] = [
    {
        id: 'FREE',
        name: 'Free',
        icon: <Sparkles size={22} />,
        monthlyPrice: 0,
        annualPrice: 0,
        description: 'Get started at no cost',
        tagline: 'Perfect for solo ontology engineers exploring the platform',
        features: [
            'Up to 3 workspaces',
            'Up to 3 workspace members',
            '10 GB storage',
            'OWL/RDF ontology editing',
            'Class hierarchy & properties',
            'SPARQL query execution',
            'SWRL rule editor',
            'DL Query & reasoning',
            'Import OWL/TTL/RDF files',
            'Custom plugin support',
            'Community support',
        ],
        limitations: [
            'No team collaboration',
            'No shared editing',
        ],
        gradient: 'from-slate-500 to-slate-700',
        glowColor: 'shadow-slate-500/30',
    },
    {
        id: 'PRO',
        name: 'Professional',
        icon: <Zap size={22} />,
        monthlyPrice: 29,
        annualPrice: 24,
        description: 'For serious ontology teams',
        tagline: 'Collaborate with your team and unlock advanced features',
        features: [
            'Up to 10 workspaces',
            'Up to 10 team members',
            '100 GB storage',
            'Everything in Free',
            'Team collaboration enabled',
            'Invite & manage members',
            'Priority email support',
            'Export to multiple formats',
        ],
        popular: true,
        badge: 'Most Popular',
        gradient: 'from-violet-500 to-indigo-600',
        glowColor: 'shadow-violet-500/40',
    },
    {
        id: 'ENTERPRISE',
        name: 'Enterprise',
        icon: <Crown size={22} />,
        monthlyPrice: 99,
        annualPrice: 79,
        description: 'For large organizations',
        tagline: 'Unlimited scale with dedicated support and SLA',
        features: [
            'Unlimited team members',
            'Unlimited workspaces',
            'Unlimited storage',
            'Everything in Professional',
            'Priority support channel',
            'Early access to new features',
        ],
        gradient: 'from-amber-500 to-orange-600',
        glowColor: 'shadow-amber-500/40',
        badge: 'Best Value',
    },
];

const SubscriptionPlanSelection: React.FC<SubscriptionPlanSelectionProps> = ({
    username,
    workspaceName,
    onPlanSelected,
    onSkip,
    onLogout,
}) => {
    const [selectedPlan, setSelectedPlan] = useState<string>('PRO');
    const [billingInterval, setBillingInterval] = useState<BillingInterval>('annual');
    const [isReportIssueModalOpen, setIsReportIssueModalOpen] = useState(false);

    const { getPricing, trialPeriodDays } = usePlanPricing();
    const plans = PLANS.map(plan => {
        const live = getPricing(plan.id);
        return {
            ...plan,
            monthlyPrice: live.monthlyPrice,
            annualPrice: live.annualPrice,
            features: live.features.length ? live.features : plan.features,
            limitations: live.limitations.length ? live.limitations : (plan.limitations ?? []),
        };
    });

    const getDisplayPrice = (plan: Plan) =>
        billingInterval === 'annual' ? plan.annualPrice : plan.monthlyPrice;

    const getAnnualSavings = (plan: Plan) => {
        if (plan.monthlyPrice === 0) return 0;
        return (plan.monthlyPrice - plan.annualPrice) * 12;
    };

    const getDiscountPercent = (plan: Plan) => {
        if (plan.monthlyPrice === 0) return 0;
        return Math.round((plan.monthlyPrice - plan.annualPrice) / plan.monthlyPrice * 100);
    };

    const discountPercentages = plans.filter(p => p.monthlyPrice > 0).map(getDiscountPercent);
    const maxDiscount = discountPercentages.length > 0 ? Math.max(...discountPercentages) : 0;

    const handleContinue = () => {
        onPlanSelected(selectedPlan, billingInterval);
    };

    const selectedPlanData = plans.find(p => p.id === selectedPlan);
    const selectedDiscount = selectedPlanData ? getDiscountPercent(selectedPlanData) : 0;
    const badgeDiscount = selectedDiscount > 0 ? selectedDiscount : maxDiscount;

    return (
        <div className="dark-surface h-screen bg-gradient-to-br from-slate-950 via-violet-950 to-slate-950 relative overflow-y-auto">
            {/* Background orbs */}
            <div className="absolute inset-0 overflow-hidden pointer-events-none">
                <div className="absolute -top-32 -right-32 w-96 h-96 bg-violet-600 rounded-full mix-blend-multiply filter blur-3xl opacity-15 animate-pulse" />
                <div className="absolute -bottom-32 -left-32 w-96 h-96 bg-indigo-600 rounded-full mix-blend-multiply filter blur-3xl opacity-15 animate-pulse" style={{ animationDelay: '1s' }} />
                <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-80 h-80 bg-purple-600 rounded-full mix-blend-multiply filter blur-3xl opacity-10 animate-pulse" style={{ animationDelay: '2s' }} />
            </div>

            <div className="relative z-10 min-h-full flex flex-col px-4 py-6 sm:px-6">

                {/* Top bar */}
                <div className="flex items-center justify-between mb-6">
                    <div>
                        <div className="flex items-center gap-2 mb-1">
                            <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-violet-500 to-indigo-600 flex items-center justify-center">
                                <Star size={14} className="text-white" />
                            </div>
                            <span className="text-violet-300 text-xs font-semibold uppercase tracking-widest">OntoCode</span>
                        </div>
                        <h1 className="text-2xl sm:text-3xl font-bold text-white">
                            Start your free trial
                        </h1>
                        <p className="text-slate-400 text-sm mt-0.5">
                            Hi <span className="text-violet-300 font-medium">{username}</span> — workspace <span className="text-violet-300 font-medium">{workspaceName}</span>
                        </p>
                    </div>
                    <button
                        onClick={onLogout}
                        className="flex items-center gap-2 px-3 py-2 bg-white/5 hover:bg-white/10 border border-white/10 text-slate-300 hover:text-white rounded-lg transition-all text-sm"
                    >
                        <LogOut size={15} />
                        <span className="hidden sm:inline">Logout</span>
                    </button>
                </div>

                {/* Trial banner */}
                <div className="mb-5 flex justify-center">
                    <div className="flex items-center gap-2.5 px-5 py-2.5 bg-gradient-to-r from-violet-600/30 to-indigo-600/30 border border-violet-500/40 rounded-full backdrop-blur-sm">
                        <Gift size={16} className="text-violet-300" />
                        <span className="text-white text-sm font-medium">
                            {trialPeriodDays}-day free trial on all paid plans —{' '}
                            <span className="text-violet-300">card saved, not charged until day {trialPeriodDays + 1}</span>
                        </span>
                    </div>
                </div>

                {/* Billing toggle */}
                <div className="flex justify-center mb-6">
                    <div className="flex items-center bg-white/5 border border-white/10 rounded-xl p-1">
                        <button
                            onClick={() => setBillingInterval('monthly')}
                            className={`px-4 py-1.5 rounded-lg text-sm font-medium transition-all ${
                                billingInterval === 'monthly'
                                    ? 'bg-purple-600 text-white shadow-sm'
                                    : 'text-slate-400 hover:text-white'
                            }`}
                        >
                            Monthly
                        </button>
                        <button
                            onClick={() => setBillingInterval('annual')}
                            className={`px-4 py-1.5 rounded-lg text-sm font-medium transition-all flex items-center gap-2 ${
                                billingInterval === 'annual'
                                    ? 'bg-purple-600 text-white shadow-sm'
                                    : 'text-slate-400 hover:text-white'
                            }`}
                        >
                            Annual
                            <span className={`text-xs font-semibold px-1.5 py-0.5 rounded-full ${
                                billingInterval === 'annual'
                                    ? 'bg-green-500 text-white'
                                    : 'bg-green-600/30 text-green-400'
                            }`}>
                                Save {badgeDiscount}%
                            </span>
                        </button>
                    </div>
                </div>

                {/* Plan cards */}
                <div className="flex-1 flex items-start justify-center">
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl w-full">
                        {PLANS.map((plan) => {
                            const isSelected = selectedPlan === plan.id;
                            const savings = getAnnualSavings(plan);
                            const price = getDisplayPrice(plan);

                            return (
                                <div
                                    key={plan.id}
                                    onClick={() => setSelectedPlan(plan.id)}
                                    className={`
                                        relative flex flex-col rounded-2xl cursor-pointer transition-all duration-200
                                        ${isSelected
                                            ? `bg-white/10 border-2 border-violet-400 shadow-2xl ${plan.glowColor} scale-[1.02]`
                                            : 'bg-white/5 border-2 border-white/10 hover:border-white/25 hover:bg-white/8'
                                        }
                                        ${plan.popular ? 'ring-2 ring-violet-500/50' : ''}
                                    `}
                                >
                                    {/* Badge */}
                                    {plan.badge && (
                                        <div className="absolute -top-3 left-1/2 -translate-x-1/2">
                                            <span className={`bg-gradient-to-r ${plan.gradient} text-white px-3 py-0.5 rounded-full text-xs font-bold shadow-lg`}>
                                                {plan.badge}
                                            </span>
                                        </div>
                                    )}

                                    {/* Selected checkmark */}
                                    {isSelected && (
                                        <div className="absolute top-3 right-3 w-6 h-6 rounded-full bg-violet-500 flex items-center justify-center">
                                            <Check size={13} className="text-white" />
                                        </div>
                                    )}

                                    <div className="p-5 flex flex-col flex-1">
                                        {/* Header */}
                                        <div className="flex items-center gap-3 mb-3">
                                            <div className={`w-9 h-9 rounded-xl bg-gradient-to-br ${plan.gradient} flex items-center justify-center text-white shadow-lg flex-shrink-0`}>
                                                {plan.icon}
                                            </div>
                                            <div>
                                                <h3 className="text-base font-bold text-white leading-tight">{plan.name}</h3>
                                                <p className="text-slate-400 text-xs">{plan.description}</p>
                                            </div>
                                        </div>

                                        {/* Price */}
                                        <div className="mb-1">
                                            <div className="flex items-baseline gap-1">
                                                <span className="text-3xl font-extrabold text-white">${price}</span>
                                                {plan.monthlyPrice > 0 && (
                                                    <span className="text-slate-400 text-xs">/ mo</span>
                                                )}
                                                {plan.monthlyPrice === 0 && (
                                                    <span className="text-slate-400 text-xs">forever</span>
                                                )}
                                            </div>
                                            {billingInterval === 'annual' && plan.monthlyPrice > 0 && (
                                                <p className="text-xs text-slate-400 mt-0.5">
                                                    ${plan.annualPrice * 12}/yr
                                                    {savings > 0 && (
                                                        <span className="ml-1.5 text-green-400 font-semibold">
                                                            · saves ${savings} ({getDiscountPercent(plan)}% off)
                                                        </span>
                                                    )}
                                                </p>
                                            )}
                                            {billingInterval === 'monthly' && plan.monthlyPrice > 0 && (
                                                <p className="text-xs text-slate-500 mt-0.5">Billed monthly</p>
                                            )}
                                        </div>

                                        {/* Tagline */}
                                        <p className="text-violet-200/70 text-xs mb-3 italic">{plan.tagline}</p>

                                        <hr className="border-white/10 mb-3" />

                                        {/* Features */}
                                        <div className="flex-1 space-y-2">
                                            {plan.features.map((f, i) => (
                                                <div key={i} className="flex items-start gap-2">
                                                    <Check size={13} className="text-green-400 flex-shrink-0 mt-0.5" />
                                                    <span className="text-slate-300 text-xs leading-relaxed">{f}</span>
                                                </div>
                                            ))}
                                            {plan.limitations?.map((l, i) => (
                                                <div key={i} className="flex items-start gap-2">
                                                    <X size={13} className="text-slate-600 flex-shrink-0 mt-0.5" />
                                                    <span className="text-slate-500 text-xs leading-relaxed">{l}</span>
                                                </div>
                                            ))}
                                        </div>

                                        {/* Trial note */}
                                        {plan.monthlyPrice > 0 && (
                                            <div className="mt-3 pt-3 border-t border-white/10">
                                                <div className="flex items-center gap-1.5 text-violet-300 text-xs">
                                                    <Shield size={12} />
                                                    <span>{trialPeriodDays}-day trial • card not charged until day {trialPeriodDays + 1}</span>
                                                </div>
                                            </div>
                                        )}
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                </div>

                {/* CTA */}
                <div className="mt-6 flex flex-col items-center gap-3">
                    {selectedPlanData && selectedPlanData.monthlyPrice > 0 && (
                        <p className="text-slate-400 text-xs text-center">
                            You'll start your{' '}
                            <span className="text-white font-medium">{trialPeriodDays}-day free trial</span>
                            {' '}of <span className="text-violet-300 font-medium">{selectedPlanData.name}</span>.
                            {' '}Your card won't be charged until the trial ends.
                        </p>
                    )}

                    <div className="flex items-center gap-3">
                        <button
                            onClick={onSkip}
                            className="px-5 py-2.5 bg-white/5 hover:bg-white/10 border border-white/10 text-slate-300 hover:text-white rounded-xl transition-all text-sm"
                        >
                            Skip for now
                        </button>
                        <button
                            onClick={handleContinue}
                            className="px-7 py-2.5 bg-gradient-to-r from-violet-600 to-indigo-600 hover:from-violet-500 hover:to-indigo-500 text-white rounded-xl font-semibold transition-all flex items-center gap-2 shadow-lg shadow-violet-500/30 text-sm"
                        >
                            {selectedPlanData?.monthlyPrice === 0
                                ? `Continue with ${selectedPlanData.name}`
                                : `Start free trial — ${selectedPlanData?.name}`}
                            <ArrowRight size={15} />
                        </button>
                    </div>

                    <p className="text-slate-500 text-xs">
                        Card required • not charged for {trialPeriodDays} days • cancel anytime before trial ends
                    </p>
                </div>
            </div>

            {/* Report Issue */}
            <button
                onClick={() => setIsReportIssueModalOpen(true)}
                className="fixed bottom-4 right-4 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-white/5 hover:bg-white/10 border border-white/10 text-xs font-medium text-slate-400 hover:text-white transition-colors backdrop-blur-sm z-50"
            >
                <Bug size={13} />
                Report Issue
            </button>

            {isReportIssueModalOpen && (
                <ReportIssueModal onClose={() => setIsReportIssueModalOpen(false)} />
            )}
        </div>
    );
};

export default SubscriptionPlanSelection;
