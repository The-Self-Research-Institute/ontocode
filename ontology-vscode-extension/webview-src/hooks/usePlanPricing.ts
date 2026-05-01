import { useState, useEffect } from 'react';
import { getGatewayUrl } from '../config/deploymentConfig';

export interface PlanPricing {
    id: string;
    monthlyPrice: number;
    annualPrice: number;
}

type PricingMap = Record<string, PlanPricing>;

const FALLBACK: PricingMap = {
    FREE:       { id: 'FREE',       monthlyPrice: 0,  annualPrice: 0  },
    PRO:        { id: 'PRO',        monthlyPrice: 29, annualPrice: 24 },
    ENTERPRISE: { id: 'ENTERPRISE', monthlyPrice: 99, annualPrice: 79 },
};

// Module-level cache — only one HTTP request regardless of how many components mount.
let _cache: PricingMap | null = null;
let _inflight: Promise<PricingMap> | null = null;

function loadPricing(): Promise<PricingMap> {
    if (_cache) return Promise.resolve(_cache);
    if (_inflight) return _inflight;
    _inflight = fetch(`${getGatewayUrl()}/api/billing/plans`)
        .then(r => r.json())
        .then((data: { plans?: PlanPricing[] }) => {
            if (Array.isArray(data.plans)) {
                const map: PricingMap = {};
                data.plans.forEach(p => { map[p.id.toUpperCase()] = p; });
                _cache = map;
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

    return { pricing, getPricing, getDisplayPrice, getShortPrice };
}
