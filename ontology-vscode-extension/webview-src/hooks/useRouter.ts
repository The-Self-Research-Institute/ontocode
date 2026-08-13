import { useEffect, useRef, useCallback } from 'react';
import { generateUrlPath, parseUrlPath } from '../config/routes';

export interface RouteState {
    view: 'deployment' | 'login' | 'signup' | 'workspace' | 'projectDashboard' | 'projectLibrary' | 'dashboard' | 'invitation' | 'subscription' | 'billing' | 'desktopDownload';
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

export const useRouter = (
    currentRoute: RouteState,
    onRouteChange: (route: RouteState, fromBrowserNav?: boolean) => void
) => {
    const isInitialMount = useRef(true);
    const isPopStateNavigation = useRef(false);
    const historyStackRef = useRef<RouteState[]>([]);

    const saveRouteHistory = useCallback((stack: RouteState[]) => {
        try {
            sessionStorage.setItem(ROUTE_HISTORY_KEY, JSON.stringify(stack));
        } catch (e) {
            console.warn('[Router] Failed to save route history:', e);
        }
    }, []);

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

    useEffect(() => {
        if (isInitialMount.current) {
            isInitialMount.current = false;

            const parsedRoute = parseUrlPath();
            console.log('[Router] Parsed URL route:', parsedRoute);

            const persistedHistory = loadRouteHistory();
            console.log('[Router] Loaded persisted route history:', persistedHistory.length, 'entries');

            if (persistedHistory.length > 0) {

                const restoredHistory = [...persistedHistory];

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

                const lastPersistedRoute = restoredHistory[restoredHistory.length - 1];
                if (hasRouteChanged(currentRoute, lastPersistedRoute)) {
                    console.log('[Router] Current route differs from last persisted, restoring...');
                    onRouteChange(lastPersistedRoute, false); // From initialization, not browser nav
                } else {

                    const url = generateUrlPath(currentRoute);
                    window.history.replaceState(currentRoute, '', url);
                }

                console.log('[Router] Restored', persistedHistory.length, 'history entries');
            } else if (parsedRoute) {

                console.log('[Router] Initializing from URL route');
                const mergedRoute = { ...currentRoute, ...parsedRoute };
                const url = generateUrlPath(mergedRoute as RouteState);
                window.history.replaceState(mergedRoute, '', url);
                historyStackRef.current = [mergedRoute];
                saveRouteHistory([mergedRoute]);

                if (hasRouteChanged(currentRoute, mergedRoute as RouteState)) {
                    onRouteChange(mergedRoute as RouteState, false); // From URL, not browser nav
                }
            } else {

                console.log('[Router] No persisted history, initializing with current route');
                const url = generateUrlPath(currentRoute);
                window.history.replaceState(currentRoute, '', url);
                historyStackRef.current = [currentRoute];
                saveRouteHistory([currentRoute]);
            }
        }
    }, []); // Only run on mount

    useEffect(() => {
        const handlePopState = (event: PopStateEvent) => {
            console.log('[Router] Browser back/forward detected');

            if (currentRoute.view === 'workspace') {
                console.log('[Router] Workspace back navigation blocked');
                const url = generateUrlPath(currentRoute);
                window.history.pushState(currentRoute, '', url);
                return;
            }

            const route = event.state as RouteState | null;

            if (route?.view === 'workspace') {
                console.log('[Router] Back navigation to workspace — navigating to workspace selection');
                onRouteChange({ view: 'workspace' }, true);
                return;
            }

            if (route) {
                isPopStateNavigation.current = true;
                console.log('[Router] Restoring route:', route);
                onRouteChange(route, true); // From browser navigation

                setTimeout(() => {
                    isPopStateNavigation.current = false;
                }, 100);
            } else {

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

    useEffect(() => {

        if (isInitialMount.current || isPopStateNavigation.current) {
            return;
        }

        const previousRoute = window.history.state as RouteState | null;
        if (previousRoute && !hasRouteChanged(previousRoute, currentRoute)) {
            return;
        }

        console.log('[Router] Pushing new route to history:', currentRoute.view);

        const url = generateUrlPath(currentRoute);

        if (currentRoute.view === 'workspace') {

            window.history.replaceState(currentRoute, '', url);
            historyStackRef.current = [currentRoute];
            saveRouteHistory([currentRoute]);
        } else {

            window.history.pushState(currentRoute, '', url);

            const newStack = [...historyStackRef.current, currentRoute];

            if (newStack.length > MAX_HISTORY_ENTRIES) {
                newStack.shift(); // Remove oldest entry
            }

            historyStackRef.current = newStack;
            saveRouteHistory(newStack);
        }

        console.log('[Router] Route history size:', historyStackRef.current.length);
    }, [currentRoute, hasRouteChanged, saveRouteHistory]);

    const goBack = useCallback(() => {
        console.log('[Router] Programmatic back navigation, stack size:', historyStackRef.current.length);
        if (historyStackRef.current.length > 1) {
            window.history.back();
        } else {

            onRouteChange({ view: 'workspace' }, false);
        }
    }, [onRouteChange]);

    const goForward = useCallback(() => {
        console.log('[Router] Programmatic forward navigation');
        window.history.forward();
    }, []);

    const navigateTo = useCallback((route: Partial<RouteState> & { replace?: boolean }) => {
        const { replace, ...routeUpdate } = route;
        console.log('[Router] Navigating to:', routeUpdate, replace ? '(replace)' : '');
        const newRoute = { ...currentRoute, ...routeUpdate } as RouteState;
        onRouteChange(newRoute, false); // Not from browser navigation

        if (replace) {
            const url = generateUrlPath(newRoute);
            window.history.replaceState(newRoute, '', url);
            const stack = historyStackRef.current.length > 0
                ? [...historyStackRef.current.slice(0, -1), newRoute]
                : [newRoute];
            historyStackRef.current = stack;
            saveRouteHistory(stack);
        }
    }, [currentRoute, onRouteChange, saveRouteHistory]);

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
