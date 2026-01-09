import React, { useState } from 'react';
import { X, Mail, UserPlus, Copy, Check, AlertCircle } from 'lucide-react';

interface InviteMemberModalProps {
    isOpen: boolean;
    onClose: () => void;
    workspaceId: string;
    workspaceName: string;
    onInvite: (email: string, role: string) => Promise<void>;
}

const InviteMemberModal: React.FC<InviteMemberModalProps> = ({
    isOpen,
    onClose,
    workspaceId,
    workspaceName,
    onInvite
}) => {
    const [email, setEmail] = useState('');
    const [role, setRole] = useState('MEMBER');
    const [inviting, setInviting] = useState(false);
    const [inviteLink, setInviteLink] = useState('');
    const [copied, setCopied] = useState(false);
    const [errorMessage, setErrorMessage] = useState('');

    if (!isOpen) return null;

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!email.trim()) return;

        try {
            setInviting(true);
            setErrorMessage(''); // Clear any previous errors
            await onInvite(email.trim(), role);
            
            // Show success message
            setInviteLink('success');
            
            // Reset after 3 seconds
            setTimeout(() => {
                setEmail('');
                setInviteLink('');
                setErrorMessage('');
                onClose();
            }, 3000);
        } catch (error: any) {
            console.error('Error inviting member:', error);
            // Extract error message from various possible error structures
            const errorMsg = error?.message || 
                           error?.response?.error || 
                           error?.error || 
                           'Failed to send invitation. Please try again.';
            setErrorMessage(errorMsg);
        } finally {
            setInviting(false);
        }
    };

    const copyInviteLink = () => {
        if (inviteLink) {
            navigator.clipboard.writeText(inviteLink);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
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
                    <button
                        onClick={onClose}
                        className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
                    >
                        <X size={20} className="text-gray-500" />
                    </button>
                </div>

                {/* Body */}
                <form onSubmit={handleSubmit} className="p-6 space-y-4">
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                            Email Address *
                        </label>
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
                        <label className="block text-sm font-medium text-gray-700 mb-2">
                            Role
                        </label>
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
                                    <p className="text-sm font-medium text-red-800">
                                        Invitation Failed
                                    </p>
                                    <p className="text-xs text-red-700 mt-1">
                                        {errorMessage}
                                    </p>
                                </div>
                            </div>
                        </div>
                    )}

                    {inviteLink && (
                        <div className="bg-green-50 border border-green-200 rounded-lg p-4">
                            <div className="flex items-center gap-2">
                                <Check size={20} className="text-green-600" />
                                <div>
                                    <p className="text-sm font-medium text-green-800">
                                        Invitation sent successfully!
                                    </p>
                                    <p className="text-xs text-green-700 mt-1">
                                        An email with the invitation link has been sent to {email || 'the member'}.
                                    </p>
                                </div>
                            </div>
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
                            className="flex-1 px-4 py-2.5 bg-purple-600 text-white rounded-lg hover:bg-purple-700 font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                            disabled={inviting}
                        >
                            {inviting ? 'Sending...' : 'Send Invitation'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
};

export default InviteMemberModal;
