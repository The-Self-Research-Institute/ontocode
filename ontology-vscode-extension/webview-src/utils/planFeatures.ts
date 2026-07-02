export function isInheritedPlanFeature(feature: string): boolean {
    return feature.trim().toLowerCase().startsWith('everything in ');
}

export function orderPlanFeatures(features: string[]): string[] {
    const inherited = features.filter(isInheritedPlanFeature);
    const standard = features.filter(feature => !isInheritedPlanFeature(feature));
    return [...inherited, ...standard];
}
