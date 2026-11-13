import React from "react";

interface FreePlanDialogProps {
  onClose: () => void;
}

const FreePlanDialog: React.FC<FreePlanDialogProps> = ({ onClose }) => (
  <div
    className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[9999]"
    onClick={onClose}
  >
    <div
      className="relative bg-white rounded-2xl shadow-2xl w-[420px] max-w-[92vw] overflow-hidden"
      onClick={(e) => e.stopPropagation()}
    >
      <div className="h-1.5 w-full bg-gradient-to-r from-violet-500 via-purple-500 to-indigo-500" />
      <div className="px-6 pt-5 pb-4 flex items-start gap-4">
        <div className="flex-shrink-0 w-11 h-11 rounded-xl bg-amber-50 border border-amber-200 flex items-center justify-center">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-amber-500">
            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
            <circle cx="12" cy="12" r="3"/>
            <line x1="2" y1="2" x2="22" y2="22"/>
          </svg>
        </div>
        <div className="flex-1 min-w-0">
          <h3 className="text-[15px] font-semibold text-gray-900 leading-tight">Project Creation Not Available</h3>
          <p className="text-xs text-gray-400 mt-0.5">
            Your workspace is on the <span className="font-medium text-gray-500">Free plan</span>
          </p>
        </div>
        <button
          onClick={onClose}
          className="flex-shrink-0 text-gray-400 hover:text-gray-600 transition-colors p-1 rounded-lg hover:bg-gray-100 -mt-1 -mr-1"
          aria-label="Close"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>
      <div className="px-6 pb-5">
        <div className="bg-gray-50 rounded-xl border border-gray-100 px-4 py-3.5 mb-4 text-sm text-gray-600 leading-relaxed">
          Creating new projects requires a <span className="font-medium text-gray-800">Pro plan</span>. Only the workspace owner can create projects on the Free plan.
        </div>
        <div className="flex items-start gap-2.5 text-sm text-gray-600">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="flex-shrink-0 mt-0.5 text-violet-500">
            <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>
          </svg>
          <span>Ask your <span className="font-medium text-gray-800">workspace owner</span> to upgrade to Pro to unlock project creation for all members.</span>
        </div>
      </div>
      <div className="px-6 pb-5 flex justify-end">
        <button
          onClick={onClose}
          className="px-5 py-2 text-sm font-medium rounded-lg bg-gray-900 text-white hover:bg-gray-700 transition-colors"
        >
          Got it
        </button>
      </div>
    </div>
  </div>
);

export default FreePlanDialog;
