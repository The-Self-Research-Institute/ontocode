import React from "react";
import { Crown, Shield, Sparkles, Zap } from "lucide-react";
import { isDesktop } from "../../utils/desktop";
import type { SubscriptionInfo } from "../../utils/projectDashboardTypes";

interface WorkspacePlanControlsProps {
  subscription: SubscriptionInfo;
  isWorkspaceOwner: boolean;
  onShowPlanDetails: () => void;
  onOpenSubscriptionPlans?: () => void;
}

const WorkspacePlanControls: React.FC<WorkspacePlanControlsProps> = ({
  subscription,
  isWorkspaceOwner,
  onShowPlanDetails,
  onOpenSubscriptionPlans,
}) => (
  <>
    {!isDesktop() && (isWorkspaceOwner ? (
      <button
        onClick={onShowPlanDetails}
        className={`h-9 inline-flex items-center justify-center gap-1.5 px-3 text-xs font-semibold rounded-lg transition-all hover:shadow-lg cursor-pointer ${subscription.isEnterprise
            ? "bg-gradient-to-r from-amber-500 to-orange-500 text-white hover:from-amber-600 hover:to-orange-600"
            : subscription.isPro
              ? "bg-gradient-to-r from-purple-500 to-blue-500 text-white hover:from-purple-600 hover:to-blue-600"
              : "bg-gray-100 text-gray-700 hover:bg-gray-200"
          }`}
        title={`Workspace Plan: ${subscription.plan.toUpperCase()} - Click for current plan details`}
      >
        {subscription.isEnterprise ? (
          <Crown size={14} />
        ) : subscription.isPro ? (
          <Zap size={14} />
        ) : (
          <Sparkles size={14} />
        )}
        {subscription.plan.toUpperCase()}
      </button>
    ) : (
      <span
        className="h-8 inline-flex items-center justify-center gap-1.5 px-3 text-[11px] font-semibold rounded-full border border-slate-600/70 bg-slate-800/70 text-slate-300"
        title={`Workspace Plan: ${subscription.plan.toUpperCase()}`}
      >
        <Shield size={12} className="text-slate-400" />
        Plan: {subscription.plan.toUpperCase()}
      </span>
    ))}
    {!isDesktop() && isWorkspaceOwner && onOpenSubscriptionPlans && !subscription.isEnterprise && (
      <button
        onClick={onOpenSubscriptionPlans}
        className="h-9 inline-flex items-center justify-center gap-1.5 px-3 text-xs font-semibold rounded-lg transition-all hover:shadow-lg bg-gradient-to-r from-emerald-500 to-teal-500 text-white hover:from-emerald-600 hover:to-teal-600"
        title="Upgrade your account plan"
      >
        <Crown size={14} />
        Upgrade Plan
      </button>
    )}
  </>
);

export default WorkspacePlanControls;
