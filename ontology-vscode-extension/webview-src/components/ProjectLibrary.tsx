import React, { useState, useEffect } from 'react';
import { 
    ArrowLeft, 
    Upload, 
    FileText, 
    Search, 
    Grid, 
    List,
    MoreVertical,
    Download,
    Trash2,
    Clock,
    User,
    Folder
} from 'lucide-react';
import apiClient from '../services/apiClient';
import { useAuth } from '../custom-hook/useAuth';

interface ProjectLibraryProps {
    projectId: string;
    projectName: string;
    onBack: () => void;
    onFileSelect: (fileId: string, fileName: string) => void;
}

interface FileItem {
    id: string;
    name: string;
    size: number;
    uploadedBy: string;
    uploadedAt: string;
    type: string;
}

const ProjectLibrary: React.FC<ProjectLibraryProps> = ({ 
    projectId, 
    projectName, 
    onBack,
    onFileSelect 
}) => {
    const [files, setFiles] = useState<FileItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [searchQuery, setSearchQuery] = useState('');
    const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
    const [uploading, setUploading] = useState(false);
    const [uploadProgress, setUploadProgress] = useState(0);
    const [processingFile, setProcessingFile] = useState<string | null>(null);
    const [selectedFile, setSelectedFile] = useState<string | null>(null);
    const [deleteConfirm, setDeleteConfirm] = useState<{ show: boolean; fileId: string; fileName: string }>({ show: false, fileId: '', fileName: '' });
    const [toast, setToast] = useState<{ show: boolean; message: string; type: 'success' | 'error' }>({ show: false, message: '', type: 'success' });
    const [openMenuFileId, setOpenMenuFileId] = useState<string | null>(null); // Track which file menu is open
    const { user } = useAuth();

    const showToast = (message: string, type: 'success' | 'error' = 'success') => {
        setToast({ show: true, message, type });
        setTimeout(() => setToast({ show: false, message: '', type: 'success' }), 3000);
    };

    useEffect(() => {
        loadFiles();
    }, [projectId]);

    // Close menu when clicking outside
    useEffect(() => {
        const handleClickOutside = () => setOpenMenuFileId(null);
        if (openMenuFileId) {
            document.addEventListener('click', handleClickOutside);
            return () => document.removeEventListener('click', handleClickOutside);
        }
    }, [openMenuFileId]);

    const loadFiles = async () => {
        try {
            setLoading(true);
            const response = await apiClient.get(`/api/projects/${projectId}/files`);
            console.log('[ProjectLibrary] Files response:', response);
            
            // Handle both response.data and response.data.files structures
            const fileList = response?.files || response?.data || [];
            console.log('[ProjectLibrary] Parsed file list:', fileList);
            
            setFiles(Array.isArray(fileList) ? fileList : []);
        } catch (error) {
            console.error('Error loading files:', error);
            showToast('Failed to load files', 'error');
            setFiles([]);
        } finally {
            setLoading(false);
        }
    };

    const handleFileUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        if (!file) return;

        // Validate file size (max 300MB)
        const maxSize = 300 * 1024 * 1024; // 300MB
        if (file.size > maxSize) {
            showToast(`File too large. Maximum size is 300MB. Your file is ${(file.size / (1024 * 1024)).toFixed(1)}MB`, 'error');
            event.target.value = '';
            return;
        }

        // Validate file type
        const validExtensions = ['.owl', '.rdf', '.ttl', '.n3'];
        const fileExtension = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
        if (!validExtensions.includes(fileExtension)) {
            showToast('Invalid file type. Only .owl, .rdf, .ttl, .n3 files are allowed', 'error');
            event.target.value = '';
            return;
        }

        try {
            setUploading(true);
            setUploadProgress(0);
            setProcessingFile(file.name);
            
            console.log(`[ProjectLibrary] Processing large file: ${file.name} (${(file.size / (1024 * 1024)).toFixed(2)}MB)`);
            
            // For large files (>10MB), use chunked processing
            const isLargeFile = file.size > 10 * 1024 * 1024;
            
            if (isLargeFile) {
                showToast(`Processing large file: ${file.name}...`, 'success');
            }
            
            // Convert file to base64 for message passing
            const reader = new FileReader();
            
            // Track reading progress
            reader.onprogress = (e) => {
                if (e.lengthComputable) {
                    const progress = Math.round((e.loaded / e.total) * 50); // 0-50% for reading
                    setUploadProgress(progress);
                }
            };
            
            const base64Promise = new Promise<string>((resolve, reject) => {
                reader.onload = () => resolve(reader.result as string);
                reader.onerror = reject;
                reader.readAsDataURL(file);
            });
            
            const base64Data = await base64Promise;
            setUploadProgress(50); // Reading complete
            
            console.log('[ProjectLibrary] File read complete, uploading to server...');
            
            // Send as JSON with timeout for large files
            const uploadResponse = await apiClient.post(`/api/projects/${projectId}/files`, {
                fileName: file.name,
                fileData: base64Data,
                fileSize: file.size,
                fileType: file.type || 'application/rdf+xml'
            }, {
                timeout: 300000, // 5 minute timeout for large files
                onUploadProgress: (progressEvent) => {
                    if (progressEvent.total) {
                        const uploadPercent = Math.round((progressEvent.loaded / progressEvent.total) * 50);
                        setUploadProgress(50 + uploadPercent); // 50-100% for uploading
                    }
                }
            });
            
            console.log('[ProjectLibrary] Upload response:', uploadResponse);
            setUploadProgress(100);
            
            if (isLargeFile) {
                showToast('Large file uploaded successfully! Processing in background...', 'success');
            } else {
                showToast('File uploaded successfully', 'success');
            }
            
            // Reload files to show the uploaded file
            await loadFiles();
        } catch (error: any) {
            console.error('Error uploading file:', error);
            
            // Provide specific error messages
            let errorMessage = 'Failed to upload file';
            if (error.code === 'ECONNABORTED' || error.message?.includes('timeout')) {
                errorMessage = 'Upload timeout. Please try a smaller file or check your connection.';
            } else if (error.response?.status === 413) {
                errorMessage = 'File too large for server. Maximum size is 300MB.';
            } else if (error.response?.data?.error) {
                errorMessage = error.response.data.error;
            }
            
            showToast(errorMessage, 'error');
        } finally {
            setUploading(false);
            setUploadProgress(0);
            setProcessingFile(null);
            event.target.value = '';
        }
    };

    const handleFileClick = (file: FileItem) => {
        onFileSelect(file.id, file.name);
    };

    const handleDeleteFile = async (fileId: string, fileName: string) => {
        setDeleteConfirm({ show: true, fileId, fileName });
    };

    const confirmDelete = async () => {
        const fileId = deleteConfirm.fileId;
        setDeleteConfirm({ show: false, fileId: '', fileName: '' });

        try {
            await apiClient.delete(`/api/projects/${projectId}/files/${fileId}`);
            showToast('File deleted successfully', 'success');
            await loadFiles();
        } catch (error) {
            console.error('Error deleting file:', error);
            showToast('Failed to delete file', 'error');
        }
    };

    const cancelDelete = () => {
        setDeleteConfirm({ show: false, fileId: '', fileName: '' });
    };

    const formatFileSize = (bytes: number) => {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    };

    const formatDate = (dateString: string) => {
        const date = new Date(dateString);
        const now = new Date();
        const diff = now.getTime() - date.getTime();
        const days = Math.floor(diff / (1000 * 60 * 60 * 24));

        if (days === 0) return 'Today';
        if (days === 1) return 'Yesterday';
        if (days < 7) return `${days} days ago`;
        return date.toLocaleDateString();
    };

    const filteredFiles = files.filter(file =>
        file.name.toLowerCase().includes(searchQuery.toLowerCase())
    );

    return (
        <div className="min-h-screen bg-gray-50">
            {/* Header */}
            <div className="bg-white border-b sticky top-0 z-10">
                <div className="max-w-7xl mx-auto px-6 py-4">
                    <div className="flex items-center justify-between mb-4">
                        <div className="flex items-center gap-4">
                            <button
                                onClick={onBack}
                                className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
                            >
                                <ArrowLeft size={20} className="text-gray-600" />
                            </button>
                            <div>
                                <h1 className="text-2xl font-bold text-gray-900">{projectName}</h1>
                                <p className="text-sm text-gray-500">Project Library</p>
                            </div>
                        </div>

                        <label className="px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 transition-colors cursor-pointer flex items-center gap-2">
                            <Upload size={18} />
                            {uploading ? 'Uploading...' : 'Upload File'}
                            <input
                                type="file"
                                onChange={handleFileUpload}
                                className="hidden"
                                accept=".owl,.rdf,.ttl,.n3"
                                disabled={uploading}
                            />
                        </label>
                    </div>

                    <div className="flex items-center gap-4">
                        <div className="flex-1 relative">
                            <Search size={18} className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400" />
                            <input
                                type="text"
                                value={searchQuery}
                                onChange={(e) => setSearchQuery(e.target.value)}
                                placeholder="Search files..."
                                className="w-full pl-10 pr-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-transparent"
                            />
                        </div>

                        <div className="flex items-center gap-2 border border-gray-300 rounded-lg p-1">
                            <button
                                onClick={() => setViewMode('grid')}
                                className={`p-2 rounded ${viewMode === 'grid' ? 'bg-purple-100 text-purple-600' : 'text-gray-600 hover:bg-gray-100'}`}
                            >
                                <Grid size={18} />
                            </button>
                            <button
                                onClick={() => setViewMode('list')}
                                className={`p-2 rounded ${viewMode === 'list' ? 'bg-purple-100 text-purple-600' : 'text-gray-600 hover:bg-gray-100'}`}
                            >
                                <List size={18} />
                            </button>
                        </div>
                    </div>

                    {/* Upload Progress Bar */}
                    {uploading && (
                        <div className="mt-4">
                            <div className="flex items-center justify-between mb-2">
                                <span className="text-sm font-medium text-gray-700">
                                    {processingFile ? `Processing: ${processingFile}` : 'Uploading file...'}
                                </span>
                                <span className="text-sm text-gray-600">{uploadProgress}%</span>
                            </div>
                            <div className="w-full bg-gray-200 rounded-full h-2 overflow-hidden">
                                <div 
                                    className="bg-purple-600 h-full transition-all duration-300 ease-out"
                                    style={{ width: `${uploadProgress}%` }}
                                />
                            </div>
                            <p className="text-xs text-gray-500 mt-1">
                                {uploadProgress < 50 ? 'Reading file...' : 
                                 uploadProgress < 100 ? 'Uploading to server...' : 
                                 'Processing complete!'}
                            </p>
                        </div>
                    )}
                </div>
            </div>

            {/* Content */}
            <div className="max-w-7xl mx-auto px-6 py-6">
                {loading ? (
                    <div className="text-center py-12">
                        <div className="animate-spin rounded-full h-12 w-12 border-t-4 border-purple-600 mx-auto"></div>
                        <p className="mt-4 text-gray-600">Loading files...</p>
                    </div>
                ) : filteredFiles.length === 0 ? (
                    <div className="text-center py-12">
                        <Folder size={48} className="text-gray-400 mx-auto mb-4" />
                        <h3 className="text-xl font-semibold text-gray-900 mb-2">
                            {searchQuery ? 'No files found' : 'No files yet'}
                        </h3>
                        <p className="text-gray-600 mb-6">
                            {searchQuery 
                                ? 'Try a different search query'
                                : 'Upload your first ontology file to get started'
                            }
                        </p>
                    </div>
                ) : viewMode === 'grid' ? (
                    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
                        {filteredFiles.map((file) => (
                            <div
                                key={file.id}
                                onClick={() => handleFileClick(file)}
                                className={`bg-white rounded-lg border-2 p-4 cursor-pointer transition-all hover:shadow-lg ${
                                    selectedFile === file.id ? 'border-purple-500 shadow-lg' : 'border-gray-200'
                                }`}
                            >
                                <div className="flex items-start justify-between mb-3">
                                    <div className="w-12 h-12 bg-purple-100 rounded-lg flex items-center justify-center">
                                        <FileText size={24} className="text-purple-600" />
                                    </div>
                                    <div className="relative">
                                        <button 
                                            onClick={(e) => {
                                                e.stopPropagation();
                                                setOpenMenuFileId(openMenuFileId === file.id ? null : file.id);
                                            }}
                                            className="p-1 hover:bg-gray-100 rounded"
                                        >
                                            <MoreVertical size={16} className="text-gray-400" />
                                        </button>
                                        {openMenuFileId === file.id && (
                                            <div className="absolute right-0 mt-1 w-40 bg-white border border-gray-200 rounded-lg shadow-lg z-10">
                                            <button
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    handleDeleteFile(file.id, file.name);
                                                }}
                                                className="w-full px-4 py-2 text-left text-sm text-red-600 hover:bg-red-50 rounded-lg flex items-center gap-2"
                                            >
                                                <Trash2 size={14} />
                                                Delete
                                            </button>
                                            </div>
                                        )}
                                    </div>
                                </div>

                                <h3 className="font-semibold text-gray-900 mb-1 truncate" title={file.name}>
                                    {file.name}
                                </h3>
                                
                                <div className="flex items-center gap-4 text-xs text-gray-500 mb-2">
                                    <span>{formatFileSize(file.size)}</span>
                                    <span>•</span>
                                    <span className="flex items-center gap-1">
                                        <Clock size={12} />
                                        {formatDate(file.uploadedAt)}
                                    </span>
                                </div>

                                <div className="flex items-center gap-1 text-xs text-gray-600">
                                    <User size={12} />
                                    {file.uploadedBy}
                                </div>
                            </div>
                        ))}
                    </div>
                ) : (
                    <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
                        <table className="w-full">
                            <thead className="bg-gray-50 border-b border-gray-200">
                                <tr>
                                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                                        Name
                                    </th>
                                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                                        Size
                                    </th>
                                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                                        Uploaded By
                                    </th>
                                    <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                                        Date
                                    </th>
                                    <th className="px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">
                                        Actions
                                    </th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-gray-200">
                                {filteredFiles.map((file) => (
                                    <tr
                                        key={file.id}
                                        onClick={() => handleFileClick(file)}
                                        className={`cursor-pointer hover:bg-gray-50 transition-colors ${
                                            selectedFile === file.id ? 'bg-purple-50' : ''
                                        }`}
                                    >
                                        <td className="px-6 py-4 whitespace-nowrap">
                                            <div className="flex items-center gap-3">
                                                <div className="w-8 h-8 bg-purple-100 rounded flex items-center justify-center">
                                                    <FileText size={16} className="text-purple-600" />
                                                </div>
                                                <span className="font-medium text-gray-900">{file.name}</span>
                                            </div>
                                        </td>
                                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600">
                                            {formatFileSize(file.size)}
                                        </td>
                                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600">
                                            {file.uploadedBy}
                                        </td>
                                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600">
                                            {formatDate(file.uploadedAt)}
                                        </td>
                                        <td className="px-6 py-4 whitespace-nowrap text-right text-sm">
                                            <button
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    handleDeleteFile(file.id, file.name);
                                                }}
                                                className="text-red-600 hover:text-red-800"
                                            >
                                                <Trash2 size={16} />
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>

            {/* Delete Confirmation Dialog */}
            {deleteConfirm.show && (
                <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
                    <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4 shadow-xl">
                        <h3 className="text-lg font-semibold text-gray-900 mb-2">Delete File</h3>
                        <p className="text-gray-600 mb-6">
                            Are you sure you want to delete <span className="font-semibold">{deleteConfirm.fileName}</span>? This action cannot be undone.
                        </p>
                        <div className="flex justify-end gap-3">
                            <button
                                onClick={cancelDelete}
                                className="px-4 py-2 text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg transition-colors"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={confirmDelete}
                                className="px-4 py-2 bg-red-600 text-white hover:bg-red-700 rounded-lg transition-colors"
                            >
                                Delete
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* Toast Notification */}
            {toast.show && (
                <div className="fixed bottom-4 right-4 z-50 animate-slide-up">
                    <div className={`px-6 py-3 rounded-lg shadow-lg flex items-center gap-3 ${
                        toast.type === 'success' ? 'bg-green-600' : 'bg-red-600'
                    } text-white`}>
                        {toast.type === 'success' ? (
                            <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                            </svg>
                        ) : (
                            <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clipRule="evenodd" />
                            </svg>
                        )}
                        <span>{toast.message}</span>
                    </div>
                </div>
            )}
        </div>
    );
};

export default ProjectLibrary;
