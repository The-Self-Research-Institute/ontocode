import React from 'react';
import { X, Check, Crown, Zap, Sparkles, Users, HardDrive, Shield, Rocket } from 'lucide-react';
import { useSubscription } from '../hooks/useSubscription';

interface PlanDetailsModalProps {
    isOpen: boolean;
    onClose: () => void;
    onUpgrade?: (planId: string) => void;
}

const PlanDetailsModal: React.FC<PlanDetailsModalProps> = ({ isOpen, onClose, onUpgrade }) => {
    const subscription = useSubscription();

    if (!isOpen) return null;

    const plans = [
        {
            id: 'free',
            name: 'Free',
            icon: <Sparkles size={24} />,
            price: 0,
            gradient: 'from-gray-400 to-gray-600',
            features: [
                `${subscription.limits.maxTeamMembers} team members`,
                `${subscription.limits.storageGB} GB storage`,
                `${subscription.limits.maxWorkspaces} workspaces`,
                'Basic collaboration',
                'Community support',
                'Core ontology features'
            ]
        },
        {
            id: 'pro',
            name: 'Professional',
            icon: <Zap size={24} />,
            price: 29,
            gradient: 'from-purple-500 to-blue-600',
            badge: 'Most Popular',
            features: [
                'Up to 10 team members',
                '100 GB storage',
                '10 workspaces',
                'No collaboration',
                'Priority support',
                'Version control & history',
                'Custom plugins',
                'Advanced reasoning',
                'API access'
            ]
        },
        {
            id: 'enterprise',
            name: 'Enterprise',
            icon: <Crown size={24} />,
            price: 99,
            gradient: 'from-amber-500 to-orange-600',
            badge: 'Best Value',
            features: [
                'Unlimited team members',
                'Unlimited storage',
                'Unlimited workspaces',
                'Basic collaboration',
                'File sharing & comments',
                'Dedicated support 24/7',
                'Advanced security & SSO',
                'Custom integrations',
                'SLA guarantee',
                'On-premise option',
                'White-label solution'
            ]
        }
    ];

    const currentPlan = plans.find(p => p.id === subscription.plan);

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
            <div className="bg-white rounded-xl shadow-2xl max-w-5xl w-full max-h-[90vh] overflow-y-auto">
                {/* Header */}
                <div className="sticky top-0 bg-white border-b px-6 py-4 flex items-center justify-between z-10">
                    <div>
                        <h2 className="text-2xl font-bold text-gray-900">Subscription Plan</h2>
                        <p className="text-sm text-gray-600 mt-1">
                            Current plan: <span className="font-semibold text-purple-600">{currentPlan?.name}</span>
                        </p>
                    </div>
                    <button
                        onClick={onClose}
                        className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
                    >
                        <X size={20} className="text-gray-500" />
                    </button>
                </div>

                {/* Current Plan Details */}
                <div className="p-6 bg-gradient-to-r from-purple-50 to-blue-50 border-b">
                    <div className="flex items-center gap-4 mb-4">
                        <div className={`w-16 h-16 bg-gradient-to-br ${currentPlan?.gradient} rounded-xl flex items-center justify-center text-white`}>
                            {currentPlan?.icon}
                        </div>
                        <div>
                            <h3 className="text-xl font-bold text-gray-900">{currentPlan?.name} Plan</h3>
                            <p className="text-2xl font-bold text-purple-600">
                                ${currentPlan?.price}
                                <span className="text-sm font-normal text-gray-600">/month</span>
                            </p>
                        </div>
                    </div>

                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                        <div className="bg-white rounded-lg p-3 shadow-sm">
                            <Users size={20} className="text-purple-600 mb-1" />
                            <p className="text-xs text-gray-600">Team Members</p>
                            <p className="text-lg font-bold text-gray-900">
                                {subscription.limits.maxTeamMembers === Infinity ? 'Unlimited' : subscription.limits.maxTeamMembers}
                            </p>
                        </div>
                        <div className="bg-white rounded-lg p-3 shadow-sm">
                            <HardDrive size={20} className="text-blue-600 mb-1" />
                            <p className="text-xs text-gray-600">Storage</p>
                            <p className="text-lg font-bold text-gray-900">{subscription.limits.storageGB} GB</p>
                        </div>
                        <div className="bg-white rounded-lg p-3 shadow-sm">
                            <Shield size={20} className="text-green-600 mb-1" />
                            <p className="text-xs text-gray-600">Collaboration</p>
                            <p className="text-lg font-bold text-gray-900">
                                {subscription.limits.hasAdvancedCollaboration ? 'Advanced' : 'Basic'}
                            </p>
                        </div>
                        <div className="bg-white rounded-lg p-3 shadow-sm">
                            <Rocket size={20} className="text-orange-600 mb-1" />
                            <p className="text-xs text-gray-600">Workspaces</p>
                            <p className="text-lg font-bold text-gray-900">
                                {subscription.limits.maxWorkspaces === Infinity ? 'Unlimited' : subscription.limits.maxWorkspaces}
                            </p>
                        </div>
                    </div>
                </div>

                {/* All Plans Comparison */}
                <div className="p-6">
                    <h3 className="text-lg font-bold text-gray-900 mb-4">Compare Plans</h3>
                    <div className="grid md:grid-cols-3 gap-6">
                        {plans.map((plan) => (
                            <div
                                key={plan.id}
                                className={`rounded-xl border-2 p-6 transition-all ${
                                    plan.id === subscription.plan
                                        ? 'border-purple-500 bg-purple-50 shadow-lg'
                                        : 'border-gray-200 hover:border-gray-300'
                                }`}
                            >
                                {plan.badge && (
                                    <div className="mb-2">
                                        <span className="text-xs font-semibold text-purple-600 bg-purple-100 px-2 py-1 rounded">
                                            {plan.badge}
                                        </span>
                                    </div>
                                )}
                                <div className={`w-12 h-12 bg-gradient-to-br ${plan.gradient} rounded-lg flex items-center justify-center text-white mb-4`}>
                                    {plan.icon}
                                </div>
                                <h4 className="text-xl font-bold text-gray-900 mb-2">{plan.name}</h4>
                                <div className="mb-4">
                                    <span className="text-3xl font-bold text-gray-900">${plan.price}</span>
                                    <span className="text-sm text-gray-600">/month</span>
                                </div>

                                <ul className="space-y-2 mb-6">
                                    {plan.features.map((feature, idx) => (
                                        <li key={idx} className="flex items-start gap-2 text-sm text-gray-700">
                                            <Check size={16} className="text-green-600 flex-shrink-0 mt-0.5" />
                                            <span>{feature}</span>
                                        </li>
                                    ))}
                                </ul>

                                {plan.id === subscription.plan ? (
                                    <button
                                        disabled
                                        className="w-full py-2 bg-purple-600 text-white rounded-lg font-semibold cursor-not-allowed opacity-60"
                                    >
                                        Current Plan
                                    </button>
                                ) : plan.id === 'free' && subscription.plan !== 'free' ? (
                                    <button
                                        disabled
                                        className="w-full py-2 bg-gray-300 text-gray-600 rounded-lg font-semibold cursor-not-allowed"
                                    >
                                        Downgrade Unavailable
                                    </button>
                                ) : (
                                    <button
                                        onClick={() => {
                                            if (onUpgrade) {
                                                onUpgrade(plan.id);
                                            }
                                        }}
                                        className={`w-full py-2 bg-gradient-to-r ${plan.gradient} text-white rounded-lg font-semibold hover:opacity-90 transition-opacity`}
                                    >
                                        Upgrade to {plan.name}
                                    </button>
                                )}
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
};

export default PlanDetailsModal;
