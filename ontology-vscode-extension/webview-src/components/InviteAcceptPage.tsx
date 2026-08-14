import React, { useEffect, useState } from 'react';
import { useAuth } from '../custom-hook/useAuth';
import apiClient from '../services/apiClient';
import { Bug } from 'lucide-react';
import ReportIssueModal from './ReportIssueModal';

interface InvitationDetails {
    invitationToken: string;
    inviteeEmail: string;
    workspaceId: string;
    workspaceName: string;
    invitedBy: string;
    invitedByEmail: string;
    role: string;
    status: string;
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
    const [isReportIssueModalOpen, setIsReportIssueModalOpen] = useState(false);

    const { user } = useAuth();

    const params = new URLSearchParams(window.location.search);
    const urlToken = params.get('token') || params.get('invite');
    const token = propToken || urlToken;

    useEffect(() => {
        if (token) {
            fetchInvitationDetails();
        } else {
            setError('Invalid invitation link - no token provided');
            setLoading(false);
        }
    }, [token]);

    const fetchInvitationDetails = async () => {
        try {
            const response = await apiClient.get(`/api/invitations/details/${token}`);

            if (!response) {
                throw new Error('No response from server');
            }

            const invData = response?.invitation || response;

            if (!invData || !invData.inviteeEmail) {
                throw new Error('Invalid invitation data');
            }

            setInvitation(invData);

            if (response?.alreadyAccepted) {

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
            }
        } catch (err: any) {
            console.error('[InviteAcceptPage] Error loading invitation:', err);

            if (err?.status === 404 || err?.response?.status === 404) {
                setError('This invitation link is invalid or has been removed. Please contact the workspace owner to request a new invitation.');
            } else if (err?.code === 'ECONNREFUSED' || err?.code === 'ERR_NETWORK' || err?.message?.includes('Network Error') || err?.message?.includes('Failed to fetch')) {

                setError('Unable to connect to the server. Please make sure the OntoCode Studio services are running and try again.');
            } else if (err?.code === 'TIMEOUT' || err?.message?.includes('timeout')) {
                setError('Connection timed out. Please check your network connection and try again.');
            } else {
                const errorMsg = err?.error || err?.response?.data?.error || err?.message || 'Failed to load invitation details';
                setError(errorMsg);
            }
        } finally {
            setLoading(false);
        }
    };

    const handleAcceptInvitation = async () => {

        if (!user) {

            if (onSignupRequired) {
                onSignupRequired(invitation?.inviteeEmail || '');
            } else if (onLoginRequired) {

                onLoginRequired(invitation?.inviteeEmail || '');
            }
            return;
        }

        try {
            setAccepting(true);
            const response = await apiClient.post(`/api/invitations/accept/${token}`);

            const workspaceId = response.workspaceId || invitation?.workspaceId;

            const selectResponse = await apiClient.post(`/api/workspaces/${workspaceId}/select`);

            if (onAccepted) {
                onAccepted({
                    ...response,
                    ...selectResponse,
                    workspaceId: selectResponse.workspaceId,
                    workspaceName: selectResponse.workspaceName,
                    jwt: selectResponse.jwt
                });
            }
        } catch (err: any) {
            console.error('[InviteAcceptPage] Error accepting invitation:', err);

            const errorMessage = err?.error || err?.data?.error || err?.message || 'Failed to accept invitation';

            if (errorMessage.toLowerCase().includes('already a member')) {

                const workspaceId = invitation?.workspaceId;
                try {
                    const selectResponse = await apiClient.post(`/api/workspaces/${workspaceId}/select`);

                    const successResponse = {
                        workspaceId: workspaceId,
                        workspaceName: invitation?.workspaceName,
                        message: 'You are already a member of this workspace',
                        jwt: selectResponse.jwt,
                        ...selectResponse
                    };
                    if (onAccepted) {
                        onAccepted(successResponse);
                    }
                    return;
                } catch (selectErr: any) {
                    console.error('[InviteAcceptPage] Error getting workspace JWT:', selectErr);
                    setError('Already a member but failed to load workspace');
                    return;
                }
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
            <div className="min-h-screen overflow-y-auto flex items-center justify-center bg-gradient-to-br from-purple-50 to-blue-50 p-6">
                <div className="text-center">
                    <div className="animate-spin rounded-full h-16 w-16 border-t-4 border-purple-600 mx-auto"></div>
                    <p className="mt-4 text-gray-600">Loading invitation...</p>
                </div>
            </div>
        );
    }

    if (error) {
        return (
            <div className="min-h-screen overflow-y-auto flex items-center justify-center bg-gradient-to-br from-purple-50 to-blue-50 p-6">
                <div className="max-w-md w-full bg-white rounded-xl shadow-lg p-8">
                    <div className="text-center">
                        <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-4">
                            <svg className="w-8 h-8 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                            </svg>
                        </div>
                        <h2 className="text-2xl font-bold text-gray-900 mb-2">Invalid Invitation</h2>
                        <p className="text-gray-600 mb-6">{error}</p>
                        <div className="flex flex-col gap-3">
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
        <div className="min-h-screen overflow-y-auto flex items-center justify-center bg-gradient-to-br from-purple-50 to-blue-50 p-6">
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

                    </div>

                    {user ? (
                        <button
                            onClick={handleAcceptInvitation}
                            disabled={accepting}
                            className="w-full px-6 py-3 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors font-medium disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {accepting ? 'Accepting...' : 'Accept Invitation'}
                        </button>
                    ) : (
                        <div className="flex flex-col gap-3">
                            <button
                                onClick={() => onLoginRequired && onLoginRequired(invitation?.inviteeEmail || '')}
                                className="w-full px-6 py-3 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors font-medium"
                            >
                                Login & Accept
                            </button>
                            <button
                                onClick={handleAcceptInvitation}
                                className="w-full px-6 py-3 border border-purple-300 text-purple-700 rounded-lg hover:bg-purple-50 transition-colors font-medium"
                            >
                                Sign Up & Accept
                            </button>
                        </div>
                    )}
                </div>
            </div>

            {}
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

export default InviteAcceptPage;
