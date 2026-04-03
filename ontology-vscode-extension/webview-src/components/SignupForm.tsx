
import React, { useState } from 'react';
import { useAuth } from '../custom-hook/useAuth';
import { Loader2, Eye, EyeOff, ArrowLeft } from 'lucide-react';

interface SignupFormProps {
    onToggleForm: () => void;
    prefillEmail?: string;
    onBackToInvitation?: () => void;
    onBackToWelcome?: () => void;
    onVerificationRequired?: (email: string) => void;
}

const SignupForm = ({ onToggleForm, prefillEmail, onBackToInvitation, onBackToWelcome, onVerificationRequired }: SignupFormProps) => {
    const [username, setUsername] = useState('');
    const [email, setEmail] = useState(prefillEmail || '');
    const [password, setPassword] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [showPassword, setShowPassword] = useState(false);
    const [showConfirmPassword, setShowConfirmPassword] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
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
            const result = await signup(username, email, password);
            if (result.requiresVerification) {
                if (onVerificationRequired) {
                    onVerificationRequired(result.email || email);
                } else {
                    setSuccessMessage(result.message || 'Please check your email to verify your account.');
                }
            }
            // If no verification required, user is logged in automatically
        } catch (err: any) {
            if (err?.requiresVerification) {
                if (onVerificationRequired) {
                    onVerificationRequired(err.email || email);
                } else {
                    setSuccessMessage(err.message || 'Please check your email to verify your account.');
                }
            } else {
                setError(err instanceof Error ? err.message : 'An unknown error occurred.');
            }
        } finally {
            setIsLoading(false);
        }
    };

    return (
      <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex items-center justify-center p-4 overflow-y-auto">
        <div className="absolute inset-0 overflow-hidden pointer-events-none">
          <div className="absolute -top-40 -right-40 w-80 h-80 bg-purple-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse"></div>
          <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-indigo-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse delay-1000"></div>
        </div>

        <div className="relative bg-white/10 backdrop-blur-xl border border-white/20 rounded-2xl shadow-2xl p-8 w-full max-w-md">
          <div className="text-center mb-8">
            <h2 className="text-3xl font-bold text-white mb-2">Create Your Account</h2>
            <p className="text-gray-300">Get started with OntoCode</p>
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
                  ? "bg-purple-400 cursor-not-allowed"
                  : "bg-gradient-to-r from-purple-500 to-indigo-500 hover:from-purple-600 hover:to-indigo-600 focus:outline-none focus:ring-2 focus:ring-purple-500"
              }`}
            >
              {isLoading ? <Loader2 className="animate-spin" /> : "Sign Up"}
            </button>
          </form>

          <div className="mt-8 text-center space-y-3">
            <p className="text-gray-400 text-sm">
              Already have an account?{" "}
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
            {onBackToWelcome && (
              <button
                onClick={onBackToWelcome}
                className="flex items-center justify-center gap-2 w-full text-gray-400 hover:text-gray-300 text-sm"
              >
                <ArrowLeft size={16} />
                Back to Welcome
              </button>
            )}
          </div>
        </div>
      </div>
    );
};

export default SignupForm;
