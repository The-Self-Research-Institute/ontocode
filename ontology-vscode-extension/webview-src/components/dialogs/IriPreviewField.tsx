import React from 'react';

interface IriPreviewFieldProps {
  computedIri: string;
  isDuplicate: boolean;
  placeholder: string;
}

export const IriPreviewField: React.FC<IriPreviewFieldProps> = ({ computedIri, isDuplicate, placeholder }) => (
  <div>
    <label className="font-medium text-black block mb-2">IRI Preview</label>
    <input
      type="text"
      disabled
      value={computedIri || placeholder}
      style={{ direction: 'rtl', textAlign: 'left' }}
      className={`w-full px-3 py-2 border rounded-md text-xs ${
        isDuplicate
          ? 'border-red-300 bg-red-50 text-red-700'
          : 'border-gray-200 bg-gray-50 text-gray-500'
      }`}
    />
    {isDuplicate && (
      <p className="text-xs text-red-600 mt-1">
        Entity already exists: <span className="font-mono break-all">{computedIri}</span>
      </p>
    )}
  </div>
);
