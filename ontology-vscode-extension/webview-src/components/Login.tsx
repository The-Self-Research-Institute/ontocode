import React, { useState, useEffect } from 'react';
import { Mail, Lock, User, Eye, EyeOff, Wrench, Clock } from 'lucide-react';
import { useAuth } from '../contexts/AuthContext';
import { useLocation, useNavigate } from 'react-router-dom';
import { OntoCodeLogo } from './OntoCodeLogo';
import { getAppVersion } from '../utils/appVersion';

const MaintenancePage: React.FC = () => (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-slate-800 via-slate-900 to-gray-900 p-6">
        <div className="max-w-lg w-full text-center">
            <div className="inline-flex items-center justify-center w-20 h-20 bg-yellow-500/20 border border-yellow-500/30 rounded-full mb-6">
                <Wrench className="w-10 h-10 text-yellow-400" />
            </div>
            <h1 className="text-3xl font-bold text-white mb-3">Under Maintenance</h1>
            <p className="text-slate-300 text-lg mb-2">
                OntoCode is currently undergoing scheduled maintenance.
            </p>
            <p className="text-slate-400 mb-8">
                We're working hard to improve your experience. The system will be back online shortly.
            </p>
            <div className="bg-slate-800/60 border border-slate-700 rounded-xl p-6 mb-8 text-left space-y-3">
                <div className="flex items-start gap-3">
                    <Clock className="w-5 h-5 text-yellow-400 mt-0.5 shrink-0" />
                    <div>
                        <p className="text-white font-medium text-sm">Estimated downtime</p>
                        <p className="text-slate-400 text-sm">This maintenance window is temporary. Please check back soon.</p>
                    </div>
                </div>
                <div className="flex items-start gap-3">
                    <Mail className="w-5 h-5 text-blue-400 mt-0.5 shrink-0" />
                    <div>
                        <p className="text-white font-medium text-sm">Need access?</p>
                        <p className="text-slate-400 text-sm">
                            Contact us at{' '}
                            <a href="mailto:support@coretopia.com" className="text-blue-400 hover:text-blue-300 underline">
                                support@coretopia.com
                            </a>
                        </p>
                    </div>
                </div>
            </div>
            <p className="text-slate-500 text-sm">
                We apologise for the inconvenience and appreciate your patience.
            </p>
        </div>
    </div>
);

