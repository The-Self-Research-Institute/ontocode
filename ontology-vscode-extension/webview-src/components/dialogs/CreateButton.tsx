import React from 'react';

interface CreateButtonProps {
  onClick: () => void;
  disabled: boolean;
  children?: React.ReactNode;
}

export const CreateButton: React.FC<CreateButtonProps> = ({ onClick, disabled, children = 'Create' }) => (
  <button
    onClick={onClick}
    disabled={disabled}
    className={`px-4 py-2 text-sm rounded-md ${
      disabled
        ? 'bg-gray-300 text-gray-500 cursor-not-allowed'
        : 'bg-blue-600 text-white hover:bg-blue-700'
    }`}
  >
    {children}
  </button>
);
