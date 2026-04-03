
import React, { useState } from 'react';
import { useAuth } from '../custom-hook/useAuth';
import { Loader2, Eye, EyeOff, ArrowLeft } from 'lucide-react';

interface LoginFormProps {
  onToggleForm: () => void;
  prefillEmail?: string;
  onBackToInvitation?: () => void;
  onBackToWelcome?: () => void;
  onForgotPassword?: () => void;
}

const LoginForm = ({
  onToggleForm,
  prefillEmail,
  onBackToInvitation,
  onBackToWelcome,
  onForgotPassword,
}: LoginFormProps) => {
  const [username, setUsername] = useState(prefillEmail || "");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState("");
  const { login, sessionExpiredMessage } = useAuth();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError("");
    try {
      await login(username, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : "An unknown error occurred.");
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
          <h2 className="text-3xl font-bold text-white mb-2">Welcome to OntoCode</h2>
          <p className="text-gray-300">Sign in to access your ontology editor</p>
        </div>

        {sessionExpiredMessage && (
          <div className="bg-amber-500/10 border border-amber-400/30 text-amber-400 px-4 py-3 rounded-lg mb-6 text-sm backdrop-blur-sm">
            {sessionExpiredMessage}
          </div>
        )}

        {error && (
          <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-lg mb-6 text-sm backdrop-blur-sm">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-6">
          <div>
            <label htmlFor="username" className="block text-sm font-medium text-gray-200 mb-2">
              Username
            </label>
            <input
              type="text"
              id="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              disabled={isLoading}
              className="w-full px-4 py-3 bg-white/5 border border-white/20 rounded-lg shadow-sm placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500 text-white"
              placeholder="Enter your username"
            />
          </div>

          <div>
            <label htmlFor="password" className="block text-sm font-medium text-gray-200 mb-2">
              Password
            </label>
            <div className="relative">
              <input
                type={showPassword ? "text" : "password"}
                id="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                disabled={isLoading}
                className="w-full px-4 py-3 pr-12 bg-white/5 border border-white/20 rounded-lg shadow-sm placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-purple-500 text-white"
                placeholder="Enter your password"
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
          </div>

          {onForgotPassword && (
            <div className="text-right">
              <button
                type="button"
                onClick={onForgotPassword}
                className="text-purple-400 hover:text-purple-300 text-sm font-medium"
              >
                Forgot Password?
              </button>
            </div>
          )}

          <button
            type="submit"
            disabled={isLoading}
            className={`w-full flex items-center justify-center py-3 px-4 rounded-lg text-sm font-medium text-white transition-all duration-300 ${
              isLoading
                ? "bg-purple-400 cursor-not-allowed"
                : "bg-gradient-to-r from-purple-500 to-indigo-500 hover:from-purple-600 hover:to-indigo-600 focus:outline-none focus:ring-2 focus:ring-purple-500"
            }`}
          >
            {isLoading ? <Loader2 className="animate-spin" /> : "Sign In"}
          </button>
        </form>

        <div className="mt-8 text-center space-y-3">
          <p className="text-gray-400 text-sm">
            Don't have an account?{" "}
            <button onClick={onToggleForm} className="text-purple-400 hover:text-purple-300 font-medium">
              Sign up
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

export default LoginForm;
