import { useEffect, useRef, useCallback } from 'react';
import { generateUrlPath, parseUrlPath } from '../config/routes';

export interface RouteState {
    view: 'deployment' | 'login' | 'signup' | 'workspace' | 'projectDashboard' | 'projectLibrary' | 'dashboard' | 'invitation' | 'subscription' | 'billing';
    projectId?: string | null;
    projectName?: string;
    fileId?: string | null;
    fileName?: string;
    isLoginView?: boolean;
    inviteToken?: string | null;
    resetToken?: string | null;
    showAuthForInvitation?: boolean;
    deploymentType?: string | null;
    showSubscriptionPlan?: boolean;
}

const ROUTE_HISTORY_KEY = 'ontocode_route_history';
const MAX_HISTORY_ENTRIES = 50;

/**
 * Router hook for managing application routes and browser history
 * Enables browser back/forward buttons and persists routing state across refreshes
 */
export const useRouter = (
    currentRoute: RouteState,
    onRouteChange: (route: RouteState, fromBrowserNav?: boolean) => void
) => {
    const isInitialMount = useRef(true);
    const isPopStateNavigation = useRef(false);
    const historyStackRef = useRef<RouteState[]>([]);

    // Helper to save route history to sessionStorage
    const saveRouteHistory = useCallback((stack: RouteState[]) => {
        try {
            sessionStorage.setItem(ROUTE_HISTORY_KEY, JSON.stringify(stack));
        } catch (e) {
            console.warn('[Router] Failed to save route history:', e);
        }
    }, []);

    // Helper to load route history from sessionStorage
    const loadRouteHistory = useCallback((): RouteState[] => {
        try {
            const stored = sessionStorage.getItem(ROUTE_HISTORY_KEY);
            if (stored) {
                return JSON.parse(stored);
            }
        } catch (e) {
            console.warn('[Router] Failed to load route history:', e);
        }
        return [];
    }, []);

    // Helper to check if route has changed. `view` covers /billing
    // because billing has its own view value, so no extra field is needed.
    const hasRouteChanged = useCallback((prev: RouteState, next: RouteState): boolean => {
        return (
            prev.view !== next.view ||
            prev.projectId !== next.projectId ||
            prev.projectName !== next.projectName ||
            prev.fileId !== next.fileId ||
            prev.fileName !== next.fileName ||
            prev.isLoginView !== next.isLoginView ||
            prev.inviteToken !== next.inviteToken ||
            prev.showAuthForInvitation !== next.showAuthForInvitation ||
            prev.deploymentType !== next.deploymentType ||
            prev.showSubscriptionPlan !== next.showSubscriptionPlan
        );
    }, []);

    // Initialize router on mount - restore from sessionStorage after refresh
    useEffect(() => {
        if (isInitialMount.current) {
            isInitialMount.current = false;

            // First, check if there's a URL route to parse
            const parsedRoute = parseUrlPath();
            console.log('[Router] Parsed URL route:', parsedRoute);

            // Load persisted route history from sessionStorage
            const persistedHistory = loadRouteHistory();
            console.log('[Router] Loaded persisted route history:', persistedHistory.length, 'entries');

            if (persistedHistory.length > 0) {
                // Rebuild the full browser history stack so Back/Forward works after refresh.
                const restoredHistory = [...persistedHistory];

                // If URL route exists and differs from last persisted route, prefer URL as current entry.
                if (parsedRoute) {
                    const mergedParsedRoute = { ...currentRoute, ...parsedRoute } as RouteState;
                    const lastPersistedRoute = restoredHistory[restoredHistory.length - 1];
                    if (hasRouteChanged(lastPersistedRoute, mergedParsedRoute)) {
                        restoredHistory.push(mergedParsedRoute);
                    }
                }

                const firstRoute = restoredHistory[0];
                window.history.replaceState(firstRoute, '', generateUrlPath(firstRoute));
                for (let i = 1; i < restoredHistory.length; i++) {
                    const route = restoredHistory[i];
                    window.history.pushState(route, '', generateUrlPath(route));
                }

                historyStackRef.current = restoredHistory;
                saveRouteHistory(restoredHistory);

                // Check if current route matches the last restored route
                const lastPersistedRoute = restoredHistory[restoredHistory.length - 1];
                if (hasRouteChanged(currentRoute, lastPersistedRoute)) {
                    console.log('[Router] Current route differs from last persisted, restoring...');
                    onRouteChange(lastPersistedRoute, false); // From initialization, not browser nav
                } else {
                    // Replace the current route with proper URL
                    const url = generateUrlPath(currentRoute);
                    window.history.replaceState(currentRoute, '', url);
                }

                console.log('[Router] Restored', persistedHistory.length, 'history entries');
            } else if (parsedRoute) {
                // If there's a URL route but no persisted history, initialize from URL
                console.log('[Router] Initializing from URL route');
                const mergedRoute = { ...currentRoute, ...parsedRoute };
                const url = generateUrlPath(mergedRoute as RouteState);
                window.history.replaceState(mergedRoute, '', url);
                historyStackRef.current = [mergedRoute];
                saveRouteHistory([mergedRoute]);

                // Navigate to the parsed route if it differs from current route
                if (hasRouteChanged(currentRoute, mergedRoute as RouteState)) {
                    onRouteChange(mergedRoute as RouteState, false); // From URL, not browser nav
                }
            } else {
                // No persisted history and no URL route - initialize with current route
                console.log('[Router] No persisted history, initializing with current route');
                const url = generateUrlPath(currentRoute);
                window.history.replaceState(currentRoute, '', url);
                historyStackRef.current = [currentRoute];
                saveRouteHistory([currentRoute]);
            }
        }
    }, []); // Only run on mount

    // Listen for browser back/forward button clicks
    useEffect(() => {
        const handlePopState = (event: PopStateEvent) => {
            console.log('[Router] Browser back/forward detected');

            // Keep users pinned on workspace route when they are currently in workspace flow.
            if (currentRoute.view === 'workspace') {
                console.log('[Router] Workspace back navigation blocked');
                const url = generateUrlPath(currentRoute);
                window.history.pushState(currentRoute, '', url);
                return;
            }

            const route = event.state as RouteState | null;

            // Never navigate back to workspace via browser history — it would re-trigger
            // workspace selection mid-session. Pin the user on the current route instead.
            if (route?.view === 'workspace') {
                console.log('[Router] Blocked back navigation to workspace history entry');
                window.history.replaceState(currentRoute, '', generateUrlPath(currentRoute));
                return;
            }

            if (route) {
                isPopStateNavigation.current = true;
                console.log('[Router] Restoring route:', route);
                onRouteChange(route, true); // From browser navigation

                // Reset flag after state update completes
                setTimeout(() => {
                    isPopStateNavigation.current = false;
                }, 100);
            } else {
                // No state in history - try to parse from URL
                const parsedRoute = parseUrlPath();
                if (parsedRoute) {
                    isPopStateNavigation.current = true;
                    console.log('[Router] Restoring from URL:', parsedRoute);
                    onRouteChange(parsedRoute as RouteState, true); // From browser navigation
                    setTimeout(() => {
                        isPopStateNavigation.current = false;
                    }, 100);
                }
            }
        };

        window.addEventListener('popstate', handlePopState);
        return () => window.removeEventListener('popstate', handlePopState);
    }, [onRouteChange, currentRoute]);

    // Update browser history when route changes
    useEffect(() => {
        // Skip initial mount and popstate-triggered updates
        if (isInitialMount.current || isPopStateNavigation.current) {
            return;
        }

        // Check if route has meaningfully changed
        const previousRoute = window.history.state as RouteState | null;
        if (previousRoute && !hasRouteChanged(previousRoute, currentRoute)) {
            return;
        }

        console.log('[Router] Pushing new route to history:', currentRoute.view);

        // Generate clean URL path for the current route
        const url = generateUrlPath(currentRoute);

        if (currentRoute.view === 'workspace') {
            // Do not add workspace view to back stack; keep a single pinned workspace state.
            window.history.replaceState(currentRoute, '', url);
            historyStackRef.current = [currentRoute];
            saveRouteHistory([currentRoute]);
        } else {
            // Push new route to browser history
            window.history.pushState(currentRoute, '', url);

            // Update history stack and persist it
            const newStack = [...historyStackRef.current, currentRoute];

            // Limit stack size to prevent memory issues
            if (newStack.length > MAX_HISTORY_ENTRIES) {
                newStack.shift(); // Remove oldest entry
            }

            historyStackRef.current = newStack;
            saveRouteHistory(newStack);
        }

        console.log('[Router] Route history size:', historyStackRef.current.length);
    }, [currentRoute, hasRouteChanged, saveRouteHistory]);

    // Navigate back programmatically
    const goBack = useCallback(() => {
        console.log('[Router] Programmatic back navigation');
        window.history.back();
    }, []);

    // Navigate forward programmatically
    const goForward = useCallback(() => {
        console.log('[Router] Programmatic forward navigation');
        window.history.forward();
    }, []);

    // Navigate to a specific route
    const navigateTo = useCallback((route: Partial<RouteState>) => {
        console.log('[Router] Navigating to:', route);
        const newRoute = { ...currentRoute, ...route } as RouteState;
        onRouteChange(newRoute, false); // Not from browser navigation
    }, [currentRoute, onRouteChange]);

    // Clear route history (e.g., on logout)
    const clearHistory = useCallback(() => {
        console.log('[Router] Clearing route history');
        try {
            sessionStorage.removeItem(ROUTE_HISTORY_KEY);
            historyStackRef.current = [];
        } catch (e) {
            console.warn('[Router] Failed to clear history:', e);
        }
    }, []);

    return {
        goBack,
        goForward,
        navigateTo,
        clearHistory,
        currentRoute
    };
};