const Login: React.FC = () => {
    const [isSignup, setIsSignup] = useState(false);
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [username, setUsername] = useState('');
    const [showPassword, setShowPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState('');
    const [maintenanceMode, setMaintenanceMode] = useState(false);
    const [appVersion, setAppVersion] = useState('');

    const { login } = useAuth();
    const location = useLocation();
    const navigate = useNavigate();

    const inviteToken = new URLSearchParams(location.search).get('invite');
    const inviteEmail = new URLSearchParams(location.search).get('email');

    useEffect(() => {
        getAppVersion().then(setAppVersion).catch(() => setAppVersion(''));
    }, []);

    useEffect(() => {
        if (inviteEmail) {
            setEmail(inviteEmail);
            setIsSignup(true); // Default to signup for new invitations
        }
    }, [inviteEmail]);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError('');
        setLoading(true);

        try {
            const endpoint = isSignup ? '/api/auth/signup' : '/api/auth/login';
            const payload = isSignup 
                ? { username, email, password }
                : { username: email, password }; // Login can use email as username

            await login(payload.username, password);

            if (inviteToken) {
                navigate(`/invite?token=${inviteToken}`);
            } else {
                navigate('/');
            }
        } catch (err: any) {
            if (err.response?.status === 403) {
                setMaintenanceMode(true);
            } else {
                setError(err.response?.data?.error || err.message || 'Authentication failed');
            }
        } finally {
            setLoading(false);
        }
    };

    if (maintenanceMode) {
        return <MaintenancePage />;
    }

    return (
        <div className="min-h-screen overflow-y-auto flex items-center justify-center bg-gradient-to-br from-purple-50 via-blue-50 to-indigo-50 p-6">
            <div className="max-w-md w-full">
                {}
                <div className="text-center mb-8">
                    <div className="inline-flex items-center justify-center mb-4">
                        <OntoCodeLogo size={64} rounded className="shadow-lg" />
                    </div>
                    <h1 className="text-3xl font-bold text-gray-900 mb-2">
                        {inviteToken ? 'Join OntoCode' : 'OntoCode Editor'}
                    </h1>
                    <p className="text-gray-600">
                        {inviteToken 
                            ? 'Create your account to accept the invitation' 
                            : isSignup 
                                ? 'Create your account to get started'
                                : 'Sign in to your account'
                        }
                    </p>
                </div>

                {}
                <div className="bg-white rounded-2xl shadow-xl p-8">
                    {error && (
                        <div className="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg">
                            <p className="text-sm text-red-600">{error}</p>
                        </div>
                    )}

                    {inviteEmail && (
                        <div className="mb-4 p-3 bg-blue-50 border border-blue-200 rounded-lg">
                            <p className="text-sm text-blue-700">
                                You've been invited to join a workspace! Create an account or login to continue.
                            </p>
                        </div>
                    )}

                    <form onSubmit={handleSubmit} className="space-y-4">
                        {isSignup && (
                            <div>
                                <label className="block text-sm font-medium text-gray-700 mb-2">
                                    Username
                                </label>
                                <div className="relative">
                                    <User size={18} className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" />
                                    <input
                                        type="text"
                                        value={username}
                                        onChange={(e) => setUsername(e.target.value)}
                                        className="w-full pl-10 pr-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                        placeholder="johndoe"
                                        required
                                    />
                                </div>
                            </div>
                        )}

                        <div>
                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                Email
                            </label>
                            <div className="relative">
                                <Mail size={18} className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" />
                                <input
                                    type="email"
                                    value={email}
                                    onChange={(e) => setEmail(e.target.value)}
                                    className="w-full pl-10 pr-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                    placeholder="you@example.com"
                                    required
                                    disabled={!!inviteEmail}
                                />
                            </div>
                        </div>

                        <div>
                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                Password
                            </label>
                            <div className="relative">
                                <Lock size={18} className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" />
                                <input
                                    type={showPassword ? 'text' : 'password'}
                                    value={password}
                                    onChange={(e) => setPassword(e.target.value)}
                                    className="w-full pl-10 pr-12 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                                    placeholder="••••••••"
                                    required
                                />
                                <button
                                    type="button"
                                    onClick={() => setShowPassword(!showPassword)}
                                    className="absolute right-3 top-1/2 transform -translate-y-1/2 text-gray-400 hover:text-gray-600"
                                >
                                    {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                                </button>
                            </div>
                        </div>

                        <button
                            type="submit"
                            disabled={loading}
                            className="w-full py-3 bg-gradient-to-r from-purple-600 to-blue-600 text-white rounded-lg hover:from-purple-700 hover:to-blue-700 transition-all font-medium disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {loading ? 'Please wait...' : isSignup ? 'Create Account' : 'Sign In'}
                        </button>
                    </form>

                    <div className="mt-6 text-center">
                        <button
                            onClick={() => {
                                setIsSignup(!isSignup);
                                setError('');
                            }}
                            className="text-sm text-purple-600 hover:text-purple-700 font-medium"
                        >
                            {isSignup ? 'Already have an account? Sign in' : "Don't have an account? Sign up"}
                        </button>
                    </div>
                </div>

                {}
                <p className="text-center text-xs text-gray-500 mt-6">
                    {appVersion ? `OntoCode v${appVersion}` : 'OntoCode'}
                    <span className="mx-2">·</span>
                    By continuing, you agree to our Terms of Service and Privacy Policy
                </p>
            </div>
        </div>
    );
};

export default Login;
