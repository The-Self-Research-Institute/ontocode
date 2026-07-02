import { useMemo } from 'react';
import { useAuth } from '../custom-hook/useAuth';
import { isDesktop } from '../utils/desktop';

export interface SubscriptionLimits {
    maxTeamMembers: number;
    storageGB: number;
    hasBasicCollaboration: boolean;
    hasAdvancedCollaboration: boolean;
    hasVersionControl: boolean;
    hasCustomPlugins: boolean;
    hasAdvancedReasoning: boolean;
    hasAPIAccess: boolean;
    hasPrioritySupport: boolean;
    hasDedicatedSupport: boolean;
    hasAdvancedSecurity: boolean;
    hasCustomIntegrations: boolean;
    hasSLAGuarantee: boolean;
    hasOnPremise: boolean;
    hasWhiteLabel: boolean;
    /**
     * Master export gate — controls Dashboard "Export" menu, Code View
     * "Copy All" and "Download" actions, and any other path that lets
     * the user pull the ontology out of the platform.
     */
    hasExport: boolean;
    /**
     * Multi-format export (Turtle / RDF/XML / N-Triples / OWL/XML / Manchester / Functional).
     * Implies {@link hasExport}. Free remains gated on this and on `hasExport`.
     */
    hasMultipleExportFormats: boolean;
    maxWorkspaces: number;
}

export const PLAN_LIMITS: Record<string, SubscriptionLimits> = {
    free: {
        maxTeamMembers: 3,       // 3 total including owner; invited members are view-only
        storageGB: 10,
        hasBasicCollaboration: true,  // FREE can invite up to 3 members (view-only)
        hasAdvancedCollaboration: false,
        hasVersionControl: false,
        hasCustomPlugins: true,   // plan description includes custom plugin support
        hasAdvancedReasoning: false,
        hasAPIAccess: false,
        hasPrioritySupport: false,
        hasDedicatedSupport: false,
        hasAdvancedSecurity: false,
        hasCustomIntegrations: false,
        hasSLAGuarantee: false,
        hasOnPremise: false,
        hasWhiteLabel: false,
        hasExport: false,
        hasMultipleExportFormats: false,
        maxWorkspaces: 3
    },
    pro: {
        maxTeamMembers: 10,
        storageGB: 100,
        hasBasicCollaboration: true,
        // Bug #49: Pro now includes real-time collaboration. Enterprise
        // remains differentiated by SLA, custom integrations, on-premise,
        // dedicated support, and advanced security — not by collaboration.
        hasAdvancedCollaboration: true,
        hasVersionControl: true,
        hasCustomPlugins: true,
        hasAdvancedReasoning: true,
        hasAPIAccess: true,
        hasPrioritySupport: true,
        hasDedicatedSupport: false,
        hasAdvancedSecurity: false,
        hasCustomIntegrations: false,
        hasSLAGuarantee: false,
        hasOnPremise: false,
        hasWhiteLabel: false,
        hasExport: true,
        hasMultipleExportFormats: true,
        maxWorkspaces: 10
    },
    enterprise: {
        maxTeamMembers: Infinity,
        storageGB: Infinity,
        hasBasicCollaboration: true,
        hasAdvancedCollaboration: true,
        hasVersionControl: true,
        hasCustomPlugins: true,
        hasAdvancedReasoning: true,
        hasAPIAccess: true,
        hasPrioritySupport: true,
        hasDedicatedSupport: true,
        hasAdvancedSecurity: true,
        hasCustomIntegrations: true,
        hasSLAGuarantee: true,
        hasOnPremise: true,
        hasWhiteLabel: true,
        hasExport: true,
        hasMultipleExportFormats: true,
        maxWorkspaces: Infinity
    }
};

export const useSubscription = () => {
    const { user } = useAuth();

    // Desktop is a local single-user app — all features are unlocked regardless of cloud plan.
    const plan = isDesktop() ? 'enterprise' : (user?.subscriptionPlan || 'free').toLowerCase();
    const limits = useMemo(() => PLAN_LIMITS[plan] || PLAN_LIMITS.free, [plan]);

    const canAccessFeature = (feature: keyof SubscriptionLimits): boolean => {
        const value = limits[feature];
        // Strict default: unknown / non-boolean keys are treated as DENIED rather
        // than allowed, so a missing entry can't accidentally unlock a paid
        // feature for free users (this is the bug that allowed export on free).
        return typeof value === 'boolean' ? value : false;
    };

    const isWithinLimit = (currentCount: number, limitKey: keyof SubscriptionLimits): boolean => {
        const limit = limits[limitKey];
        return typeof limit === 'number' ? currentCount < limit : true;
    };

    const getUpgradeMessage = (feature: string): string => {
        if (plan === 'free') {
            return `Upgrade to Professional or Enterprise plan to access ${feature}`;
        } else if (plan === 'pro') {
            return `Upgrade to Enterprise plan to access ${feature}`;
        }
        return '';
    };

    return {
        plan,
        limits,
        canAccessFeature,
        isWithinLimit,
        getUpgradeMessage,
        isPro: plan === 'pro',
        isEnterprise: plan === 'enterprise',
        isFree: plan === 'free'
    };
};
