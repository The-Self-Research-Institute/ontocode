import React, { useState } from "react";
import { useAuth } from "../custom-hook/useAuth";
import { Loader2, Mail, ArrowLeft, RefreshCw, Bug } from "lucide-react";
import ReportIssueModal from "./ReportIssueModal";

interface EmailVerificationNoticeProps {
  email: string;
  onBackToLogin: () => void;
}

const EmailVerificationNotice = ({ email, onBackToLogin }: EmailVerificationNoticeProps) => {
  const [isResending, setIsResending] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [isReportIssueModalOpen, setIsReportIssueModalOpen] = useState(false);
  const { resendVerification } = useAuth();

  const handleResend = async () => {
    setIsResending(true);
    setError("");
    setMessage("");
    try {
      const msg = await resendVerification(email);
      setMessage(msg);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to resend verification email");
    } finally {
      setIsResending(false);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-purple-900 to-slate-900 flex items-center justify-center p-4 overflow-y-auto">
      <div className="absolute inset-0 overflow-hidden pointer-events-none">
        <div className="absolute -top-40 -right-40 w-80 h-80 bg-purple-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse"></div>
        <div className="absolute -bottom-40 -left-40 w-80 h-80 bg-indigo-500 rounded-full mix-blend-multiply filter blur-xl opacity-20 animate-pulse delay-1000"></div>
      </div>

      <div className="relative bg-white/10 backdrop-blur-xl border border-white/20 rounded-2xl shadow-2xl p-8 w-full max-w-md text-center">
        <div className="w-16 h-16 bg-purple-500/20 rounded-full flex items-center justify-center mx-auto mb-6">
          <Mail size={32} className="text-purple-400" />
        </div>

        <h2 className="text-2xl font-bold text-white mb-2">Check Your Email</h2>
        <p className="text-gray-300 mb-6">
          We've sent a verification link to <span className="text-purple-400 font-medium">{email}</span>. Please click
          the link to verify your account.
        </p>

        <div className="bg-white/5 border border-white/10 rounded-lg p-4 mb-6 text-left">
          <p className="text-gray-400 text-sm">
            <strong className="text-gray-300">Didn't receive the email?</strong>
          </p>
          <ul className="text-gray-400 text-sm mt-2 space-y-1">
            <li>• Check your spam or junk folder</li>
            <li>• Make sure you entered the correct email</li>
            <li>• The link expires in 24 hours</li>
          </ul>
        </div>

        {message && (
          <div className="bg-green-500/10 border border-green-400/30 text-green-400 px-4 py-3 rounded-lg mb-4 text-sm backdrop-blur-sm">
            {message}
          </div>
        )}

        {error && (
          <div className="bg-red-500/10 border border-red-400/30 text-red-400 px-4 py-3 rounded-lg mb-4 text-sm backdrop-blur-sm">
            {error}
          </div>
        )}

        <button
          onClick={handleResend}
          disabled={isResending}
          className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-lg text-sm font-medium text-white transition-all duration-300 bg-white/10 border border-white/20 hover:bg-white/20 mb-3"
        >
          {isResending ? <Loader2 size={18} className="animate-spin" /> : <RefreshCw size={18} />}
          Resend Verification Email
        </button>

        <button
          onClick={onBackToLogin}
          className="flex items-center justify-center gap-2 w-full text-gray-400 hover:text-gray-300 text-sm py-2"
        >
          <ArrowLeft size={16} />
          Back to Sign In
        </button>
      </div>

      {/* Report Issue floating button */}
      <button
        onClick={() => setIsReportIssueModalOpen(true)}
        className="fixed bottom-4 right-4 inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-white/10 hover:bg-white/20 border border-white/20 text-xs font-medium text-white transition-colors backdrop-blur-sm z-50"
        title="Report an issue"
      >
        <Bug size={14} />
        Report Issue
      </button>

      {isReportIssueModalOpen && (
        <ReportIssueModal onClose={() => setIsReportIssueModalOpen(false)} />
      )}
    </div>
  );
};

export default EmailVerificationNotice;
