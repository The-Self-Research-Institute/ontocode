import React, { useState } from 'react';
import { X, Mail, UserPlus, Check, AlertCircle, Crown, ArrowRight } from "lucide-react";
import { validateEmail, validateRole } from "../utils/validation";

interface InviteMemberModalProps {
  isOpen: boolean;
  onClose: () => void;
  workspaceId: string;
  workspaceName: string;
  subscriptionPlan?: string;
  currentMemberCount?: number;
  maxMembers?: number;
  existingMemberEmails?: string[];
  isWorkspaceOwner?: boolean;
  onUpgradePlan?: () => void;
  onInvite: (email: string, role: string) => Promise<void>;
}

const InviteMemberModal: React.FC<InviteMemberModalProps> = ({
  isOpen,
  onClose,
  workspaceId,
  workspaceName,
  subscriptionPlan = "FREE",
  currentMemberCount = 0,
  maxMembers,
  existingMemberEmails = [],
  isWorkspaceOwner = false,
  onUpgradePlan,
  onInvite,
}) => {
  const [email, setEmail] = useState("");
  const [role, setRole] = useState("MEMBER");
  const [inviting, setInviting] = useState(false);
  const [invitationLinks, setInvitationLinks] = useState<{ webLink: string; vscodeLink: string } | null>(null);
  const [errorMessage, setErrorMessage] = useState("");
  const [showLimitExceeded, setShowLimitExceeded] = useState(false);
  const [sent, setSent] = useState(false);

  React.useEffect(() => {
    if (isOpen) {
      setEmail("");
      setRole("MEMBER");
      setInviting(false);
      setInvitationLinks(null);
      setErrorMessage("");
      setSent(false);

      setShowLimitExceeded(!!(maxMembers && currentMemberCount >= maxMembers));
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    const emailValidation = validateEmail(email);
    if (!emailValidation.isValid) {
      setErrorMessage(emailValidation.error || "Invalid email format");
      return;
    }

    const roleValidation = validateRole(role);
    if (!roleValidation.isValid) {
      setErrorMessage(roleValidation.error || "Invalid role selected");
      return;
    }

    if (existingMemberEmails.some((existingEmail) => existingEmail.toLowerCase() === email.trim().toLowerCase())) {
      setErrorMessage("This user is already a member of the workspace");
      return;
    }

    if (maxMembers && currentMemberCount >= maxMembers) {
      setErrorMessage(
        `Maximum member limit reached (${maxMembers} for ${subscriptionPlan} plan). Please upgrade your subscription or remove existing members.`,
      );
      return;
    }

    try {
      setInviting(true);
      setErrorMessage(""); // Clear any previous errors
      await onInvite(email.trim(), role);

      setInvitationLinks({ webLink: "", vscodeLink: "" });
      setSent(true);
      setInviting(false);

      setTimeout(() => onClose(), 1500);
    } catch (error: any) {
      console.error("Error inviting member:", error);

      const errorMsg =
        error?.message || error?.response?.error || error?.error || "Failed to send invitation. Please try again.";
      setErrorMessage(errorMsg);
      setInviting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4">
      <div className="bg-slate-900 border border-slate-700 rounded-xl shadow-2xl max-w-md w-full text-slate-100">
        {}
        <div className="flex items-center justify-between p-6 border-b border-slate-700">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-purple-500/15 border border-purple-400/30 rounded-full flex items-center justify-center">
              <UserPlus size={20} className="text-purple-300" />
            </div>
            <div>
              <h3 className="text-xl font-semibold text-white">Invite Workspace Member</h3>
              <p className="text-sm text-slate-300">{workspaceName}</p>
            </div>
          </div>
          <button onClick={onClose} className="p-2 hover:bg-slate-800 rounded-lg transition-colors">
            <X size={20} className="text-slate-300" />
          </button>
        </div>

        {}
        {showLimitExceeded ? (
          <div className="p-6 space-y-4">
            {}
            <div className="bg-amber-50 border-2 border-amber-200 rounded-lg p-6">
              <div className="flex items-start gap-3">
                <div className="p-2 bg-amber-100 rounded-lg">
                  <AlertCircle size={24} className="text-amber-600" />
                </div>
                <div className="flex-1">
                  <h4 className="text-lg font-semibold text-amber-900 mb-2">Member Limit Reached</h4>
                  <p className="text-sm text-amber-800 mb-4">
                    Your current <span className="font-semibold">{subscriptionPlan}</span> plan subscription allows a
                    maximum of <span className="font-semibold">{maxMembers} workspace members</span>. You currently have{" "}
                    <span className="font-semibold">{currentMemberCount} members</span> added.
                  </p>
                  <p className="text-sm text-amber-800">
                    To add more workspace members, please upgrade your subscription plan or remove existing members.
                  </p>
                </div>
              </div>
            </div>

            {isWorkspaceOwner && onUpgradePlan && (
              <button
                type="button"
                onClick={() => {
                  onClose();
                  onUpgradePlan();
                }}
                className="w-full h-11 inline-flex items-center justify-center gap-2 px-6 bg-gradient-to-r from-purple-600 to-blue-600 text-white rounded-lg hover:from-purple-700 hover:to-blue-700 font-medium transition-all shadow-lg hover:shadow-xl"
              >
                <Crown size={20} />
                Upgrade Subscription Plan
                <ArrowRight size={20} />
              </button>
            )}

            {}
            {!isWorkspaceOwner && (
              <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
                <div className="flex items-start gap-2">
                  <AlertCircle size={18} className="text-blue-600 flex-shrink-0 mt-0.5" />
                  <p className="text-sm text-blue-800">
                    Only the workspace owner can upgrade the subscription plan. Please contact the workspace owner to
                    add more members.
                  </p>
                </div>
              </div>
            )}

            <div className="flex gap-3 pt-4">
              <button
                type="button"
                onClick={onClose}
                className="flex-1 px-4 py-2.5 border border-slate-600 rounded-lg hover:bg-slate-800 font-medium transition-colors text-slate-100"
              >
                Close
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="p-6 space-y-4">
            <div>
              <label className="block text-sm font-semibold text-slate-100 mb-2">Email Address *</label>
              <div className="relative">
                <Mail size={18} className="absolute left-3 top-1/2 transform -translate-y-1/2 text-slate-400" />
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="w-full pl-10 pr-4 py-2.5 border border-slate-600 bg-slate-800 text-white placeholder:text-slate-400 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-400 disabled:opacity-60"
                  placeholder="colleague@example.com"
                  required
                  disabled={inviting}
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-semibold text-slate-100 mb-2">Workspace role</label>
              <p className="text-xs text-slate-300 mb-2 leading-relaxed">
                This controls access across the whole workspace (not a single project). Project roles are set per project in Project settings.
              </p>
              <select
                value={role}
                onChange={(e) => setRole(e.target.value)}
                className="w-full px-4 py-2.5 border border-slate-600 bg-slate-800 text-white rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-400 disabled:opacity-60"
                disabled={inviting}
              >
                <option value="MEMBER">Member</option>
                <option value="ADMIN">Admin</option>
              </select>
              <div className="mt-3 rounded-lg border border-slate-700 bg-slate-800/60 p-3 space-y-1.5 text-xs text-slate-300">
                <p><span className="font-semibold text-white">Member:</span> sees workspace resources. Added to projects manually via Project Settings.</p>
                <p><span className="font-semibold text-white">Admin:</span> full access to workspace. Auto-added as Editor to all non-private projects on join.</p>
              </div>
            </div>

            {errorMessage && (
              <div className="bg-red-50 border border-red-200 rounded-lg p-4">
                <div className="flex items-start gap-2">
                  <AlertCircle size={20} className="text-red-600 flex-shrink-0 mt-0.5" />
                  <div>
                    <p className="text-sm font-medium text-red-800">Invitation Failed</p>
                    <p className="text-xs text-red-700 mt-1">{errorMessage}</p>
                  </div>
                </div>
              </div>
            )}

            {invitationLinks && (
              <div className="bg-green-50 border border-green-200 rounded-lg p-4">
                <div className="flex items-center gap-2">
                  <Check size={20} className="text-green-600" />
                  <p className="text-sm font-medium text-green-800">Invitation sent successfully!</p>
                </div>
                <p className="text-xs text-green-700 mt-1 ml-7">An email has been sent to {email}.</p>
              </div>
            )}

            <div className="flex gap-3 pt-4">
              <button
                type="button"
                onClick={onClose}
                className="flex-1 px-4 py-2.5 border border-slate-600 rounded-lg hover:bg-slate-800 font-medium transition-colors text-slate-100 disabled:opacity-50"
                disabled={inviting}
              >
                Cancel
              </button>
              <button
                type="submit"
                className={`flex-1 px-4 py-2.5 text-white rounded-lg font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                  sent ? "bg-green-600" : "bg-purple-600 hover:bg-purple-700"
                }`}
                disabled={inviting || sent}
              >
                {sent ? "Sent" : inviting ? "Sending..." : "Send Invitation"}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
};

export default InviteMemberModal;
