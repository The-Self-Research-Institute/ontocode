import React, { useState } from 'react';
import { Star, ThumbsUp } from 'lucide-react';

interface RatingModalProps {
  pluginId: string;
  pluginName: string;
  currentRating?: {
    stars: number;
    review?: string;
    merits?: string;
    demerits?: string;
    recommended?: boolean;
  };
  onClose: () => void;
  onSubmit: (rating: {
    stars: number;
    review?: string;
    merits?: string;
    demerits?: string;
    recommended?: boolean;
  }) => Promise<void>;
}

export const RatingModal: React.FC<RatingModalProps> = ({
  pluginId,
  pluginName,
  currentRating,
  onClose,
  onSubmit
}) => {
  const [stars, setStars] = useState(currentRating?.stars || 0);
  const [hoveredStars, setHoveredStars] = useState(0);
  const [review, setReview] = useState(currentRating?.review || '');
  const [merits, setMerits] = useState(currentRating?.merits || '');
  const [demerits, setDemerits] = useState(currentRating?.demerits || '');
  const [recommended, setRecommended] = useState(currentRating?.recommended ?? true);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async () => {
    if (stars === 0) {
      alert('Please select a star rating');
      return;
    }

    setSubmitting(true);
    try {
      await onSubmit({
        stars,
        review: review.trim() || undefined,
        merits: merits.trim() || undefined,
        demerits: demerits.trim() || undefined,
        recommended
      });
      onClose();
    } catch (error) {
      console.error('Failed to submit rating:', error);
      alert('Failed to submit rating. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="border-b border-gray-200 px-6 py-4">
          <h2 className="text-xl font-semibold text-gray-900">
            Rate {pluginName}
          </h2>
        </div>

        {/* Content */}
        <div className="px-6 py-4 space-y-6">
          {/* Star Rating */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Your Rating *
            </label>
            <div className="flex items-center gap-2">
              {[1, 2, 3, 4, 5].map((star) => (
                <button
                  key={star}
                  type="button"
                  onClick={() => setStars(star)}
                  onMouseEnter={() => setHoveredStars(star)}
                  onMouseLeave={() => setHoveredStars(0)}
                  className="focus:outline-none transition-transform hover:scale-110"
                >
                  <Star
                    size={32}
                    className={`${
                      star <= (hoveredStars || stars)
                        ? 'fill-yellow-400 text-yellow-400'
                        : 'text-gray-300'
                    }`}
                  />
                </button>
              ))}
              <span className="ml-2 text-sm text-gray-600">
                {stars > 0 ? `${stars} star${stars > 1 ? 's' : ''}` : 'Click to rate'}
              </span>
            </div>
          </div>

          {/* Recommendation */}
          <div>
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={recommended}
                onChange={(e) => setRecommended(e.target.checked)}
                className="w-4 h-4 text-purple-600 rounded focus:ring-purple-500"
              />
              <ThumbsUp size={16} className="text-gray-600" />
              <span className="text-sm font-medium text-gray-700">
                I would recommend this plugin
              </span>
            </label>
          </div>

          {/* Review */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Review (Optional)
            </label>
            <textarea
              value={review}
              onChange={(e) => setReview(e.target.value)}
              rows={4}
              placeholder="Share your experience with this plugin..."
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400"
            />
          </div>

          {/* Merits */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              What do you like? (Optional)
            </label>
            <textarea
              value={merits}
              onChange={(e) => setMerits(e.target.value)}
              rows={3}
              placeholder="What are the plugin's strengths?"
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400"
            />
          </div>

          {/* Demerits */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              What could be improved? (Optional)
            </label>
            <textarea
              value={demerits}
              onChange={(e) => setDemerits(e.target.value)}
              rows={3}
              placeholder="Any areas for improvement?"
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400"
            />
          </div>
        </div>

        {/* Footer */}
        <div className="border-t border-gray-200 px-6 py-4 flex justify-end gap-3">
          <button
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting || stars === 0}
            className="px-4 py-2 text-sm font-medium text-white bg-purple-600 rounded-md hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {submitting ? 'Submitting...' : currentRating ? 'Update Rating' : 'Submit Rating'}
          </button>
        </div>
      </div>
    </div>
  );
};
