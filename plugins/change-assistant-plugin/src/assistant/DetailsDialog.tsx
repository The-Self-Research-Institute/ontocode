import React from 'react';
import { FileText, RefreshCw, X } from 'lucide-react';
import DetailsComments from './DetailsComments';
import { ChangeInfo, ValueChanges } from './DetailsSummary';

interface DetailsDialogProps {
  loading: boolean;
  details: any;
  newComment: string;
  setNewComment: (value: string) => void;
  onAddComment: (changeId: string, text: string) => void;
  onClose: () => void;
}

const DetailsBody: React.FC<Omit<DetailsDialogProps, 'loading' | 'onClose'>> = ({ details, newComment, setNewComment, onAddComment }) => (
  <div className="flex-1 overflow-auto space-y-4">
    <ChangeInfo details={details} />
    {(details.oldValue || details.newValue) && <ValueChanges details={details} />}
    {details.description && (
      <div className="bg-gray-50 rounded-lg p-3">
        <h4 className="font-medium text-sm mb-1">Description</h4>
        <p className="text-sm text-gray-600">{details.description}</p>
      </div>
    )}
    <DetailsComments details={details} newComment={newComment} setNewComment={setNewComment} onAdd={onAddComment} />
  </div>
);

const DetailsDialog: React.FC<DetailsDialogProps> = ({ loading, onClose, ...body }) => (
  <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
    <div className="bg-white rounded-lg p-4 w-[500px] max-h-[80vh] overflow-hidden flex flex-col">
      <div className="flex items-center justify-between mb-3">
        <h3 className="font-medium flex items-center gap-2">
          <FileText className="w-5 h-5 text-purple-600" />
          Change Details
        </h3>
        <button onClick={() => onClose()} className="text-gray-500 hover:text-gray-700">
          <X className="w-5 h-5" />
        </button>
      </div>
      {loading ? (
        <div className="py-8 text-center text-gray-500">
          <RefreshCw className="w-6 h-6 animate-spin mx-auto mb-2" />
          <p>Loading details...</p>
        </div>
      ) : body.details ? (
        <DetailsBody {...body} />
      ) : (
        <div className="py-8 text-center text-gray-500">
          <p>No details available</p>
        </div>
      )}
      <div className="mt-4 pt-3 border-t flex justify-end">
        <button onClick={() => onClose()} className="px-4 py-2 border rounded hover:bg-gray-50">
          Close
        </button>
      </div>
    </div>
  </div>
);

export default DetailsDialog;
