import React from 'react';
import { MessageSquare } from 'lucide-react';

interface DetailsCommentsProps {
  details: any;
  newComment: string;
  setNewComment: (value: string) => void;
  onAdd: (changeId: string, text: string) => void;
}

const CommentList: React.FC<{ comments: any[] }> = ({ comments }) => (
  <div className="space-y-2 mb-3 max-h-40 overflow-y-auto">
    {comments.map((comment: any, idx: number) => (
      <div key={comment.id || idx} className="bg-white p-2 rounded border text-sm">
        <div className="flex items-center gap-2 text-gray-600 mb-1">
          <span className="font-medium">{comment.username || 'User'}</span>
          <span className="text-xs">
            {comment.timestamp ? new Date(comment.timestamp).toLocaleString() : ''}
          </span>
        </div>
        <p className="text-gray-700">{comment.text}</p>
      </div>
    ))}
  </div>
);

const DetailsComments: React.FC<DetailsCommentsProps> = ({ details, newComment, setNewComment, onAdd }) => (
  <div className="bg-gray-50 rounded-lg p-3">
    <h4 className="font-medium text-sm mb-2 flex items-center gap-1">
      <MessageSquare className="w-4 h-4" />
      Comments ({Array.isArray(details.comments) ? details.comments.length : 0})
    </h4>
    {Array.isArray(details.comments) && details.comments.length > 0 ? (
      <CommentList comments={details.comments} />
    ) : (
      <p className="text-sm text-gray-500 mb-3">No comments yet</p>
    )}
    <div className="border-t pt-2">
      <textarea
        value={newComment}
        onChange={(e) => setNewComment(e.target.value)}
        placeholder="Add a comment..."
        className="w-full border rounded p-2 text-sm h-16 resize-none bg-white text-black"
      />
      <button
        onClick={() => {
          if (details.id && newComment.trim()) {
            onAdd(details.id, newComment);
            setNewComment('');
          }
        }}
        disabled={!newComment.trim()}
        className="mt-2 px-3 py-1 text-sm bg-purple-600 text-white rounded hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed"
      >
        Add Comment
      </button>
    </div>
  </div>
);

export default DetailsComments;
