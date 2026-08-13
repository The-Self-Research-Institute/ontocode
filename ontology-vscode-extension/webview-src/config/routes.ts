import { RouteState } from '../hooks/useRouter';

export interface RouteConfig {
    path: string;
    view: RouteState['view'];
    params?: string[];
}

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
    editor: {
        path: '/editor',
        view: 'dashboard',
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
    billing: {
        path: '/billing',
        view: 'billing',
    },
};

const shouldUseHashRouting = (): boolean => {
    const protocol = window.location.protocol;

    return protocol === 'file:' ||
        protocol.startsWith('vscode-webview') ||
        protocol.startsWith('vscode-webview-resource');
};

export const generateUrlPath = (state: RouteState): string => {
    const useHashRouting = shouldUseHashRouting();

    const baseUrl = (useHashRouting && window.location.protocol === 'file:')
        ? window.location.href.split('#')[0].split('?')[0]
        : window.location.origin;

    const withRoute = (path: string): string => {
        return useHashRouting ? `${baseUrl}#${path}` : `${baseUrl}${path}`;
    };

    switch (state.view) {
        case 'deployment':
            return withRoute(routes.deployment.path);

        case 'login':
            return withRoute(routes.login.path);

        case 'signup':
            return withRoute(routes.signup.path);

        case 'workspace':
            return withRoute(routes.workspace.path);

        case 'subscription':
            return withRoute(routes.subscription.path);

        case 'invitation':
            const invitePath = routes.invitation.path;
            return withRoute(`${invitePath}${state.inviteToken ? `?token=${encodeURIComponent(state.inviteToken)}` : ''}`);

        case 'projectDashboard':
            return withRoute(routes.projectDashboard.path);

        case 'projectLibrary':
            if (state.projectName) {
                return withRoute(routes.projectLibrary.path.replace(':projectName', encodeURIComponent(state.projectName)));
            }
            return withRoute(routes.projectDashboard.path);

        case 'dashboard':
            if (state.projectName && state.fileName) {
                return withRoute(routes.fileEditor.path
                    .replace(':projectName', encodeURIComponent(state.projectName))
                    .replace(':fileName', encodeURIComponent(state.fileName)));
            } else if (state.projectName) {
                return withRoute(routes.projectEditor.path.replace(':projectName', encodeURIComponent(state.projectName)));
            }

            return withRoute(routes.editor.path);

        case 'billing':
            return withRoute(routes.billing.path);

        default:
            return useHashRouting ? `${baseUrl}#/` : `${baseUrl}/`;
    }
};

export const parseUrlPath = (): Partial<RouteState> | null => {
    const hash = window.location.hash.substring(1);
    const useHashRouting = shouldUseHashRouting();

    let sourcePath = '';
    let sourceQuery = '';

    if (hash && hash.startsWith('/')) {
        const [path, queryString] = hash.split('?');
        sourcePath = path;
        sourceQuery = queryString || '';
    } else if (useHashRouting) {
        if (!hash) return null;
        const [path, queryString] = hash.split('?');
        sourcePath = path;
        sourceQuery = queryString || '';
    } else {
        sourcePath = window.location.pathname;
        sourceQuery = window.location.search.startsWith('?')
            ? window.location.search.substring(1)
            : window.location.search;
        if (!sourcePath || sourcePath === '/') return null;
    }

    const params = new URLSearchParams(sourceQuery || '');
    const pathParts = sourcePath.split('/').filter(p => p);

    if (pathParts.length === 0) return null;

    const route = pathParts[0];

    switch (route) {
        case 'deployment':
            return { view: 'deployment' };

        case 'login':
            return { view: 'login', isLoginView: true };

        case 'signup':
            return { view: 'signup', isLoginView: false };

        case 'editor':
            return { view: 'dashboard' };

        case 'workspace':
            return { view: 'workspace' };

        case 'subscription':
            return { view: 'subscription', showSubscriptionPlan: true };

        case 'desktop-pricing':
            return { view: 'subscription', showSubscriptionPlan: true };

        case 'billing':
            return { view: 'billing' };

        case 'invitation':
        case 'invite':
            return {
                view: 'invitation',
                inviteToken: params.get('token') || params.get('invite') || undefined
            };

        case 'reset-password':
            return {
                view: 'login',
                isLoginView: true,
                resetToken: params.get('token') || undefined
            };

        case 'projects':

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

export const getRoutePath = (view: RouteState['view'], params?: Record<string, string>): string => {
    const routeKey = Object.keys(routes).find(key => routes[key].view === view);
    if (!routeKey) return '/';

    let path = routes[routeKey].path;

    if (params) {
        Object.entries(params).forEach(([key, value]) => {
            path = path.replace(`:${key}`, encodeURIComponent(value));
        });
    }

    return path;
};

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

            const paramName = patternPart.substring(1);
            params[paramName] = decodeURIComponent(pathPart);
        } else if (patternPart !== pathPart) {

            return { matches: false, params: {} };
        }
    }

    return { matches: true, params };
};
