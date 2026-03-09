import React, { useState, useEffect } from 'react';
import { Bug, Upload, X, AlertCircle, CheckCircle } from 'lucide-react';

interface ReportIssueModalProps {
  projectName?: string;
  projectId?: string;
  ontologyFilePath?: string;
  onClose: () => void;
}

export const ReportIssueModal: React.FC<ReportIssueModalProps> = ({
  projectName,
  projectId,
  ontologyFilePath,
  onClose
}) => {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [stepsToReproduce, setStepsToReproduce] = useState('');
  const [userEmail, setUserEmail] = useState('');
  const [issueType, setIssueType] = useState('Task');
  const [includeErrorLogs, setIncludeErrorLogs] = useState(false);
  const [attachments, setAttachments] = useState<File[]>([]);
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
    
    // Validate file sizes
    const invalidFiles = files.filter((f: File) => f.size > 10 * 1024 * 1024); // 10MB
    if (invalidFiles.length > 0) {
      alert(`The following files exceed 10MB limit: ${invalidFiles.map((f: File) => f.name).join(', ')}`);
      return;
    }    // Limit to 5 files total
    const newAttachments = [...attachments, ...files].slice(0, 5);
    setAttachments(newAttachments);
    
    if (newAttachments.length >= 5 && files.length + attachments.length > 5) {
      alert('Maximum 5 attachments allowed');
    }
  };

  const removeAttachment = (index: number) => {
    setAttachments(attachments.filter((_, i) => i !== index));
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
      
      if (userEmail.trim()) {
        formData.append('userEmail', userEmail);
      }
      
      formData.append('issueType', issueType);
      
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

      // Add error logs if requested
      if (includeErrorLogs) {
        const errorLogs = await getErrorLogs();
        if (errorLogs) {
          formData.append('errorLogs', errorLogs);
        }
      }

      // Add attachments
      attachments.forEach((file) => {
        formData.append('attachments', file);
      });

      // Submit to backend
      const response = await fetch('http://localhost:8083/api/v1/issues/report', {
        method: 'POST',
        body: formData,
        credentials: 'include'
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

  const getErrorLogs = async (): Promise<string | null> => {
    try {
      // Request error logs from extension
      const response = await new Promise<string>((resolve) => {
        const handler = (event: MessageEvent) => {
          if (event.data.type === 'errorLogsResponse') {
            window.removeEventListener('message', handler);
            resolve(event.data.logs || '');
          }
        };
        window.addEventListener('message', handler);
        
        // Send request to extension
        (window as any).vscode?.postMessage({
          type: 'getErrorLogs'
        });

        // Timeout after 5 seconds
        setTimeout(() => {
          window.removeEventListener('message', handler);
          resolve('');
        }, 5000);
      });

      return response;
    } catch (error) {
      console.error('Failed to get error logs:', error);
      return null;
    }
  };

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl max-w-3xl w-full mx-4 max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="border-b border-gray-200 px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Bug className="text-purple-600" size={24} />
            <h2 className="text-xl font-semibold text-gray-900">
              Report an Issue
            </h2>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600"
            disabled={submitting}
          >
            <X size={24} />
          </button>
        </div>

        {/* Success/Error Message */}
        {submitResult && (
          <div className={`mx-6 mt-4 p-4 rounded-md flex items-start gap-3 ${
            submitResult.success 
              ? 'bg-green-50 border border-green-200' 
              : 'bg-red-50 border border-red-200'
          }`}>
            {submitResult.success ? (
              <CheckCircle className="text-green-600 flex-shrink-0" size={20} />
            ) : (
              <AlertCircle className="text-red-600 flex-shrink-0" size={20} />
            )}
            <div className="flex-1">
              <p className={`text-sm font-medium ${
                submitResult.success ? 'text-green-800' : 'text-red-800'
              }`}>
                {submitResult.message}
              </p>
              {submitResult.jiraUrl && (
                <a
                  href={submitResult.jiraUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-sm text-purple-600 hover:text-purple-800 underline mt-1 block"
                >
                  View in Jira →
                </a>
              )}
            </div>
          </div>
        )}

        {/* Content */}
        <div className="px-6 py-4 space-y-6">
          {/* Title */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Title *
            </label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Brief summary of the issue"
              maxLength={200}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400"
              disabled={submitting}
            />
            <p className="text-xs text-gray-500 mt-1">{title.length}/200 characters</p>
          </div>

          {/* Description */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Description *
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={4}
              placeholder="Describe the issue in detail..."
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400"
              disabled={submitting}
            />
          </div>

          {/* Steps to Reproduce */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Steps to Reproduce (Optional)
            </label>
            <textarea
              value={stepsToReproduce}
              onChange={(e) => setStepsToReproduce(e.target.value)}
              rows={4}
              placeholder="1. Go to...&#10;2. Click on...&#10;3. See error..."
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400"
              disabled={submitting}
            />
          </div>

          {/* Issue Type */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Issue Type *
            </label>
            <select
              value={issueType}
              onChange={(e) => setIssueType(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black"
              disabled={submitting}
            >
              <option value="Task">Task</option>
              <option value="Bug">Bug</option>
              <option value="Story">Story</option>
              <option value="Sub-task">Sub-task</option>
            </select>
            <p className="text-xs text-gray-500 mt-1">Select the type of issue you're reporting</p>
          </div>

          {/* User Email */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Your Email (Optional)
            </label>
            <input
              type="email"
              value={userEmail}
              onChange={(e) => setUserEmail(e.target.value)}
              placeholder="your.email@example.com"
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-purple-500 focus:border-purple-500 text-sm bg-white text-black placeholder-gray-400"
              disabled={submitting}
            />
            <p className="text-xs text-gray-500 mt-1">We may contact you for follow-up questions</p>
          </div>

          {/* Error Logs Checkbox */}
          <div>
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={includeErrorLogs}
                onChange={(e) => setIncludeErrorLogs(e.target.checked)}
                className="w-4 h-4 text-purple-600 rounded focus:ring-purple-500"
                disabled={submitting}
              />
              <span className="text-sm font-medium text-gray-700">
                Include recent error logs from extension output
              </span>
            </label>
          </div>

          {/* File Attachments */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">
              Screenshots/Attachments (Optional)
            </label>
            <div className="space-y-2">
              {/* Upload Button */}
              {attachments.length < 5 && (
                <label className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 cursor-pointer">
                  <Upload size={16} />
                  <span>Choose Files</span>
                  <input
                    type="file"
                    multiple
                    accept="image/*,.pdf,.txt,.log,.owl,.ttl,.rdf"
                    onChange={handleFileSelect}
                    className="hidden"
                    disabled={submitting}
                  />
                </label>
              )}

              {/* File List */}
              {attachments.length > 0 && (
                <div className="space-y-1">
                  {attachments.map((file, index) => (
                    <div
                      key={index}
                      className="flex items-center justify-between px-3 py-2 bg-gray-50 rounded border border-gray-200"
                    >
                      <span className="text-sm text-gray-700 truncate">{file.name}</span>
                      <button
                        onClick={() => removeAttachment(index)}
                        className="text-gray-400 hover:text-red-600 ml-2"
                        disabled={submitting}
                      >
                        <X size={16} />
                      </button>
                    </div>
                  ))}
                </div>
              )}

              <p className="text-xs text-gray-500">
                Max 5 files, 10MB each. Supported formats: images, PDF, text, logs, ontology files (.owl, .ttl, .rdf)
              </p>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="border-t border-gray-200 px-6 py-4 flex justify-end gap-3">
          <button
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting || !title.trim() || !description.trim()}
            className="px-4 py-2 text-sm font-medium text-white bg-purple-600 rounded-md hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
          >
            {submitting ? (
              <>
                <span className="animate-spin">⏳</span>
                <span>Submitting...</span>
              </>
            ) : (
              <>
                <Bug size={16} />
                <span>Submit Issue</span>
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};
