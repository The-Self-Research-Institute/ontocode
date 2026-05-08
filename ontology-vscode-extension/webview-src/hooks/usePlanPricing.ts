import { useState, useEffect } from 'react';
import { getGatewayUrl } from '../config/deploymentConfig';

export interface PlanPricing {
    id: string;
    monthlyPrice: number;
    annualPrice: number;
    features: string[];
    limitations: string[];
}

type PricingMap = Record<string, PlanPricing>;

const FALLBACK: PricingMap = {
    FREE: {
        id: 'FREE', monthlyPrice: 0, annualPrice: 0,
        features: [
            'Up to 3 workspaces', 'Up to 3 workspace members', '10 GB storage',
            'OWL/RDF ontology editing', 'Class hierarchy & properties',
            'SPARQL query execution', 'SWRL rule editor', 'DL Query & reasoning',
            'Import OWL/TTL/RDF files', 'Custom plugin support', 'Invite & manage members',
        ],
        limitations: ['No team collaboration', 'No shared editing'],
    },
    PRO: {
        id: 'PRO', monthlyPrice: 59, annualPrice: 59,
        features: [
            'Up to 10 workspaces', 'Up to 10 workspace members', '100 GB storage',
            'Everything in Free', 'Team collaboration enabled', 'Export to multiple formats',
            'Priority email support',
        ],
        limitations: [],
    },
    ENTERPRISE: {
        id: 'ENTERPRISE', monthlyPrice: 299, annualPrice: 299,
        features: [
            'Unlimited workspace members', 'Unlimited workspaces', 'Unlimited storage',
            'Everything in Professional', 'Early access to new features', 'Priority channel support',
        ],
        limitations: [],
    },
};

// Module-level cache — only one HTTP request regardless of how many components mount.
let _cache: PricingMap | null = null;
let _trialDaysCache: number = 14;
let _inflight: Promise<PricingMap> | null = null;

function loadPricing(): Promise<PricingMap> {
    if (_cache) return Promise.resolve(_cache);
    if (_inflight) return _inflight;
    _inflight = fetch(`${getGatewayUrl()}/api/billing/plans`)
        .then(r => r.json())
        .then((data: { plans?: PlanPricing[], trialPeriodDays?: number }) => {
            if (Array.isArray(data.plans)) {
                const map: PricingMap = {};
                data.plans.forEach(p => { map[p.id.toUpperCase()] = p; });
                _cache = map;
                _trialDaysCache = data.trialPeriodDays ?? 14;
                return map;
            }
            return FALLBACK;
        })
        .catch(() => FALLBACK);
    return _inflight;
}

export function usePlanPricing() {
    const [pricing, setPricing] = useState<PricingMap>(_cache ?? FALLBACK);

    useEffect(() => {
        if (_cache) return;
        loadPricing().then(map => setPricing(map));
    }, []);

    const getPricing = (planId: string): PlanPricing =>
        pricing[planId.toUpperCase()] ?? FALLBACK[planId.toUpperCase()] ?? FALLBACK.FREE;

    const getDisplayPrice = (planId: string, interval: string): string => {
        const p = getPricing(planId);
        const norm = interval?.toLowerCase();
        if (norm === 'monthly') return `$${p.monthlyPrice}/month`;
        if (norm === 'annual' || norm === 'yearly') return `$${p.annualPrice * 12}/year`;
        return '';
    };

    const getShortPrice = (planId: string, interval: string): string => {
        const p = getPricing(planId);
        const norm = interval?.toLowerCase();
        if (norm === 'monthly') return `$${p.monthlyPrice}/mo`;
        if (norm === 'annual' || norm === 'yearly') return `$${p.annualPrice * 12}/yr`;
        return '';
    };

    return { pricing, getPricing, getDisplayPrice, getShortPrice, trialPeriodDays: _trialDaysCache };
}
