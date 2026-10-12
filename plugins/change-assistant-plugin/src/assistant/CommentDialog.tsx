import React from 'react';

interface CommentDialogProps {
  newComment: string;
  setNewComment: (value: string) => void;
  onAdd: () => void;
  onCancel: () => void;
}

const CommentDialog: React.FC<CommentDialogProps> = ({ newComment, setNewComment, onAdd, onCancel }) => (
  <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
    <div className="bg-white rounded-lg p-4 w-96">
      <h3 className="font-medium mb-3">Add Comment</h3>
      <textarea
        value={newComment}
        onChange={(e) => setNewComment(e.target.value)}
        placeholder="Enter your comment..."
        className="w-full border rounded p-2 text-sm h-24 resize-none bg-white text-black"
      />
      <div className="flex gap-2 mt-3">
        <button
          onClick={onAdd}
          className="flex-1 px-4 py-2 bg-purple-600 text-white rounded hover:bg-purple-700"
        >
          Add Comment
        </button>
        <button
          onClick={() => onCancel()}
          className="px-4 py-2 border rounded hover:bg-gray-50"
        >
          Cancel
        </button>
      </div>
    </div>
  </div>
);

export default CommentDialog;
