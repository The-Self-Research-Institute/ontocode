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
    token?: string;
    onAccepted?: (workspaceData?: any) => void;
    onLoginRequired?: (email: string) => void;
    onSignupRequired?: (email: string) => void;
    onError?: () => void;
}

const InviteAcceptPage: React.FC<InviteAcceptPageProps> = ({ token: propToken, onAccepted, onLoginRequired, onSignupRequired, onError }) => {
    const [invitation, setInvitation] = useState<InvitationDetails | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [accepting, setAccepting] = useState(false);
    const [isExpired, setIsExpired] = useState(false);
    const [resending, setResending] = useState(false);
    
    const { user } = useAuth();
    
    // Get token from props or URL parameters
    const params = new URLSearchParams(window.location.search);
    const urlToken = params.get('token') || params.get('invite');
    const token = propToken || urlToken;

    useEffect(() => {
        console.log('[InviteAcceptPage] Component mounted, token:', token);
        if (token) {
            fetchInvitationDetails();
        } else {
            setError('Invalid invitation link - no token provided');
            setLoading(false);
        }
    }, [token]);

    const fetchInvitationDetails = async () => {
        console.log('[InviteAcceptPage] Fetching invitation details for token:', token);
        try {
            const response = await apiClient.get(`/api/invitations/details/${token}`);
            console.log('[InviteAcceptPage] Invitation details loaded:', response);
            
            // Check if response is valid
            if (!response) {
                throw new Error('No response from server');
            }
            
            // apiClient.get already returns response.data, so access invitation directly
            const invData = response?.invitation || response;
            
            if (!invData || !invData.inviteeEmail) {
                throw new Error('Invalid invitation data');
            }
            
            setInvitation(invData);
            
            // Check response status flags from backend
            if (response?.expired) {
                setIsExpired(true);
                setError(response?.message || 'This invitation has expired.');
            } else if (response?.alreadyAccepted) {
                // Invitation was already accepted - show success message and redirect
                console.log('[InviteAcceptPage] Invitation already accepted, redirecting to workspace');
                if (onAccepted) {
                    onAccepted({
                        workspaceId: invData.workspaceId,
                        workspaceName: invData.workspaceName,
                        message: 'You have already joined this workspace'
                    });
                }
                return;
            } else if (response?.cancelled) {
                setError(response?.message || 'This invitation has been cancelled.');
            } else if (invData.expiresAt && new Date(invData.expiresAt) < new Date()) {
                setIsExpired(true);
                setError('This invitation has expired.');
            }
        } catch (err: any) {
            console.error('[InviteAcceptPage] Error loading invitation:', err);
            // Handle error message from various sources
            const errorMsg = err?.error || err?.response?.data?.error || err?.message || 'Failed to load invitation details';
            if (errorMsg.toLowerCase().includes('expired')) {
                setIsExpired(true);
            }
            setError(errorMsg);
        } finally {
            setLoading(false);
        }
    };

    const handleResendInvitation = async () => {
        if (!token) return;
        
        try {
            setResending(true);
            console.log('[InviteAcceptPage] Requesting invitation resend for token');
            
            // Use the public endpoint that doesn't require authentication
            const response = await apiClient.post(`/api/invitations/request-resend/${token}`);
            
            setIsExpired(false);
            
            // Note: The token will be different now, user needs to use the new link from email
            // Show a message to user to check their email for the new link
            setError('A new invitation link has been sent to your email. Please check your inbox and use the new link.');
        } catch (err: any) {
            console.error('[InviteAcceptPage] Error resending invitation:', err);
            const errorMsg = err?.error || err?.message || 'Failed to resend invitation. Please contact the workspace owner.';
            setError(errorMsg);
        } finally {
            setResending(false);
        }
    };

    const handleAcceptInvitation = async () => {
        console.log('[InviteAcceptPage] Accept button clicked, user:', user ? 'logged in' : 'not logged in');
        
        if (!user) {
            // User not logged in - trigger callback to show signup (new users need to create account)
            console.log('[InviteAcceptPage] User not logged in, showing signup form');
            if (onSignupRequired) {
                onSignupRequired(invitation?.inviteeEmail || '');
            } else if (onLoginRequired) {
                // Fallback to login if signup callback not provided
                onLoginRequired(invitation?.inviteeEmail || '');
            }
            return;
        }

        try {
            setAccepting(true);
            console.log('[InviteAcceptPage] Accepting invitation with token:', token);
            const response = await apiClient.post(`/api/invitations/accept/${token}`);
            
            console.log('[InviteAcceptPage] Invitation accepted successfully:', response);
            
            // Trigger callback to refresh app state with workspace data
            if (onAccepted) {
                onAccepted(response);
            }
        } catch (err: any) {
            console.error('[InviteAcceptPage] Error accepting invitation:', err);
            // Handle error message from various sources
            const errorMessage = err?.error || err?.data?.error || err?.message || 'Failed to accept invitation';
            
            // Check if user is already a member - treat this as success
            if (errorMessage.toLowerCase().includes('already a member')) {
                console.log('[InviteAcceptPage] User is already a member, treating as success');
                // Create a mock successful response with workspace info
                const successResponse = {
                    workspaceId: invitation?.workspaceId,
                    workspaceName: invitation?.workspaceName,
                    message: 'You are already a member of this workspace'
                };
                if (onAccepted) {
                    onAccepted(successResponse);
                }
                return;
            }
            
            setError(errorMessage);
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
                        </div>
                        <h2 className="text-2xl font-bold text-gray-900 mb-2">
                            {isExpired ? 'Invitation Expired' : 'Invalid Invitation'}
                        </h2>
                        <p className="text-gray-600 mb-6">{error}</p>
                        <div className="flex flex-col gap-3">
                            {isExpired && invitation?.inviteeEmail && (
                                <button
                                    onClick={handleResendInvitation}
                                    disabled={resending}
                                    className="w-full px-6 py-3 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {resending ? 'Resending...' : 'Resend Invitation'}
                                </button>
                            )}
                            <button
                                onClick={() => { onError && onError(); }}
                                className="w-full px-6 py-3 border border-gray-300 text-gray-700 rounded-lg hover:bg-gray-50 transition-colors"
                            >
                                Go to Login
                            </button>
                        </div>
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
