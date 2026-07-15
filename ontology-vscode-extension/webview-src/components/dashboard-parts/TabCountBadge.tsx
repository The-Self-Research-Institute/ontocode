import React from "react";
import { Loader2 } from "lucide-react";

type TabCountBadgeProps = {
  loading: boolean;
  count: number;
};

/** Tab count with a short crossfade between spinner and numeric badge. */
export const TabCountBadge: React.FC<TabCountBadgeProps> = ({ loading, count }) => (
  <span className="inline-flex items-center justify-center min-w-[1.25rem] h-5">
    {loading ? (
      <Loader2
        size={12}
        className="animate-spin text-purple-500 transition-opacity duration-200 opacity-100"
        aria-hidden
      />
    ) : (
      <span
        className="bg-gray-200 text-gray-700 px-1.5 py-0.5 rounded-sm font-bold transition-opacity duration-200 opacity-100"
        aria-label={`${count} items`}
      >
        {count || 0}
      </span>
    )}
  </span>
);
