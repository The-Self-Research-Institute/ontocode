import React from "react";
import { XCircle } from "lucide-react";

interface DashboardLoadErrorProps {
  loadError: string;
  isOwner: boolean;
  onRetry: () => void;
  onSwitchWorkspace: () => void;
  onManageSubscription?: () => void;
  onLogout: () => void;
}

const DashboardLoadError: React.FC<DashboardLoadErrorProps> = ({
  loadError,
  isOwner,
  onRetry,
  onSwitchWorkspace,
  onManageSubscription,
  onLogout,
}) => (
  <div className="min-h-screen flex items-center justify-center bg-gray-50 p-6">
    <div className="text-center max-w-md">
      <XCircle size={48} className="text-red-400 mx-auto mb-4" />
      <p className="text-gray-800 font-semibold mb-2">Couldn't open this workspace</p>
      <p className="text-gray-600 mb-6 text-sm leading-relaxed">{loadError}</p>
      <div className="flex flex-wrap justify-center gap-3">
        <button
          onClick={onRetry}
          className="px-5 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors"
        >
          Retry
        </button>
        <button
          onClick={onSwitchWorkspace}
          className="px-5 py-2 bg-white border border-gray-300 text-gray-700 rounded-lg hover:bg-gray-100 transition-colors"
        >
          Switch Workspace
        </button>
        {isOwner && onManageSubscription && (
          <button
            onClick={onManageSubscription}
            className="px-5 py-2 bg-emerald-600 text-white rounded-lg hover:bg-emerald-700 transition-colors"
          >
            Manage Subscription
          </button>
        )}
        <button
          onClick={onLogout}
          className="px-5 py-2 border border-gray-300 text-gray-500 rounded-lg hover:bg-gray-100 transition-colors text-sm"
        >
          Log out
        </button>
      </div>
    </div>
  </div>
);

export default DashboardLoadError;
