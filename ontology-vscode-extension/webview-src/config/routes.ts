import { RouteState } from '../hooks/useRouter';

/**
 * Application route configuration
 * Defines all navigable routes in the application
 */

export interface RouteConfig {
    path: string;
    view: RouteState['view'];
    params?: string[];
}

/**
 * Route definitions for the application
 * Maps view types to URL patterns
 */
export const routes: Record<string, RouteConfig> = {
    deployment: {
        path: '/deployment',
        view: 'deployment',
    },
    login: {
        path: '/login',
        view: 'login',
    },
    signup: {
        path: '/signup',
        view: 'signup',
    },
    workspace: {
        path: '/workspace',
        view: 'workspace',
    },
    subscription: {
        path: '/subscription',
        view: 'subscription',
    },
    invitation: {
        path: '/invitation',
        view: 'invitation',
    },
    projectDashboard: {
        path: '/projects',
        view: 'projectDashboard',
    },
    projectLibrary: {
        path: '/projects/:projectName',
        view: 'projectLibrary',
        params: ['projectName'],
    },
    projectEditor: {
        path: '/projects/:projectName/editor',
        view: 'dashboard',
        params: ['projectName'],
    },
    fileEditor: {
        path: '/projects/:projectName/files/:fileName',
        view: 'dashboard',
        params: ['projectName', 'fileName'],
    },
};

/**
 * Generate URL path from route state
 */
export const generateUrlPath = (state: RouteState): string => {
    // Get base URL without hash
    const baseUrl = window.location.origin + window.location.pathname.split('#')[0];

    switch (state.view) {
        case 'deployment':
            return baseUrl + '#' + routes.deployment.path;

        case 'login':
            return baseUrl + '#' + routes.login.path;

        case 'signup':
            return baseUrl + '#' + routes.signup.path;

        case 'workspace':
            return baseUrl + '#' + routes.workspace.path;

        case 'subscription':
            return baseUrl + '#' + routes.subscription.path;

        case 'invitation':
            const invitePath = routes.invitation.path;
            return baseUrl + `#${invitePath}${state.inviteToken ? `?token=${encodeURIComponent(state.inviteToken)}` : ''}`;

        case 'projectDashboard':
            return baseUrl + '#' + routes.projectDashboard.path;

        case 'projectLibrary':
            if (state.projectName) {
                return baseUrl + `#${routes.projectLibrary.path.replace(':projectName', encodeURIComponent(state.projectName))}`;
            }
            return baseUrl + '#' + routes.projectDashboard.path;

        case 'dashboard':
            if (state.projectName && state.fileName) {
                return baseUrl + `#${routes.fileEditor.path
                    .replace(':projectName', encodeURIComponent(state.projectName))
                    .replace(':fileName', encodeURIComponent(state.fileName))}`;
            } else if (state.projectName) {
                return baseUrl + `#${routes.projectEditor.path.replace(':projectName', encodeURIComponent(state.projectName))}`;
            }
            // Fallback to projects dashboard if no project context
            return baseUrl + '#' + routes.projectDashboard.path;

        default:
            return baseUrl + '#/';
    }
};

/**
 * Parse URL path to route state
 */
export const parseUrlPath = (): Partial<RouteState> | null => {
    const hash = window.location.hash.substring(1); // Remove leading #
    if (!hash) return null;

    // Split path and query string
    const [path, queryString] = hash.split('?');
    const params = new URLSearchParams(queryString || '');
    const pathParts = path.split('/').filter(p => p);

    if (pathParts.length === 0) return null;

    const route = pathParts[0];

    // Match against route patterns
    switch (route) {
        case 'deployment':
            return { view: 'deployment' };

        case 'login':
            return { view: 'login', isLoginView: true };

        case 'signup':
            return { view: 'signup', isLoginView: false };

        case 'workspace':
            return { view: 'workspace' };

        case 'subscription':
            return { view: 'subscription', showSubscriptionPlan: true };

        case 'invitation':
        case 'invite':
            return {
                view: 'invitation',
                inviteToken: params.get('token') || params.get('invite') || undefined
            };

        case 'projects':
            // /projects
            if (pathParts.length === 1) {
                return { view: 'projectDashboard' };
            }
            // /projects/:projectName
            else if (pathParts.length === 2) {
                return {
                    view: 'projectLibrary',
                    projectName: decodeURIComponent(pathParts[1])
                };
            }
            // /projects/:projectName/editor
            else if (pathParts.length === 3 && pathParts[2] === 'editor') {
                return {
                    view: 'dashboard',
                    projectName: decodeURIComponent(pathParts[1])
                };
            }
            // /projects/:projectName/files/:fileName
            else if (pathParts.length === 4 && pathParts[2] === 'files') {
                return {
                    view: 'dashboard',
                    projectName: decodeURIComponent(pathParts[1]),
                    fileName: decodeURIComponent(pathParts[3])
                };
            }
            return { view: 'projectDashboard' };

        default:
            return null;
    }
};

/**
 * Get route path for a specific view
 */
export const getRoutePath = (view: RouteState['view'], params?: Record<string, string>): string => {
    const routeKey = Object.keys(routes).find(key => routes[key].view === view);
    if (!routeKey) return '/';

    let path = routes[routeKey].path;

    // Replace path parameters if provided
    if (params) {
        Object.entries(params).forEach(([key, value]) => {
            path = path.replace(`:${key}`, encodeURIComponent(value));
        });
    }

    return path;
};

/**
 * Check if a route matches a given path pattern
 */
export const matchRoute = (path: string, pattern: string): { matches: boolean; params: Record<string, string> } => {
    const pathParts = path.split('/').filter(p => p);
    const patternParts = pattern.split('/').filter(p => p);

    if (pathParts.length !== patternParts.length) {
        return { matches: false, params: {} };
    }

    const params: Record<string, string> = {};

    for (let i = 0; i < patternParts.length; i++) {
        const patternPart = patternParts[i];
        const pathPart = pathParts[i];

        if (patternPart.startsWith(':')) {
            // This is a parameter
            const paramName = patternPart.substring(1);
            params[paramName] = decodeURIComponent(pathPart);
        } else if (patternPart !== pathPart) {
            // Static part doesn't match
            return { matches: false, params: {} };
        }
    }

    return { matches: true, params };
};
