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

  // Reset all state when modal opens/closes
  React.useEffect(() => {
    if (isOpen) {
      setEmail("");
      setRole("MEMBER");
      setInviting(false);
      setInvitationLinks(null);
      setErrorMessage("");
      setSent(false);
      // Check limit only once when modal opens
      setShowLimitExceeded(!!(maxMembers && currentMemberCount >= maxMembers));
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // Validate email format
    const emailValidation = validateEmail(email);
    if (!emailValidation.isValid) {
      setErrorMessage(emailValidation.error || "Invalid email format");
      return;
    }

    // Validate role
    const roleValidation = validateRole(role);
    if (!roleValidation.isValid) {
      setErrorMessage(roleValidation.error || "Invalid role selected");
      return;
    }

    // Check for duplicate member
    if (existingMemberEmails.some((existingEmail) => existingEmail.toLowerCase() === email.trim().toLowerCase())) {
      setErrorMessage("This user is already a member of the workspace");
      return;
    }

    // Check member limit if maxMembers is provided
    if (maxMembers && currentMemberCount >= maxMembers) {
      setErrorMessage(
        `Maximum member limit reached (${maxMembers} for ${subscriptionPlan} plan). Please upgrade your subscription or remove existing members.`,
      );
      return;
    }

    try {
      setInviting(true);
      setErrorMessage(""); // Clear any previous errors
      const response = await onInvite(email.trim(), role);

      setInvitationLinks({ webLink: "", vscodeLink: "" });
      setSent(true);
    } catch (error: any) {
      console.error("Error inviting member:", error);
      // Extract error message from various possible error structures
      const errorMsg =
        error?.message || error?.response?.error || error?.error || "Failed to send invitation. Please try again.";
      setErrorMessage(errorMsg);
      setInviting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-2xl max-w-md w-full">
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-purple-100 rounded-full flex items-center justify-center">
              <UserPlus size={20} className="text-purple-600" />
            </div>
            <div>
              <h3 className="text-xl font-semibold text-gray-900">Invite Team Member</h3>
              <p className="text-sm text-gray-500">{workspaceName}</p>
            </div>
          </div>
          <button onClick={onClose} className="p-2 hover:bg-gray-100 rounded-lg transition-colors">
            <X size={20} className="text-gray-500" />
          </button>
        </div>

        {/* Body */}
        {showLimitExceeded ? (
          <div className="p-6 space-y-4">
            {/* Member Limit Exceeded Message */}
            <div className="bg-amber-50 border-2 border-amber-200 rounded-lg p-6">
              <div className="flex items-start gap-3">
                <div className="p-2 bg-amber-100 rounded-lg">
                  <AlertCircle size={24} className="text-amber-600" />
                </div>
                <div className="flex-1">
                  <h4 className="text-lg font-semibold text-amber-900 mb-2">Member Limit Reached</h4>
                  <p className="text-sm text-amber-800 mb-4">
                    Your current <span className="font-semibold">{subscriptionPlan}</span> plan subscription allows a
                    maximum of <span className="font-semibold">{maxMembers} team members</span>. You currently have{" "}
                    <span className="font-semibold">{currentMemberCount} members</span> added.
                  </p>
                  <p className="text-sm text-amber-800">
                    To add more team members, please upgrade your subscription plan or remove existing members.
                  </p>
                </div>
              </div>
            </div>

            {/* Upgrade Plan Button (only for workspace owner) */}
            {/* {isWorkspaceOwner && onUpgradePlan && (
                            <button
                                type="button"
                                onClick={() => {
                                    onClose();
                                    onUpgradePlan();
                                }}
                                className="w-full flex items-center justify-center gap-2 px-6 py-3 bg-gradient-to-r from-purple-600 to-blue-600 text-white rounded-lg hover:from-purple-700 hover:to-blue-700 font-medium transition-all shadow-lg hover:shadow-xl"
                            >
                                <Crown size={20} />
                                Upgrade Subscription Plan
                                <ArrowRight size={20} />
                            </button>
                        )} */}

            {/* Info message if not owner */}
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
                className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg hover:bg-gray-50 font-medium transition-colors"
              >
                Close
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="p-6 space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">Email Address *</label>
              <div className="relative">
                <Mail size={18} className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" />
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="w-full pl-10 pr-4 py-2.5 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                  placeholder="colleague@example.com"
                  required
                  disabled={inviting}
                />
              </div>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">Role</label>
              <select
                value={role}
                onChange={(e) => setRole(e.target.value)}
                className="w-full px-4 py-2.5 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                disabled={inviting}
              >
                <option value="VIEWER">Viewer - Read only access</option>
                <option value="MEMBER">Member - Can edit and collaborate</option>
                <option value="ADMIN">Admin - Full access</option>
              </select>
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
                className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg hover:bg-gray-50 font-medium transition-colors"
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
