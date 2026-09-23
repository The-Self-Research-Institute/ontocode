import React from "react";

interface AskAiIconProps {
  size?: number;
  className?: string;
}

export const AskAiIcon: React.FC<AskAiIconProps> = ({ size = 20, className = "" }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2}
    strokeLinecap="round"
    strokeLinejoin="round"
    className={`ask-ai-bob ${className}`}
  >
    <path d="M12 8V4H8" />
    <rect width="16" height="12" x="4" y="8" rx="2" />
    <path d="M2 14h2" />
    <path d="M20 14h2" />
    <path className="ask-ai-eye" d="M9 13v2" />
    <path className="ask-ai-eye ask-ai-eye-right" d="M15 13v2" />
    <path
      className="ask-ai-sparkle"
      fill="#facc15"
      stroke="none"
      transform="translate(15.5 -0.5) scale(0.28)"
      d="M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z"
    />
  </svg>
);

export default AskAiIcon;
