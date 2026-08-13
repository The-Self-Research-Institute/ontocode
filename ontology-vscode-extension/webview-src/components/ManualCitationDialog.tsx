
import React, { useCallback, useEffect, useState } from 'react';
import { X, BookOpen, Save } from 'lucide-react';
import { isValidDoiFormat, normalizeDoi as normalizeDoiUtil } from '../utils/doi';
import { validateDoiOnline } from '../services/doiValidationService';

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
  }) => Promise<void> | void;
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
  const [doiChecking, setDoiChecking] = useState(false);
  const [url, setUrl] = useState('');
  const [itemType, setItemType] = useState('journalArticle');
  const [publicationTitle, setPublicationTitle] = useState('');

  const resetForm = useCallback(() => {
    setTitle('');
    setAuthors('');
    setYear('');
    setDoi('');
    setDoiError('');
    setDoiChecking(false);
    setUrl('');
    setItemType('journalArticle');
    setPublicationTitle('');
  }, []);

  useEffect(() => {
    if (!isOpen) {
      resetForm();
    }
  }, [isOpen, resetForm]);

  const handleClose = () => {
    resetForm();
    onClose();
  };

  const handleSubmit = async () => {
    if (!title.trim() || !authors.trim() || !year.trim()) {
      alert('Please fill in Title, Authors, and Year (required fields)');
      return;
    }

    let cleanDoi: string | undefined = undefined;
    if (doi.trim()) {
      const normalized = normalizeDoiUtil(doi);
      if (!isValidDoiFormat(normalized)) {
        setDoiError('Invalid DOI format. Expected format: 10.XXXX/suffix');
        return;
      }
      setDoiChecking(true);
      try {
        const validation = await validateDoiOnline({
          doi: normalized,
          title: title.trim(),
          publicationTitle: publicationTitle.trim() || undefined,
          year: year.trim(),
        });

        if (!validation.valid) {
          setDoiError(validation.error || 'DOI could not be validated.');
          return;
        }

        if (validation.relevant === false) {
          setDoiError(validation.error || 'DOI is real but does not match this citation.');
          return;
        }

        cleanDoi = validation.normalizedDoi || normalized;
        setDoiError('');
      } finally {
        setDoiChecking(false);
      }
    }

    await onSubmit({
      title: title.trim(),
      authors: authors.trim(),
      year: year.trim(),
      doi: cleanDoi,
      url: url.trim() || undefined,
      itemType,
      publicationTitle: publicationTitle.trim() || undefined
    });

    resetForm();
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white rounded-lg shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col">
        {}
        <div className="flex items-center justify-between p-4 border-b border-gray-200">
          <div className="flex items-center gap-2">
            <BookOpen className="text-purple-600" size={24} />
            <h2 className="text-xl font-bold text-gray-800">Add Citation Manually</h2>
          </div>
          <button
            onClick={handleClose}
            className="p-1 hover:bg-gray-100 rounded-lg transition-colors"
          >
            <X size={20} className="text-gray-500" />
          </button>
        </div>

        {}
        <div className="flex-1 overflow-y-auto p-6">
          <div className="space-y-4">
            {}
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

            {}
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

            {}
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

            {}
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

            {}
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

            {}
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
          {doiChecking && (
            <p className="text-xs text-purple-600 mt-1">Validating DOI...</p>
          )}
          <p className="text-xs text-gray-400 mt-1">You can paste a full DOI URL — it will be normalised automatically.</p>
            </div>

            {}
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

        {}
        <div className="p-4 border-t border-gray-200 bg-gray-50 flex justify-end gap-2">
          <button
            onClick={handleClose}
            disabled={doiChecking}
            className="px-4 py-2 text-gray-700 bg-gray-200 rounded-lg hover:bg-gray-300 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={doiChecking}
            className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
          >
            <Save size={18} />
            <span>{doiChecking ? 'Validating DOI...' : 'Insert Citation'}</span>
          </button>
        </div>
      </div>
    </div>
  );
};

export default ManualCitationDialog;
