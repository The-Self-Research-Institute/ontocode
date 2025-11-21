import React, { useState, useEffect, useRef } from 'react';

interface InlineRenameInputProps {
  initialValue: string;
  onConfirm: (newValue: string) => void;
  onCancel: () => void;
  className?: string;
}

/**
 * Inline rename input component for entity renaming
 * Supports Enter to confirm, Escape to cancel
 */
const InlineRenameInput: React.FC<InlineRenameInputProps> = ({
  initialValue,
  onConfirm,
  onCancel,
  className = ''
}) => {
  const [value, setValue] = useState(initialValue);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    // Focus and select all text when component mounts
    if (inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, []);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      e.stopPropagation();
      if (value.trim() && value !== initialValue) {
        onConfirm(value.trim());
      } else {
        onCancel();
      }
    } else if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      onCancel();
    }
  };

  const handleBlur = () => {
    // Confirm on blur if value changed
    if (value.trim() && value !== initialValue) {
      onConfirm(value.trim());
    } else {
      onCancel();
    }
  };

  return (
    <input
      ref={inputRef}
      type="text"
      value={value}
      onChange={(e) => setValue(e.target.value)}
      onKeyDown={handleKeyDown}
      onBlur={handleBlur}
      className={`px-2 py-0.5 text-xs border-2 border-purple-500 rounded bg-white focus:outline-none focus:ring-2 focus:ring-purple-300 ${className}`}
      onClick={(e) => e.stopPropagation()}
    />
  );
};

export default InlineRenameInput;
