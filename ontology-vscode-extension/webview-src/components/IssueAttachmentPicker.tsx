import React, { useEffect, useRef, useState } from "react";
import { FileText, Upload, X } from "lucide-react";
import {
  ACCEPT_ATTRIBUTE,
  SUPPORTED_FORMATS_LABEL,
  findUnsupportedFiles,
  getFileVisual,
  readPreview,
  releasePreview,
} from "../utils/issueAttachments";

interface Props {
  attachments: File[];
  onChange: (files: File[]) => void;
  disabled: boolean;
}

const stop = (event: React.DragEvent) => {
  event.preventDefault();
  event.stopPropagation();
};

const Thumbnail: React.FC<{ file: File; preview?: string }> = ({ file, preview }) => {
  const { icon: Icon, color, iconColor } = getFileVisual(file);
  if (preview?.startsWith("video:")) {
    return <video src={preview.substring(6)} controls preload="metadata" className="w-full h-full object-cover rounded bg-black" />;
  }
  if (preview?.startsWith("text:")) {
    return (
      <div className="w-full h-full bg-white rounded border border-gray-200 p-2 overflow-hidden">
        <pre className="text-[7px] leading-tight text-gray-700 font-mono whitespace-pre-wrap break-all">{preview.substring(5)}</pre>
      </div>
    );
  }
  if (preview?.startsWith("pdf:") || preview?.startsWith("word:")) {
    const isPdf = preview.startsWith("pdf:");
    const tone = isPdf ? "from-red-50 to-red-100 border-red-200 text-red-600" : "from-blue-50 to-blue-100 border-blue-200 text-blue-600";
    return (
      <div className={`w-full h-full bg-gradient-to-br ${tone} rounded border-2 flex flex-col items-center justify-center p-2`}>
        <FileText size={28} className="mb-1" />
        <span className="text-xs font-bold">{isPdf ? "PDF" : "Word"}</span>
        <span className="text-[7px] mt-0.5">Document</span>
      </div>
    );
  }
  if (preview && file.type.startsWith("image/")) {
    return <img src={preview} alt={file.name} className="w-full h-full object-cover rounded" />;
  }
  return (
    <div className={`${color} p-3 rounded-lg`}>
      <Icon size={24} className={iconColor} />
    </div>
  );
};

export const IssueAttachmentPicker: React.FC<Props> = ({ attachments, onChange, disabled }) => {
  const [previews, setPreviews] = useState<Map<string, string>>(new Map());
  const [isDragging, setIsDragging] = useState(false);
  const [typeError, setTypeError] = useState<string | null>(null);
  const previewsRef = useRef(previews);
  previewsRef.current = previews;

  useEffect(() => () => previewsRef.current.forEach(releasePreview), []);

  const addFiles = (files: File[]) => {
    const unsupported = findUnsupportedFiles(files);
    if (unsupported.length > 0) {
      setTypeError(
        `Unsupported file types: ${unsupported.map((f) => f.name).join(", ")}. Supported: images, videos, PDF, Word, text, logs and ontology files.`,
      );
      return;
    }
    setTypeError(null);
    onChange([...attachments, ...files]);
    files.forEach(async (file) => {
      const preview = await readPreview(file);
      if (preview) {setPreviews((prev) => new Map(prev).set(file.name, preview));}
    });
  };

  const removeFile = (index: number) => {
    const file = attachments[index];
    releasePreview(previews.get(file.name));
    setPreviews((prev) => {
      const next = new Map(prev);
      next.delete(file.name);
      return next;
    });
    onChange(attachments.filter((_, i) => i !== index));
  };

  const compact = attachments.length > 0;

  return (
    <div className="space-y-3">
      <label className="flex items-center gap-2 text-sm font-semibold text-gray-800">
        <Upload size={16} className="text-purple-600" />
        Attachments
        <span className="text-xs font-normal text-gray-500">(Optional)</span>
      </label>
      {typeError && <p className="text-sm text-red-600">{typeError}</p>}
      <div
        className={`border-2 border-dashed rounded-lg transition-all ${isDragging ? "border-purple-500 bg-purple-50" : "border-gray-300 bg-gray-50 hover:bg-gray-100"}`}
        onDragOver={stop}
        onDragEnter={(e) => {
          stop(e);
          setIsDragging(true);
        }}
        onDragLeave={(e) => {
          stop(e);
          if (e.currentTarget === e.target) {setIsDragging(false);}
        }}
        onDrop={(e) => {
          stop(e);
          setIsDragging(false);
          addFiles(Array.from(e.dataTransfer.files));
        }}
      >
        <label className={`flex flex-col items-center justify-center cursor-pointer transition-all ${compact ? "py-3" : "py-8"}`}>
          <div className={`rounded-full transition-all ${compact ? "p-2 mb-2" : "p-3 mb-3"} ${isDragging ? "bg-purple-200 scale-110" : "bg-purple-100"}`}>
            <Upload size={compact ? 20 : 28} className={isDragging ? "text-purple-700" : "text-purple-600"} />
          </div>
          <span className={`font-medium ${compact ? "text-sm mb-1" : "text-base mb-1"} ${isDragging ? "text-purple-700" : "text-gray-700"}`}>
            {isDragging ? "Drop files here" : "Choose files to upload"}
          </span>
          <span className={`text-gray-500 ${compact ? "text-xs mb-1" : "text-sm mb-3"}`}>
            {isDragging ? "Release to upload" : "or drag and drop"}
          </span>
          <span className={`text-gray-500 ${compact ? "text-[10px]" : "text-xs"}`}>{SUPPORTED_FORMATS_LABEL}</span>
          <input
            type="file"
            multiple
            accept={ACCEPT_ATTRIBUTE}
            onChange={(e) => addFiles(Array.from(e.target.files || []))}
            className="hidden"
            disabled={disabled}
          />
        </label>
        {compact && (
          <div className="border-t border-gray-200 pt-4 px-4 pb-2">
            <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
              {attachments.map((file, index) => (
                <div key={`${file.name}-${index}`} className="relative group bg-white rounded-lg border-2 border-gray-200 hover:border-purple-400 shadow-sm hover:shadow-md transition-all overflow-hidden">
                  <button
                    onClick={() => removeFile(index)}
                    className="absolute top-2 right-2 p-1.5 bg-white rounded-full border border-gray-300 text-gray-400 hover:text-red-600 hover:bg-red-50 hover:border-red-300 transition-colors opacity-0 group-hover:opacity-100 z-10 shadow-md"
                    disabled={disabled}
                    title="Remove file"
                  >
                    <X size={14} />
                  </button>
                  <div className="w-full aspect-[4/3] flex items-center justify-center p-2 bg-gray-50">
                    <Thumbnail file={file} preview={previews.get(file.name)} />
                  </div>
                  <div className="p-2 border-t border-gray-100">
                    <p className="text-xs font-medium text-gray-800 text-center truncate w-full" title={file.name}>{file.name}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default IssueAttachmentPicker;
