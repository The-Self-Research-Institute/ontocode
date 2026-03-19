
import React, { useState } from 'react';
import { useAuth } from '../custom-hook/useAuth';
import { Loader2, Eye, EyeOff, ArrowLeft } from 'lucide-react';
import { getBaseUrl } from '../services/apiClient';

interface SignupFormProps {
    onToggleForm: () => void;
    prefillEmail?: string;
    onBackToInvitation?: () => void;
}

const SignupForm = ({ onToggleForm, prefillEmail, onBackToInvitation }: SignupFormProps) => {
    const [username, setUsername] = useState('');
    const [email, setEmail] = useState(prefillEmail || '');
    const [password, setPassword] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [showPassword, setShowPassword] = useState(false);
    const [showConfirmPassword, setShowConfirmPassword] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const [isOidcLoading, setIsOidcLoading] = useState(false);
    const [error, setError] = useState('');
    const [successMessage, setSuccessMessage] = useState('');
    const { signup } = useAuth();

    const validatePassword = (pwd: string): string | null => {
        if (pwd.length < 8) return 'Password must be at least 8 characters';
        if (!/[A-Z]/.test(pwd)) return 'Password must contain an uppercase letter';
        if (!/[a-z]/.test(pwd)) return 'Password must contain a lowercase letter';
        if (!/[0-9]/.test(pwd)) return 'Password must contain a number';
        if (!/[@#$%^&+=!]/.test(pwd)) return 'Password must contain a special character (@#$%^&+=!)';
        if (/\s/.test(pwd)) return 'Password cannot contain whitespace';
        return null;
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError('');
        setSuccessMessage('');
        
        // Validate password
        const passwordError = validatePassword(password);
        if (passwordError) {
            setError(passwordError);
            return;
        }
        
        if (password !== confirmPassword) {
            setError("Passwords don't match.");
            return;
        }
        
        setIsLoading(true);
        try {
            await signup(username, email, password);
            // If we get here without error, signup succeeded with immediate login
        } catch (err: any) {
            // Check if it's a success case (verification required)
            if (err?.success && err?.message) {
                setSuccessMessage(err.message);
            } else {
                setError(err instanceof Error ? err.message : 'An unknown error occurred.');
            }
        } finally {
            setIsLoading(false);
        }
    };

    return (
        <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex items-center justify-center p-4">
             <div className="absolute inset-0 overflow-hidden">
                <div className="absolute -top-40 -right-40 w-80 h-80 bg-purple-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse"></div>
                <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-indigo-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse delay-1000"></div>
            </div>

            <div className="relative bg-white/10 backdrop-blur-xl border border-white/20 rounded-2xl shadow-2xl p-8 w-full max-w-md">
                <div className="text-center mb-8">
                    <h2 className="text-3xl font-bold text-white mb-2">
                        Create Your Account
                    </h2>
                    <p className="text-gray-300">
                        Get started with OntoCode
                    </p>
                </div>

                {error && (
                    <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-lg mb-6 text-sm backdrop-blur-sm">
                        {error}
                    </div>
                )}
                
                {successMessage && (
                    <div className="bg-green-500/10 border border-green-400/30 text-green-400 px-4 py-3 rounded-lg mb-6 text-sm backdrop-blur-sm">
                        {successMessage}
                    </div>
                )}

                <form onSubmit={handleSubmit} className="space-y-4">
                    <input
                        type="text"
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                        required
                        disabled={isLoading}
                        className="w-full px-4 py-3 bg-white/5 border border-white/20 rounded-lg shadow-sm placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500 text-white"
                        placeholder="Username"
                    />

                    <input
                        type="email"
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                        required
                        disabled={isLoading}
                        className="w-full px-4 py-3 bg-white/5 border border-white/20 rounded-lg shadow-sm placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500 text-white"
                        placeholder="Email Address"
                    />
                    <div>
                        <div className="relative">
                            <input
                                type={showPassword ? "text" : "password"}
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                                required
                                disabled={isLoading}
                                className="w-full px-4 py-3 pr-12 bg-white/5 border border-white/20 rounded-lg shadow-sm placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500 text-white"
                                placeholder="Password"
                            />
                            <button
                                type="button"
                                onClick={() => setShowPassword(!showPassword)}
                                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-200 focus:outline-none"
                                tabIndex={-1}
                            >
                                {showPassword ? <EyeOff size={20} /> : <Eye size={20} />}
                            </button>
                        </div>
                        <p className="mt-1 text-xs text-gray-400">
                            Min 8 chars, with uppercase, lowercase, number, and special char (@#$%^&+=!)
                        </p>
                    </div>
                    <div className="relative">
                        <input
                            type={showConfirmPassword ? "text" : "password"}
                            value={confirmPassword}
                            onChange={(e) => setConfirmPassword(e.target.value)}
                            required
                            disabled={isLoading}
                            className="w-full px-4 py-3 pr-12 bg-white/5 border border-white/20 rounded-lg shadow-sm placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500 text-white"
                            placeholder="Confirm Password"
                        />
                        <button
                            type="button"
                            onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                            className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-200 focus:outline-none"
                            tabIndex={-1}
                        >
                            {showConfirmPassword ? <EyeOff size={20} /> : <Eye size={20} />}
                        </button>
                    </div>
                    <button
                        type="submit"
                        disabled={isLoading}
                         className={`w-full flex items-center justify-center py-3 px-4 rounded-lg text-sm font-medium text-white transition-all duration-300 ${
                            isLoading
                                ? 'bg-purple-400 cursor-not-allowed'
                                : 'bg-gradient-to-r from-purple-500 to-indigo-500 hover:from-purple-600 hover:to-indigo-600 focus:outline-none focus:ring-2 focus:ring-purple-500'
                        }`}
                    >
                         {isLoading ? <Loader2 className="animate-spin" /> : 'Sign Up'}
                    </button>
                </form>

                <div className="my-6 flex items-center">
                    <div className="flex-1 border-t border-white/20"></div>
                    <span className="px-4 text-sm text-gray-400">or</span>
                    <div className="flex-1 border-t border-white/20"></div>
                </div>

                <button
                    type="button"
                    onClick={async () => {
                        if (typeof window !== 'undefined' && (window as any).vscode) {
                            (window as any).vscode.postMessage({ type: 'loginWithOidc', mode: 'signup' });
                        } else {
                            // Browser / dev mode: open a popup with embedded_view=true and kc_action=register.
                            setIsOidcLoading(true);
                            try {
                                const baseUrl = getBaseUrl();
                                const res = await fetch(`${baseUrl}/api/auth/oidc/providers`);
                                const data = await res.json();
                                const provider = data?.providers?.[0];
                                if (provider?.authUrl) {
                                    const sep = provider.authUrl.includes('?') ? '&' : '?';
                                    const authUrl = `${baseUrl}${provider.authUrl}${sep}kc_action=register&embedded_view=true`;
                                    const popup = window.open(
                                        authUrl,
                                        'keycloak-signup',
                                        'width=520,height=660,top=100,left=100,resizable=yes,scrollbars=yes'
                                    );
                                    if (!popup) {
                                        // Popup blocked — fall back to redirect_uri approach.
                                        const callbackUrl = window.location.origin + window.location.pathname;
                                        window.location.href = `${baseUrl}${provider.authUrl}${sep}kc_action=register&redirect_uri=${encodeURIComponent(callbackUrl)}`;
                                    }
                                } else {
                                    setError('No OIDC providers available. Make sure backend services are running.');
                                }
                            } catch (err) {
                                console.error('[SignupForm] Failed to fetch OIDC providers:', err);
                                setError('Could not reach the backend. Make sure backend services are running.');
                            } finally {
                                setIsOidcLoading(false);
                            }
                        }
                    }}
                    disabled={isLoading || isOidcLoading}
                    className="w-full flex items-center justify-center gap-3 py-3 px-4 bg-white/10 border border-white/20 rounded-lg text-sm font-medium text-white hover:bg-white/15 focus:outline-none focus:ring-2 focus:ring-blue-500 transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                    {isOidcLoading ? (
                        <Loader2 className="animate-spin w-5 h-5" />
                    ) : (
                        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <path d="M12 2C6.48 2 2 6.48 2 12C2 17.52 6.48 22 12 22C17.52 22 22 17.52 22 12C22 6.48 17.52 2 12 2ZM12 20C7.59 20 4 16.41 4 12C4 7.59 7.59 4 12 4C16.41 4 20 7.59 20 12C20 16.41 16.41 20 12 20ZM12 6C9.24 6 7 8.24 7 11H9C9 9.34 10.34 8 12 8C13.66 8 15 9.34 15 11C15 12.66 13.66 14 12 14H11V16H13C14.66 16 16 17.34 16 19C16 20.66 14.66 22 13 22H11C9.34 22 8 20.66 8 19H6C6 21.76 8.24 24 11 24H13C15.76 24 18 21.76 18 19C18 17.62 17.35 16.4 16.35 15.6C17.35 14.8 18 13.58 18 12.2C18 9.88 16.12 8 13.8 8H12.2C9.88 8 8 9.88 8 12.2C8 13.58 8.65 14.8 9.65 15.6C8.65 16.4 8 17.62 8 19C8 20.66 9.34 22 11 22V20C10.34 20 9 19.66 9 19C9 17.34 10.34 16 12 16C13.66 16 15 17.34 15 19C15 19.66 13.66 20 13 20V22C14.66 22 16 20.66 16 19C16 17.34 14.66 16 13 16H12C10.34 16 9 14.66 9 13C9 11.34 10.34 10 12 10C13.66 10 15 11.34 15 13C15 14.66 13.66 16 12 16V18C14.66 18 17 15.66 17 13C17 10.34 14.66 8 12 8Z" fill="currentColor"/>
                        </svg>
                    )}
                    {isOidcLoading ? 'Redirecting to Keycloak...' : 'Sign up with Keycloak'}
                </button>

                <div className="mt-8 text-center space-y-3">
                    <p className="text-gray-400 text-sm">
                        Already have an account?{' '}
                        <button onClick={onToggleForm} className="text-purple-400 hover:text-purple-300 font-medium">
                            Sign in
                        </button>
                    </p>
                    {onBackToInvitation && (
                        <button 
                            onClick={onBackToInvitation}
                            className="flex items-center justify-center gap-2 w-full text-gray-400 hover:text-gray-300 text-sm"
                        >
                            <ArrowLeft size={16} />
                            Back to Invitation
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
};

export default SignupForm;
