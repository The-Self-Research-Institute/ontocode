import React, { useState, useEffect } from 'react';
import { Bug, Upload, X, AlertCircle, CheckCircle, FileText, ListOrdered, Tag, Flag, FileCode, Image, File } from 'lucide-react';
import { useAuth } from '../custom-hook/useAuth';

interface ReportIssueModalProps {
  projectName?: string;
  projectId?: string;
  ontologyFilePath?: string;
  onClose: () => void;
}

// Get API base URL based on deployment type
const getApiBaseUrl = () => {
  const deploymentType = localStorage.getItem('deploymentType') || 'cloud';
  const config = (window as any).__ONTOCODE_CONFIG__;
  
  if (deploymentType === 'self-hosted') {
    // For self-hosted, use direct editor service URL (port 8083)
    return 'http://localhost:8083';
  } else {
    // For cloud, use cloud gateway URL (will go through port 80)
    return config?.CLOUD_GATEWAY_URL || 'http://13.218.153.101';
  }
};

export const ReportIssueModal: React.FC<ReportIssueModalProps> = ({
  projectName,
  projectId,
  ontologyFilePath,
  onClose
}) => {
  const { user } = useAuth();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [stepsToReproduce, setStepsToReproduce] = useState('');
  const [issueType, setIssueType] = useState('Task');
  const [priority, setPriority] = useState('Medium');
  const [attachments, setAttachments] = useState<File[]>([]);
  const [filePreviews, setFilePreviews] = useState<Map<string, string>>(new Map());
  const [isDragging, setIsDragging] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitResult, setSubmitResult] = useState<{
    success: boolean;
    message: string;
    jiraUrl?: string;
  } | null>(null);

  // Get system info
  const getSystemInfo = () => {
    // Extract OS info from user agent
    const ua = navigator.userAgent;
    let osInfo = navigator.platform;
    
    // Try to get more readable OS name
    if (ua.indexOf('Win') !== -1) osInfo = 'Windows';
    else if (ua.indexOf('Mac') !== -1) osInfo = 'macOS';
    else if (ua.indexOf('Linux') !== -1) osInfo = 'Linux';
    
    // Extract VS Code version from user agent if available
    const vscodeMatch = ua.match(/Code\/([\d.]+)/);
    const chromeMatch = ua.match(/Chrome\/([\d.]+)/);
    const electronMatch = ua.match(/Electron\/([\d.]+)/);
    
    return {
      osName: osInfo,
      osVersion: navigator.platform, // Keep platform for details
      vsCodeVersion: vscodeMatch ? vscodeMatch[1] : (window as any).vscodeVersion || 'Unknown',
      extensionVersion: (window as any).extensionVersion || 'Unknown',
      browser: `Chrome ${chromeMatch ? chromeMatch[1] : 'Unknown'}, Electron ${electronMatch ? electronMatch[1] : 'Unknown'}`,
    };
  };

  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    const files: File[] = Array.from(event.target.files || []);
    processFiles(files);
  };

  const processFiles = (files: File[]) => {
    // Define allowed file types
    const allowedExtensions = ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.svg', '.webp', '.pdf', '.doc', '.docx', '.txt', '.log', '.owl', '.ttl', '.rdf'];
    const allowedMimeTypes = ['image/', 'application/pdf', 'application/msword', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'text/', 'application/rdf+xml', 'application/x-turtle'];
    
    // Validate file types
    const invalidTypeFiles = files.filter((f: File) => {
      const fileName = f.name.toLowerCase();
      const fileType = f.type.toLowerCase();
      
      const hasValidExtension = allowedExtensions.some(ext => fileName.endsWith(ext));
      const hasValidMimeType = allowedMimeTypes.some(mime => fileType.startsWith(mime));
      
      return !(hasValidExtension || hasValidMimeType);
    });
    
    if (invalidTypeFiles.length > 0) {
      alert(`The following files have unsupported file types: ${invalidTypeFiles.map((f: File) => f.name).join(', ')}\\n\\nSupported formats: images, PDF, Word documents (.doc, .docx), text files, logs, and ontology files (.owl, .ttl, .rdf)`);
      return;
    }
    
    // Add all valid files
    const newAttachments = [...attachments, ...files];
    setAttachments(newAttachments);
    
    // Generate previews for image and text-based files
    files.forEach(file => {
      const fileName = file.name.toLowerCase();
      const isTextFile = fileName.endsWith('.txt') || fileName.endsWith('.log') || 
                         fileName.endsWith('.owl') || fileName.endsWith('.ttl') || fileName.endsWith('.rdf');
      const isPDF = fileName.endsWith('.pdf');
      const isWordDoc = fileName.endsWith('.doc') || fileName.endsWith('.docx');
      
      if (file.type.startsWith('image/')) {
        // Image preview - read as data URL
        const reader = new FileReader();
        reader.onload = (e) => {
          if (e.target?.result) {
            setFilePreviews(prev => new Map(prev).set(file.name, e.target!.result as string));
          }
        };
        reader.readAsDataURL(file);
      } else if (isTextFile) {
        // Text file preview - read first 500 characters
        const reader = new FileReader();
        reader.onload = (e) => {
          if (e.target?.result) {
            const text = e.target.result as string;
            const preview = text.substring(0, 500);
            setFilePreviews(prev => new Map(prev).set(file.name, `text:${preview}`));
          }
        };
        reader.readAsText(file);
      } else if (isPDF) {
        // PDF preview - mark as PDF type
        setFilePreviews(prev => new Map(prev).set(file.name, 'pdf:preview'));
      } else if (isWordDoc) {
        // Word document preview - mark as Word type
        setFilePreviews(prev => new Map(prev).set(file.name, 'word:preview'));
      }
    });
  };

  const handleDragOver = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
  };

  const handleDragEnter = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(true);
  };

  const handleDragLeave = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
    // Only set to false if leaving the drop zone entirely
    if (e.currentTarget === e.target) {
      setIsDragging(false);
    }
  };

  const handleDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);

    const droppedFiles: File[] = Array.from(e.dataTransfer.files);
    processFiles(droppedFiles);
  };

  const removeAttachment = (index: number) => {
    const fileToRemove = attachments[index];
    setAttachments(attachments.filter((_, i) => i !== index));
    
    // Remove preview if exists
    if (filePreviews.has(fileToRemove.name)) {
      const newPreviews = new Map(filePreviews);
      newPreviews.delete(fileToRemove.name);
      setFilePreviews(newPreviews);
    }
  };

  const handleSubmit = async () => {
    if (!title.trim()) {
      alert('Please enter a title');
      return;
    }

    if (!description.trim()) {
      alert('Please enter a description');
      return;
    }

    setSubmitting(true);
    setSubmitResult(null);

    try {
      const formData = new FormData();
      formData.append('title', title);
      formData.append('description', description);
      
      if (stepsToReproduce.trim()) {
        formData.append('stepsToReproduce', stepsToReproduce);
      }
      
      formData.append('issueType', issueType);
      
      formData.append('priority', priority);
      
      if (projectId) {
        formData.append('projectId', projectId);
      }
      
      if (projectName) {
        formData.append('projectName', projectName);
      }
      
      if (ontologyFilePath) {
        formData.append('ontologyFilePath', ontologyFilePath);
      }

      // Add system info
      const systemInfo = getSystemInfo();
      formData.append('osName', systemInfo.osName);
      formData.append('osVersion', systemInfo.osVersion);
      formData.append('vsCodeVersion', systemInfo.vsCodeVersion);
      formData.append('extensionVersion', systemInfo.extensionVersion);

      // Add attachments
      attachments.forEach((file) => {
        formData.append('attachments', file);
      });

      // Submit to backend - Get token from auth context
      const token = user?.token;
      const apiBaseUrl = getApiBaseUrl();
      const response = await fetch(`${apiBaseUrl}/api/v1/issues/report`, {
        method: 'POST',
        body: formData,
        // credentials: 'include', // Removed - we use JWT in Authorization header, not cookies
        headers: token ? { 'Authorization': `Bearer ${token}` } : {}
      });

      const result = await response.json();

      if (result.success) {
        setSubmitResult({
          success: true,
          message: result.message || 'Issue reported successfully!',
          jiraUrl: result.jiraIssueUrl
        });

        // Close modal after 3 seconds on success
        setTimeout(() => {
          onClose();
        }, 3000);
      } else {
        setSubmitResult({
          success: false,
          message: result.message || 'Failed to submit issue report'
        });
      }
    } catch (error) {
      console.error('Failed to submit issue report:', error);
      setSubmitResult({
        success: false,
        message: 'Network error: Failed to submit issue report. Please try again.'
      });
    } finally {
      setSubmitting(false);
    }
  };

  // Get file icon and color based on file type
  const getFileIconAndColor = (file: File) => {
    const fileName = file.name.toLowerCase();
    const fileType = file.type.toLowerCase();
    
    if (fileType.startsWith('image/')) {
      return { icon: Image, color: 'bg-green-100', iconColor: 'text-green-600' };
    } else if (fileName.endsWith('.pdf')) {
      return { icon: File, color: 'bg-red-100', iconColor: 'text-red-600' };
    } else if (fileName.endsWith('.doc') || fileName.endsWith('.docx')) {
      return { icon: FileText, color: 'bg-blue-100', iconColor: 'text-blue-600' };
    } else if (fileName.endsWith('.log') || fileName.endsWith('.txt')) {
      return { icon: FileText, color: 'bg-gray-100', iconColor: 'text-gray-600' };
    } else if (fileName.endsWith('.owl') || fileName.endsWith('.ttl') || fileName.endsWith('.rdf')) {
      return { icon: FileCode, color: 'bg-purple-100', iconColor: 'text-purple-600' };
    }
    return { icon: FileText, color: 'bg-blue-100', iconColor: 'text-blue-600' };
  };

  // Get priority color
  const getPriorityColor = (p: string) => {
    switch(p) {
      case 'Highest': return 'bg-red-100 text-red-800 border-red-300';
      case 'High': return 'bg-orange-100 text-orange-800 border-orange-300';
      case 'Medium': return 'bg-yellow-100 text-yellow-800 border-yellow-300';
      case 'Low': return 'bg-blue-100 text-blue-800 border-blue-300';
      case 'Lowest': return 'bg-gray-100 text-gray-800 border-gray-300';
      default: return 'bg-gray-100 text-gray-800 border-gray-300';
    }
  };

  // Get issue type icon and color
  const getIssueTypeStyle = (type: string) => {
    switch(type) {
      case 'Bug': return { color: 'bg-red-100 text-red-800 border-red-300', icon: Bug };
      case 'Task': return { color: 'bg-blue-100 text-blue-800 border-blue-300', icon: ListOrdered };
      default: return { color: 'bg-purple-100 text-purple-800 border-purple-300', icon: Tag };
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
      <div className="bg-white rounded-xl shadow-2xl max-w-4xl w-full max-h-[95vh] overflow-hidden flex flex-col">
        {/* Header */}
        <div className="bg-gradient-to-r from-purple-600 to-indigo-600 px-6 py-5 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="bg-white bg-opacity-20 p-2 rounded-lg">
              <Bug className="text-white" size={28} />
            </div>
            <div>
              <h2 className="text-2xl font-bold text-white">
                Report an Issue
              </h2>
              {projectName && (
                <p className="text-purple-100 text-sm mt-1">
                  Project: {projectName}
                </p>
              )}
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-white hover:bg-white hover:bg-opacity-20 p-2 rounded-lg transition-colors"
            disabled={submitting}
          >
            <X size={24} />
          </button>
        </div>

        {/* Success/Error Message */}
        {submitResult && (
          <div className={`mx-6 mt-6 p-5 rounded-lg flex items-start gap-4 shadow-md ${
            submitResult.success 
              ? 'bg-gradient-to-r from-green-50 to-emerald-50 border-2 border-green-300' 
              : 'bg-gradient-to-r from-red-50 to-pink-50 border-2 border-red-300'
          }`}>
            <div className={`flex-shrink-0 p-2 rounded-full ${
              submitResult.success ? 'bg-green-100' : 'bg-red-100'
            }`}>
              {submitResult.success ? (
                <CheckCircle className="text-green-600" size={24} />
              ) : (
                <AlertCircle className="text-red-600" size={24} />
              )}
            </div>
            <div className="flex-1">
              <p className={`text-base font-semibold mb-2 ${
                submitResult.success ? 'text-green-900' : 'text-red-900'
              }`}>
                {submitResult.message}
              </p>
              {submitResult.success && (
                <div className="flex items-center gap-3 mt-3">
                  <span className={`inline-flex items-center gap-1 px-3 py-1 rounded-full text-xs font-semibold border ${
                    getIssueTypeStyle(issueType).color
                  }`}>
                    {React.createElement(getIssueTypeStyle(issueType).icon, { size: 14 })}
                    {issueType}
                  </span>
                  <span className={`inline-flex items-center gap-1 px-3 py-1 rounded-full text-xs font-semibold border ${
                    getPriorityColor(priority)
                  }`}>
                    <Flag size={14} />
                    {priority}
                  </span>
                </div>
              )}
              {submitResult.jiraUrl && (
                <a
                  href={submitResult.jiraUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-2 mt-4 px-4 py-2 bg-purple-600 text-white text-sm font-medium rounded-lg hover:bg-purple-700 transition-colors"
                >
                  View in Jira
                  <span>→</span>
                </a>
              )}
            </div>
          </div>
        )}

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-6 py-6 space-y-6">
          {/* Issue Type & Priority Row */}
          <div className="grid grid-cols-2 gap-4">
            {/* Issue Type */}
            <div className="space-y-2">
              <label className="flex items-center gap-2 text-sm font-semibold text-gray-800">
                <Tag size={16} className="text-purple-600" />
                Issue Type <span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <select
                  value={issueType}
                  onChange={(e) => setIssueType(e.target.value)}
                  className="w-full px-4 py-3 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black appearance-none cursor-pointer transition-all"
                  disabled={submitting}
                >
                  <option value="Bug">🐛 Bug</option>
                  <option value="Task">✓ Task</option>
                </select>
                <div className="absolute right-3 top-1/2 -translate-y-1/2 pointer-events-none">
                  <svg className="w-4 h-4 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                  </svg>
                </div>
              </div>
            </div>

            {/* Priority */}
            <div className="space-y-2">
              <label className="flex items-center gap-2 text-sm font-semibold text-gray-800">
                <Flag size={16} className="text-purple-600" />
                Priority <span className="text-red-500">*</span>
              </label>
              <div className="relative">
                <select
                  value={priority}
                  onChange={(e) => setPriority(e.target.value)}
                  className="w-full px-4 py-3 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black appearance-none cursor-pointer transition-all"
                  disabled={submitting}
                >
                  <option value="Highest">🔴 Highest</option>
                  <option value="High">🟠 High</option>
                  <option value="Medium">🟡 Medium</option>
                  <option value="Low">🔵 Low</option>
                  <option value="Lowest">⚪ Lowest</option>
                </select>
                <div className="absolute right-3 top-1/2 -translate-y-1/2 pointer-events-none">
                  <svg className="w-4 h-4 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                  </svg>
                </div>
              </div>
            </div>
          </div>

          {/* Title */}
          <div className="space-y-2">
            <label className="flex items-center gap-2 text-sm font-semibold text-gray-800">
              <FileText size={16} className="text-purple-600" />
              Title <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Brief summary of the issue"
              maxLength={200}
              className="w-full px-4 py-3 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400 transition-all"
              disabled={submitting}
            />
            <div className="flex justify-between items-center">
              <p className="text-xs text-gray-500">Provide a clear, concise summary</p>
              <p className={`text-xs font-medium ${
                title.length > 180 ? 'text-orange-600' : 'text-gray-500'
              }`}>{title.length}/200</p>
            </div>
          </div>

          {/* Description */}
          <div className="space-y-2">
            <label className="flex items-center gap-2 text-sm font-semibold text-gray-800">
              <FileCode size={16} className="text-purple-600" />
              Description <span className="text-red-500">*</span>
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={5}
              placeholder="Describe the issue in detail. Include what you expected vs. what actually happened..."
              className="w-full px-4 py-3 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400 transition-all resize-none"
              disabled={submitting}
            />
          </div>

          {/* Steps to Reproduce */}
          <div className="space-y-2">
            <label className="flex items-center gap-2 text-sm font-semibold text-gray-800">
              <ListOrdered size={16} className="text-purple-600" />
              Steps to Reproduce
              <span className="text-xs font-normal text-gray-500">(Optional)</span>
            </label>
            <textarea
              value={stepsToReproduce}
              onChange={(e) => setStepsToReproduce(e.target.value)}
              rows={4}
              placeholder="1. Go to...&#10;2. Click on...&#10;3. See error..."
              className="w-full px-4 py-3 border-2 border-gray-300 rounded-lg focus:ring-2 focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400 transition-all resize-none"
              disabled={submitting}
            />
          </div>

          {/* File Attachments */}
          <div className="space-y-3">
            <label className="flex items-center gap-2 text-sm font-semibold text-gray-800">
              <Upload size={16} className="text-purple-600" />
              Attachments
              <span className="text-xs font-normal text-gray-500">(Optional)</span>
            </label>
            <div 
              className={`border-2 border-dashed rounded-lg transition-all ${
                isDragging 
                  ? 'border-purple-500 bg-purple-50' 
                  : 'border-gray-300 bg-gray-50 hover:bg-gray-100'
              }`}
              onDragOver={handleDragOver}
              onDragEnter={handleDragEnter}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
            >
              {/* Upload Button - Vertical centered layout with dynamic sizing */}
              <label className={`flex flex-col items-center justify-center cursor-pointer transition-all ${
                attachments.length > 0 ? 'py-3' : 'py-8'
              }`}>
                  <div className={`rounded-full transition-all ${
                    attachments.length > 0 ? 'p-2 mb-2' : 'p-3 mb-3'
                  } ${
                    isDragging 
                      ? 'bg-purple-200 scale-110' 
                      : 'bg-purple-100'
                  }`}>
                    <Upload size={attachments.length > 0 ? 20 : 28} className={`transition-colors ${
                      isDragging ? 'text-purple-700' : 'text-purple-600'
                    }`} />
                  </div>
                  <span className={`font-medium transition-colors ${
                    attachments.length > 0 ? 'text-sm mb-1' : 'text-base mb-1'
                  } ${
                    isDragging ? 'text-purple-700' : 'text-gray-700'
                  }`}>
                    {isDragging ? 'Drop files here' : 'Choose files to upload'}
                  </span>
                  <span className={`text-gray-500 transition-all ${
                    attachments.length > 0 ? 'text-xs mb-1' : 'text-sm mb-3'
                  }`}>
                    {isDragging ? 'Release to upload' : 'or drag and drop'}
                  </span>
                  
                  {/* Supported file types */}
                  <span className={`text-gray-500 transition-all ${
                    attachments.length > 0 ? 'text-[10px]' : 'text-xs'
                  }`}>
                    JPG, PNG, PDF, DOC, DOCX, TXT, .log, .owl, .ttl, .rdf
                  </span>
                  
                  <input
                    type="file"
                    multiple
                    accept="image/*,.pdf,.doc,.docx,.txt,.log,.owl,.ttl,.rdf"
                    onChange={handleFileSelect}
                    className="hidden"
                    disabled={submitting}
                  />
                </label>

              {/* File List */}
              {attachments.length > 0 && (
                <div className="border-t border-gray-200 pt-4 px-4 pb-2">
                  <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
                  {attachments.map((file, index) => {
                    const { icon: FileIcon, color, iconColor } = getFileIconAndColor(file);
                    const preview = filePreviews.get(file.name);
                    const isImage = file.type.startsWith('image/');
                    
                    return (
                      <div
                        key={index}
                        className="relative group bg-white rounded-lg border-2 border-gray-200 hover:border-purple-400 shadow-sm hover:shadow-md transition-all overflow-hidden"
                      >
                        {/* Remove button */}
                        <button
                          onClick={() => removeAttachment(index)}
                          className="absolute top-2 right-2 p-1.5 bg-white rounded-full border border-gray-300 text-gray-400 hover:text-red-600 hover:bg-red-50 hover:border-red-300 transition-colors opacity-0 group-hover:opacity-100 z-10 shadow-md"
                          disabled={submitting}
                          title="Remove file"
                        >
                          <X size={14} />
                        </button>
                        
                        {/* File thumbnail/icon */}
                        <div className="w-full aspect-[4/3] flex items-center justify-center p-2 bg-gray-50">
                          {isImage && preview && !preview.startsWith('text:') && !preview.startsWith('pdf:') && !preview.startsWith('word:') ? (
                            <img
                              src={preview}
                              alt={file.name}
                              className="w-full h-full object-cover rounded"
                            />
                          ) : preview?.startsWith('text:') ? (
                            <div className="w-full h-full bg-white rounded border border-gray-200 p-2 overflow-hidden">
                              <pre className="text-[7px] leading-tight text-gray-700 font-mono whitespace-pre-wrap break-all">
                                {preview.substring(5)}
                              </pre>
                            </div>
                          ) : preview?.startsWith('pdf:') ? (
                            <div className="w-full h-full bg-gradient-to-br from-red-50 to-red-100 rounded border-2 border-red-200 flex flex-col items-center justify-center p-2">
                              <FileText size={28} className="text-red-600 mb-1" />
                              <span className="text-xs font-bold text-red-700">PDF</span>
                              <span className="text-[7px] text-red-600 mt-0.5">Document</span>
                            </div>
                          ) : preview?.startsWith('word:') ? (
                            <div className="w-full h-full bg-gradient-to-br from-blue-50 to-blue-100 rounded border-2 border-blue-200 flex flex-col items-center justify-center p-2">
                              <FileText size={28} className="text-blue-600 mb-1" />
                              <span className="text-xs font-bold text-blue-700">Word</span>
                              <span className="text-[7px] text-blue-600 mt-0.5">Document</span>
                            </div>
                          ) : (
                            <div className={`${color} p-3 rounded-lg`}>
                              <FileIcon size={24} className={iconColor} />
                            </div>
                          )}
                        </div>
                        
                        {/* File info */}
                        <div className="p-2 border-t border-gray-100">
                          {/* File name */}
                          <p className="text-xs font-medium text-gray-800 text-center truncate w-full" title={file.name}>
                            {file.name}
                          </p>
                        </div>
                      </div>
                    );
                  })}
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="border-t-2 border-gray-200 bg-gray-50 px-6 py-5 flex justify-between items-center">
          <p className="text-xs text-gray-600">
            <span className="text-red-500">*</span> Required fields
          </p>
          <div className="flex gap-3">
            <button
              onClick={onClose}
              disabled={submitting}
              className="px-6 py-2.5 text-sm font-semibold text-gray-700 bg-white border-2 border-gray-300 rounded-lg hover:bg-gray-50 hover:border-gray-400 disabled:opacity-50 transition-all"
            >
              Cancel
            </button>
            <button
              onClick={handleSubmit}
              disabled={submitting || !title.trim() || !description.trim()}
              className="px-6 py-2.5 text-sm font-semibold text-white bg-gradient-to-r from-purple-600 to-indigo-600 rounded-lg hover:from-purple-700 hover:to-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 shadow-lg hover:shadow-xl transition-all"
            >
              {submitting ? (
                <>
                  <div className="animate-spin rounded-full h-4 w-4 border-2 border-white border-t-transparent"></div>
                  <span>Submitting...</span>
                </>
              ) : (
                <>
                  <Bug size={18} />
                  <span>Submit Issue Report</span>
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
