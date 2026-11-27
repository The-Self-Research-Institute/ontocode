import React, { useId } from 'react';

interface IriPreviewFieldProps {
  computedIri: string;
  isDuplicate: boolean;
  placeholder: string;
  onChangeIri?: (value: string) => void;
}

export const IriPreviewField: React.FC<IriPreviewFieldProps> = ({ computedIri, isDuplicate, placeholder, onChangeIri }) => {
  const id = useId();
  return (
  <div>
    <label htmlFor={id} className="font-medium text-black block mb-2">{onChangeIri ? 'IRI' : 'IRI Preview'}</label>
    <input
      id={id}
      type="text"
      readOnly={!onChangeIri}
      value={computedIri}
      placeholder={placeholder}
      onChange={onChangeIri ? event => onChangeIri(event.target.value) : undefined}
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
};
