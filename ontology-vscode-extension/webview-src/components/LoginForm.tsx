
import React, { useState } from 'react';
import { useAuth } from '../custom-hook/useAuth';
import { Loader2, Eye, EyeOff, ArrowLeft, Bug, RefreshCw } from 'lucide-react';
import ReportIssueModal from './ReportIssueModal';

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
  const [isResendingVerification, setIsResendingVerification] = useState(false);
  const [error, setError] = useState("");
  const [resendMessage, setResendMessage] = useState("");
  const [resendError, setResendError] = useState("");
  const [isReportIssueModalOpen, setIsReportIssueModalOpen] = useState(false);
  const { login, resendVerification, sessionExpiredMessage } = useAuth();
  const showResendVerification = error.toLowerCase().includes("account not verified");

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError("");
    setResendMessage("");
    setResendError("");
    try {
      await login(username, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : "An unknown error occurred.");
    } finally {
      setIsLoading(false);
    }
  };

  const handleResendVerification = async () => {
    const identifier = username.trim();
    if (!identifier) {
      setResendError("Enter your email or username first.");
      return;
    }

    setIsResendingVerification(true);
    setResendMessage("");
    setResendError("");
    try {
      const message = await resendVerification(identifier);
      setResendMessage(message);
    } catch (err) {
      setResendError(err instanceof Error ? err.message : "Failed to resend verification email");
    } finally {
      setIsResendingVerification(false);
    }
  };

  return (
    <div className="min-h-screen min-h-[100dvh] bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex items-center justify-center p-4 pb-20 sm:pb-4 overflow-y-auto">
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        <div className="absolute -top-40 -right-40 w-80 h-80 bg-purple-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse"></div>
        <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-indigo-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse delay-1000"></div>
      </div>

      <div className="relative bg-white/10 backdrop-blur-xl border border-white/20 rounded-2xl shadow-2xl p-6 sm:p-8 w-full max-w-md mx-auto">
        <div className="text-center mb-8">
          <h2 className="text-2xl sm:text-3xl font-bold text-white mb-2">Welcome to OntoCode</h2>
          <p className="text-gray-300">Sign in to access your ontology editor</p>
        </div>

        {sessionExpiredMessage && (
          <div className="bg-amber-500/10 border border-amber-400/30 text-amber-400 px-4 py-3 rounded-lg mb-6 text-sm backdrop-blur-sm">
            {sessionExpiredMessage}
          </div>
        )}

        {error && (
          <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-lg mb-6 text-sm backdrop-blur-sm">
            <div>{error}</div>
            {showResendVerification && (
              <div className="mt-3 pt-3 border-t border-red-400/20">
                <p className="text-red-200/90 mb-2">
                  Need a new verification email?
                </p>
                {resendMessage && <p className="text-green-300 mb-2">{resendMessage}</p>}
                {resendError && <p className="text-red-200 mb-2">{resendError}</p>}
                <button
                  type="button"
                  onClick={handleResendVerification}
                  disabled={isResendingVerification}
                  className="inline-flex items-center gap-2 px-3 py-2 rounded-lg bg-white/10 border border-white/20 text-white hover:bg-white/20 transition-colors disabled:opacity-60"
                >
                  {isResendingVerification ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />}
                  {isResendingVerification ? "Sending..." : "Resend verification link"}
                </button>
              </div>
            )}
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
          {/* Desktop download link */}
          <div className="pt-2 border-t border-white/10">
            <button
              onClick={() => window.dispatchEvent(new CustomEvent('navigate-desktop-download'))}
              className="flex items-center justify-center gap-2 w-full text-purple-400 hover:text-purple-300 text-sm py-1"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/></svg>
              Download Desktop App (Windows / macOS / Linux)
            </button>
          </div>
        </div>
      </div>

      {/* Report Issue floating button */}
      <button
        onClick={() => setIsReportIssueModalOpen(true)}
        className="fixed bottom-4 right-4 inline-flex items-center gap-1.5 px-3 py-2 sm:py-1.5 rounded-lg bg-white/10 hover:bg-white/20 border border-white/20 text-xs font-medium text-white transition-colors backdrop-blur-sm z-50"
        title="Report an issue"
      >
        <Bug size={14} />
        <span className="sm:hidden">Issue</span>
        <span className="hidden sm:inline">Report Issue</span>
      </button>

      {isReportIssueModalOpen && (
        <ReportIssueModal onClose={() => setIsReportIssueModalOpen(false)} />
      )}
    </div>
  );
};

export default LoginForm;
