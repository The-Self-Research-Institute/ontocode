import React from 'react';
import { MousePointer2 } from 'lucide-react';

interface CollaborativeCursorProps {
  userId: string;
  userName: string;
  position: { x: number; y: number };
  color: string;
  isActive?: boolean;
}

/**
 * CollaborativeCursor - Displays a collaborator's cursor with their name
 * Similar to Figma's collaborative cursors
 */
export const CollaborativeCursor: React.FC<CollaborativeCursorProps> = ({
  userId,
  userName,
  position,
  color,
  isActive = true
}) => {
  if (!isActive) return null;

  return (
    <div
      style={{
        position: 'fixed',
        left: position.x,
        top: position.y,
        pointerEvents: 'none',
        zIndex: 10000,
        transform: 'translate(-2px, -2px)',
        transition: 'left 0.1s ease-out, top 0.1s ease-out',
      }}
    >
      {/* Cursor pointer */}
      <svg
        width="24"
        height="24"
        viewBox="0 0 24 24"
        fill="none"
        style={{
          filter: 'drop-shadow(0 2px 4px rgba(0,0,0,0.3))',
        }}
      >
        <path
          d="M5.5 3.21V20.79L12.5 13.79L16.5 19.79L19.5 18.29L15.5 12.29L23.5 11.79L5.5 3.21Z"
          fill={color}
          stroke="white"
          strokeWidth="1.5"
          strokeLinejoin="round"
        />
      </svg>

      {/* User name label */}
      <div
        style={{
          position: 'absolute',
          left: '20px',
          top: '4px',
          backgroundColor: color,
          color: 'white',
          padding: '2px 8px',
          borderRadius: '4px',
          fontSize: '11px',
          fontWeight: 500,
          whiteSpace: 'nowrap',
          boxShadow: '0 2px 8px rgba(0,0,0,0.15)',
          fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        }}
      >
        {userName}
      </div>
    </div>
  );
};

interface CollaborativeCursorsProps {
  cursors: Map<string, { x: number; y: number; userName: string; color: string; timestamp: number }>;
}

/**
 * CollaborativeCursors - Renders all active collaborative cursors
 */
export const CollaborativeCursors: React.FC<CollaborativeCursorsProps> = ({ cursors }) => {
  return (
    <>
      {Array.from(cursors.entries()).map(([userId, cursor]) => (
        <CollaborativeCursor
          key={userId}
          userId={userId}
          userName={cursor.userName}
          position={{ x: cursor.x, y: cursor.y }}
          color={cursor.color}
          isActive={Date.now() - cursor.timestamp < 5000} // Hide cursor after 5 seconds of inactivity
        />
      ))}
    </>
  );
};
