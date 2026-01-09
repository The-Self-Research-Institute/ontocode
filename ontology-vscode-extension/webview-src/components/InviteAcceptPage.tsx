import React, { useEffect, useState } from 'react';
import { useAuth } from '../custom-hook/useAuth';
import apiClient from '../services/apiClient';

interface InvitationDetails {
    invitationToken: string;
    inviteeEmail: string;
    workspaceId: string;
    workspaceName: string;
    invitedBy: string;
    invitedByEmail: string;
    role: string;
    status: string;
    expiresAt: string;
}

interface InviteAcceptPageProps {
    onAccepted?: () => void;
    onLoginRequired?: (email: string) => void;
    onError?: () => void;
}

const InviteAcceptPage: React.FC<InviteAcceptPageProps> = ({ onAccepted, onLoginRequired, onError }) => {
    const [invitation, setInvitation] = useState<InvitationDetails | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [accepting, setAccepting] = useState(false);
    
    const { user } = useAuth();
    
    // Get token from URL parameters
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token') || params.get('invite');

    useEffect(() => {
        if (token) {
            fetchInvitationDetails();
        } else {
            setError('Invalid invitation link');
            setLoading(false);
        }
    }, [token]);

    const fetchInvitationDetails = async () => {
        try {
            const response = await apiClient.get(`/api/invitations/details/${token}`);
            setInvitation(response.data.invitation);
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to load invitation details');
        } finally {
            setLoading(false);
        }
    };

    const handleAcceptInvitation = async () => {
        if (!user) {
            // User not logged in - trigger callback to show login
            if (onLoginRequired) {
                onLoginRequired(invitation?.inviteeEmail || '');
            }
            return;
        }

        try {
            setAccepting(true);
            const response = await apiClient.post(`/api/invitations/accept/${token}`);
            
            // Show success message briefly
            alert(`Welcome to ${response.data.workspaceName}! You've successfully joined the workspace.`);
            
            // Trigger callback to refresh app state
            if (onAccepted) {
                onAccepted();
            }
        } catch (err: any) {
            setError(err.response?.data?.error || 'Failed to accept invitation');
            if (onError) {
                onError();
            }
        } finally {
            setAccepting(false);
        }
    };

    if (loading) {
        return (
            <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-purple-50 to-blue-50">
                <div className="text-center">
                    <div className="animate-spin rounded-full h-16 w-16 border-t-4 border-purple-600 mx-auto"></div>
                    <p className="mt-4 text-gray-600">Loading invitation...</p>
                </div>
            </div>
        );
    }

    if (error) {
        return (
            <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-purple-50 to-blue-50 p-6">
                <div className="max-w-md w-full bg-white rounded-xl shadow-lg p-8">
                    <div className="text-center">
                        <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4">
                            <svg className="w-8 h-8 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                            </svg>
                        </div>onError && onError()
                        <h2 className="text-2xl font-bold text-gray-900 mb-2">Invalid Invitation</h2>
                        <p className="text-gray-600 mb-6">{error}</p>
                        <button
                            onClick={() => window.location.href = '/'}
                            className="px-6 py-3 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors"
                        >
                            Go to Login
                        </button>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-purple-50 to-blue-50 p-6">
            <div className="max-w-md w-full bg-white rounded-xl shadow-lg overflow-hidden">
                <div className="bg-gradient-to-r from-purple-600 to-blue-600 p-8 text-white">
                    <div className="text-center">
                        <div className="w-16 h-16 bg-white/20 rounded-full flex items-center justify-center mx-auto mb-4">
                            <svg className="w-8 h-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
                            </svg>
                        </div>
                        <h1 className="text-2xl font-bold">You're Invited!</h1>
                    </div>
                </div>

                <div className="p-8">
                    <div className="space-y-4 mb-6">
                        <div>
                            <p className="text-sm text-gray-500">From</p>
                            <p className="text-lg font-semibold text-gray-900">{invitation?.invitedBy}</p>
                            <p className="text-sm text-gray-600">{invitation?.invitedByEmail}</p>
                        </div>

                        <div>
                            <p className="text-sm text-gray-500">Workspace</p>
                            <p className="text-lg font-semibold text-gray-900">{invitation?.workspaceName}</p>
                        </div>

                        <div>
                            <p className="text-sm text-gray-500">Your Role</p>
                            <span className="inline-block mt-1 px-3 py-1 bg-purple-100 text-purple-800 rounded-full text-sm font-medium">
                                {invitation?.role}
                            </span>
                        </div>

                        <div>
                            <p className="text-sm text-gray-500">Email</p>
                            <p className="text-sm text-gray-900">{invitation?.inviteeEmail}</p>
                        </div>

                        <div className="pt-4 border-t">
                            <p className="text-xs text-gray-500">
                                This invitation expires on {invitation?.expiresAt ? new Date(invitation.expiresAt).toLocaleDateString() : 'N/A'}
                            </p>
                        </div>
                    </div>

                    <button
                        onClick={handleAcceptInvitation}
                        disabled={accepting}
                        className="w-full px-6 py-3 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors font-medium disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {accepting ? 'Accepting...' : user ? 'Accept Invitation' : 'Sign Up & Accept'}
                    </button>

                    {!user && (
                        <p className="text-center text-sm text-gray-600 mt-4">
                            Already have an account?{' '}
                            <button
                                onClick={() => onLoginRequired && onLoginRequired(invitation?.inviteeEmail || '')}
                                className="text-purple-600 hover:text-purple-700 font-medium"
                            >
                                Login
                            </button>
                        </p>
                    )}
                </div>
            </div>
        </div>
    );
};

export default InviteAcceptPage;
