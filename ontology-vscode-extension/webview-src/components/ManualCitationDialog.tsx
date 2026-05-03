// ManualCitationDialog.tsx
import React, { useState } from 'react';
import { X, BookOpen, Save } from 'lucide-react';

interface ManualCitationDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (citation: {
    title: string;
    authors: string;
    year: string;
    doi?: string;
    url?: string;
    itemType: string;
    publicationTitle?: string;
  }) => void;
}

const ManualCitationDialog: React.FC<ManualCitationDialogProps> = ({ 
  isOpen, 
  onClose, 
  onSubmit 
}) => {
  const [title, setTitle] = useState('');
  const [authors, setAuthors] = useState('');
  const [year, setYear] = useState('');
  const [doi, setDoi] = useState('');
  const [doiError, setDoiError] = useState('');
  const [url, setUrl] = useState('');
  const [itemType, setItemType] = useState('journalArticle');
  const [publicationTitle, setPublicationTitle] = useState('');

  // Normalise and validate DOI — strips common URL/prefix wrappers
  const normalizeDoi = (raw: string): string | null => {
    const stripped = raw
      .trim()
      .replace(/^https?:\/\/(dx\.)?doi\.org\//i, '')
      .replace(/^doi:/i, '')
      .trim();
    // DOIs start with 10. followed by registrant and suffix
    const DOI_REGEX = /^10\.\d{4,9}\/.+/;
    return DOI_REGEX.test(stripped) ? stripped : null;
  };

  const handleSubmit = () => {
    if (!title.trim() || !authors.trim() || !year.trim()) {
      alert('Please fill in Title, Authors, and Year (required fields)');
      return;
    }

    // Validate DOI if provided
    let cleanDoi: string | undefined = undefined;
    if (doi.trim()) {
      const normalized = normalizeDoi(doi);
      if (!normalized) {
        setDoiError('Invalid DOI format. Expected format: 10.XXXX/suffix');
        return;
      }
      cleanDoi = normalized;
      setDoiError('');
    }

    onSubmit({
      title: title.trim(),
      authors: authors.trim(),
      year: year.trim(),
      doi: cleanDoi,
      url: url.trim() || undefined,
      itemType,
      publicationTitle: publicationTitle.trim() || undefined
    });

    // Reset form
    setTitle('');
    setAuthors('');
    setYear('');
    setDoi('');
    setDoiError('');
    setUrl('');
    setItemType('journalArticle');
    setPublicationTitle('');
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white rounded-lg shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-gray-200">
          <div className="flex items-center gap-2">
            <BookOpen className="text-purple-600" size={24} />
            <h2 className="text-xl font-bold text-gray-800">Add Citation Manually</h2>
          </div>
          <button
            onClick={onClose}
            className="p-1 hover:bg-gray-100 rounded-lg transition-colors"
          >
            <X size={20} className="text-gray-500" />
          </button>
        </div>

        {/* Form */}
        <div className="flex-1 overflow-y-auto p-6">
          <div className="space-y-4">
            {/* Title */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Title <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="Enter the publication title"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500"
              />
            </div>

            {/* Authors */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Authors <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={authors}
                onChange={(e) => setAuthors(e.target.value)}
                placeholder="e.g., John Doe, Jane Smith"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500"
              />
              <p className="text-xs text-gray-500 mt-1">Separate multiple authors with commas</p>
            </div>

            {/* Year */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Year <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={year}
                onChange={(e) => setYear(e.target.value)}
                placeholder="e.g., 2024"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500"
              />
            </div>

            {/* Item Type */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Publication Type
              </label>
              <select
                value={itemType}
                onChange={(e) => setItemType(e.target.value)}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500"
              >
                <option value="journalArticle">Journal Article</option>
                <option value="book">Book</option>
                <option value="bookSection">Book Chapter</option>
                <option value="conferencePaper">Conference Paper</option>
                <option value="thesis">Thesis</option>
                <option value="report">Report</option>
                <option value="webpage">Web Page</option>
                <option value="manuscript">Manuscript</option>
              </select>
            </div>

            {/* Publication Title */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Publication/Journal Name
              </label>
              <input
                type="text"
                value={publicationTitle}
                onChange={(e) => setPublicationTitle(e.target.value)}
                placeholder="e.g., Nature, IEEE Transactions"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500"
              />
            </div>

            {/* DOI */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                DOI (optional)
              </label>
              <input
                type="text"
                value={doi}
                onChange={(e) => { setDoi(e.target.value); setDoiError(''); }}
                placeholder="e.g., 10.1000/xyz123  or  https://doi.org/10.1000/xyz123"
                className={`w-full px-3 py-2 border rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500 ${
                  doiError ? 'border-red-400 bg-red-50' : 'border-gray-300'
                }`}
              />
              {doiError && (
                <p className="text-xs text-red-500 mt-1">{doiError}</p>
              )}
              <p className="text-xs text-gray-400 mt-1">You can paste a full DOI URL — it will be normalised automatically.</p>
            </div>

            {/* URL */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                URL (optional)
              </label>
              <input
                type="url"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder="https://example.com/article"
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-purple-500"
              />
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="p-4 border-t border-gray-200 bg-gray-50 flex justify-end gap-2">
          <button
            onClick={onClose}
            className="px-4 py-2 text-gray-700 bg-gray-200 rounded-lg hover:bg-gray-300 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors"
          >
            <Save size={18} />
            <span>Insert Citation</span>
          </button>
        </div>
      </div>
    </div>
  );
};

export default ManualCitationDialog;
