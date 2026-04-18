import React, { useState } from 'react';
import { Check, X, Sparkles, Zap, Crown, ArrowRight, LogOut, Users, HardDrive, Shield } from 'lucide-react';

interface SubscriptionPlanSelectionProps {
    username: string;
    workspaceId: string;
    workspaceName: string;
    onPlanSelected: (planId: string) => void;
    onSkip: () => void;
    onLogout: () => void;
}

interface Plan {
    id: string;
    name: string;
    icon: React.ReactNode;
    price: number;
    period: string;
    description: string;
    features: string[];
    limitations?: string[];
    popular?: boolean;
    gradient: string;
    buttonColor: string;
    badge?: string;
}

const SubscriptionPlanSelection: React.FC<SubscriptionPlanSelectionProps> = ({
    username,
    workspaceId,
    workspaceName,
    onPlanSelected,
    onSkip,
    onLogout
}) => {
    const [selectedPlan, setSelectedPlan] = useState<string>('PRO');

    const plans: Plan[] = [
        {
            id: 'FREE',
            name: 'Free',
            icon: <Sparkles size={28} />,
            price: 0,
            period: 'forever',
            description: 'Perfect for getting started',
            features: [
                'Up to 10 team members',
                '5 GB storage',
                'OWL/RDF ontology editing',
                'Class hierarchy & properties',
                'SPARQL query execution',
                'SWRL rule editor',
                'DL Query & reasoning',
                'Import OWL/TTL/RDF files',
                'Custom plugin support',
                'Community support'
            ],
            limitations: [
                'No Live collaboration'
            ],
            gradient: 'from-gray-400 to-gray-600',
            buttonColor: 'bg-gray-600 hover:bg-gray-700'
        },
        {
            id: 'PRO',
            name: 'Professional',
            icon: <Zap size={28} />,
            price: 29,
            period: 'per month',
            description: 'For growing teams',
            features: [
                'Up to 50 team members',
                '50 GB storage',
                'All Free features',
                'Live collaboration',
                'Priority email support'
            ],
            popular: true,
            badge: 'Most Popular',
            gradient: 'from-purple-500 to-blue-600',
            buttonColor: 'bg-gradient-to-r from-purple-600 to-blue-600 hover:from-purple-700 hover:to-blue-700'
        },
        {
            id: 'ENTERPRISE',
            name: 'Enterprise',
            icon: <Crown size={28} />,
            price: 99,
            period: 'per month',
            description: 'For large organizations',
            features: [
                'Unlimited team members',
                'Unlimited storage',
                'All Pro features',
                'Advanced collaboration',
                'Dedicated support'
            ],
            gradient: 'from-amber-500 to-orange-600',
            buttonColor: 'bg-gradient-to-r from-amber-600 to-orange-600 hover:from-amber-700 hover:to-orange-700',
            badge: 'Best Value'
        }
    ];

    const handleContinue = () => {
        onPlanSelected(selectedPlan);
    };

    return (
      <div className="h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 relative overflow-y-auto">
        {/* Animated background */}
        <div className="absolute inset-0 overflow-hidden pointer-events-none">
          <div className="absolute -top-40 -right-40 w-64 h-64 md:w-96 md:h-96 bg-purple-500 rounded-full mix-blend-multiply filter blur-3xl opacity-20 animate-pulse"></div>
          <div className="absolute -bottom-40 -left-40 w-64 h-64 md:w-96 md:h-96 bg-blue-500 rounded-full mix-blend-multiply filter blur-3xl opacity-20 animate-pulse delay-1000"></div>
          <div className="absolute top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 w-64 h-64 md:w-96 md:h-96 bg-indigo-500 rounded-full mix-blend-multiply filter blur-3xl opacity-10 animate-pulse delay-500"></div>
        </div>

        {/* Content */}
        <div className="relative z-10 min-h-full flex flex-col justify-center px-4 py-6 sm:px-6 md:px-8">
          {/* Header */}
          <div className="flex items-center justify-between mb-4 flex-shrink-0">
            <div>
              <h1 className="text-2xl sm:text-3xl font-bold text-white mb-1 flex items-center gap-3">
                <span className="bg-gradient-to-r from-purple-400 to-blue-400 bg-clip-text text-transparent">
                  Choose Your Plan
                </span>
              </h1>
              <p className="text-gray-300 text-xs sm:text-sm">
                Welcome, <span className="font-semibold text-purple-300">{username}</span> • Workspace:{" "}
                <span className="font-medium text-purple-300">{workspaceName}</span>
              </p>
            </div>
            <button
              onClick={onLogout}
              className="flex items-center gap-2 px-3 py-1.5 sm:px-4 sm:py-2 bg-white/10 hover:bg-white/20 backdrop-blur-sm text-white rounded-lg transition-all text-sm"
            >
              <LogOut size={18} />
              <span className="hidden sm:inline">Logout</span>
            </button>
          </div>

          {/* Plans */}
          <div className="flex items-center justify-center py-4 flex-1">
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 max-w-7xl w-full">
              {plans.map((plan) => (
                <div
                  key={plan.id}
                  onClick={() => setSelectedPlan(plan.id)}
                  className={`
                    relative bg-white/10 backdrop-blur-xl rounded-2xl p-4 sm:p-5 cursor-pointer transition-all duration-300 flex flex-col
                    border-2 ${
                      selectedPlan === plan.id
                        ? "border-white shadow-2xl shadow-purple-500/50 scale-[1.02] md:scale-105"
                        : "border-white/20 hover:border-white/40 hover:scale-[1.01] md:hover:scale-102"
                    }
                    ${plan.popular ? "ring-4 ring-purple-500/50" : ""}
                  `}
                >
                  {plan.badge && (
                    <div className="absolute -top-3 left-1/2 transform -translate-x-1/2">
                      <span
                        className={`bg-gradient-to-r ${plan.gradient} text-white px-3 py-1 rounded-full text-xs font-bold shadow-lg`}
                      >
                        {plan.badge}
                      </span>
                    </div>
                  )}

                  {/* Icon & Title */}
                  <div className="mb-3">
                    <div
                      className={`w-10 h-10 rounded-xl bg-gradient-to-br ${plan.gradient} flex items-center justify-center text-white mb-2 shadow-lg`}
                    >
                      {React.cloneElement(plan.icon as React.ReactElement, { size: 20 })}
                    </div>
                    <h3 className="text-xl font-bold text-white mb-1">{plan.name}</h3>
                    <p className="text-gray-300 text-xs">{plan.description}</p>
                  </div>

                  {/* Price */}
                  <div className="mb-3">
                    <div className="flex items-baseline gap-1">
                      <span className="text-2xl sm:text-3xl font-bold text-white">${plan.price}</span>
                      <span className="text-gray-400 text-xs">/{plan.period}</span>
                    </div>
                  </div>

                  {/* Features - Scrollable */}
                  <div className="flex-1 overflow-y-auto space-y-2 mb-3 pr-2" style={{ maxHeight: "280px" }}>
                    {plan.features.map((feature, index) => (
                      <div key={index} className="flex items-start gap-2">
                        <Check size={14} className="text-green-400 flex-shrink-0 mt-0.5" />
                        <span className="text-gray-200 text-xs">{feature}</span>
                      </div>
                    ))}

                    {/* Limitations */}
                    {plan.limitations && plan.limitations.length > 0 && (
                      <div className="space-y-1.5 pt-2 border-t border-white/20 mt-2">
                        {plan.limitations.map((limitation, index) => (
                          <div key={index} className="flex items-start gap-2">
                            <X size={14} className="text-gray-500 flex-shrink-0 mt-0.5" />
                            <span className="text-gray-400 text-xs">{limitation}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>

                  {/* Selection indicator */}
                  {selectedPlan === plan.id && (
                    <div className="absolute top-4 right-4">
                      <div className="w-8 h-8 rounded-full bg-white flex items-center justify-center">
                        <Check size={20} className="text-purple-600" />
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* Action Buttons */}
          <div className="relative z-20 flex items-center justify-center gap-3 sm:gap-4 mt-4 mb-3 flex-shrink-0">
            <button
              onClick={onSkip}
              className="px-5 py-2 bg-white/10 hover:bg-white/20 backdrop-blur-sm text-white rounded-xl transition-all text-sm"
            >
              Skip for now
            </button>
            <button
              onClick={handleContinue}
              className="px-6 py-2 bg-gradient-to-r from-purple-600 to-blue-600 hover:from-purple-700 hover:to-blue-700 text-white rounded-xl font-semibold transition-all flex items-center gap-2 shadow-lg shadow-purple-500/50 text-sm"
            >
              Continue with {plans.find((p) => p.id === selectedPlan)?.name}
              <ArrowRight size={16} />
            </button>
          </div>

          {/* Footer */}
          <div className="text-center mb-2 sm:mb-4 flex-shrink-0">
            <p className="text-gray-400 text-xs">
              All plans include a 14-day free trial • Cancel anytime • No credit card required
            </p>
          </div>
        </div>
      </div>
    );
};

export default SubscriptionPlanSelection;
