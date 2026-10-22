import { File as FileIcon, FileCode, FileText, FileVideo, Image as ImageIcon } from "lucide-react";
import type { LucideIcon } from "lucide-react";

const ALLOWED_EXTENSIONS = [
  ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".svg", ".webp",
  ".pdf", ".doc", ".docx", ".txt", ".log", ".owl", ".ttl", ".rdf",
  ".mp4", ".webm", ".ogg", ".mov", ".m4v",
];

const ALLOWED_MIME_PREFIXES = [
  "image/",
  "video/",
  "application/pdf",
  "application/msword",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "text/",
  "application/rdf+xml",
  "application/x-turtle",
];

const VIDEO_EXTENSIONS = [".mp4", ".webm", ".ogg", ".mov", ".m4v"];
const TEXT_PREVIEW_EXTENSIONS = [".txt", ".log", ".owl", ".ttl", ".rdf"];
const TEXT_PREVIEW_LENGTH = 500;

export const ACCEPT_ATTRIBUTE =
  "image/*,video/*,.mp4,.webm,.ogg,.mov,.m4v,.pdf,.doc,.docx,.txt,.log,.owl,.ttl,.rdf";

export const SUPPORTED_FORMATS_LABEL = "JPG, PNG, MP4, WEBM, MOV, PDF, DOC, DOCX, TXT, .log, .owl, .ttl, .rdf";

const hasExtension = (file: File, extensions: string[]): boolean => {
  const name = file.name.toLowerCase();
  return extensions.some((ext) => name.endsWith(ext));
};

export const isVideoFile = (file: File): boolean =>
  file.type.startsWith("video/") || hasExtension(file, VIDEO_EXTENSIONS);

export const findUnsupportedFiles = (files: File[]): File[] =>
  files.filter((file) => {
    const type = file.type.toLowerCase();
    return !(hasExtension(file, ALLOWED_EXTENSIONS) || ALLOWED_MIME_PREFIXES.some((prefix) => type.startsWith(prefix)));
  });

const readAs = (file: File, mode: "dataUrl" | "text"): Promise<string | null> =>
  new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === "string" ? reader.result : null);
    reader.onerror = () => resolve(null);
    if (mode === "dataUrl") {reader.readAsDataURL(file);}
    else {reader.readAsText(file);}
  });

export const readPreview = async (file: File): Promise<string | null> => {
  if (file.type.startsWith("image/")) {return readAs(file, "dataUrl");}
  if (isVideoFile(file)) {return `video:${URL.createObjectURL(file)}`;}
  if (hasExtension(file, TEXT_PREVIEW_EXTENSIONS)) {
    const text = await readAs(file, "text");
    return text === null ? null : `text:${text.substring(0, TEXT_PREVIEW_LENGTH)}`;
  }
  if (hasExtension(file, [".pdf"])) {return "pdf:preview";}
  if (hasExtension(file, [".doc", ".docx"])) {return "word:preview";}
  return null;
};

export const releasePreview = (preview: string | undefined) => {
  if (preview?.startsWith("video:")) {URL.revokeObjectURL(preview.substring(6));}
};

export interface FileVisual {
  icon: LucideIcon;
  color: string;
  iconColor: string;
}

export const getFileVisual = (file: File): FileVisual => {
  if (file.type.startsWith("image/")) {return { icon: ImageIcon, color: "bg-green-100", iconColor: "text-green-600" };}
  if (isVideoFile(file)) {return { icon: FileVideo, color: "bg-indigo-100", iconColor: "text-indigo-600" };}
  if (hasExtension(file, [".pdf"])) {return { icon: FileIcon, color: "bg-red-100", iconColor: "text-red-600" };}
  if (hasExtension(file, [".log", ".txt"])) {return { icon: FileText, color: "bg-gray-100", iconColor: "text-gray-600" };}
  if (hasExtension(file, [".owl", ".ttl", ".rdf"])) {
    return { icon: FileCode, color: "bg-purple-100", iconColor: "text-purple-600" };
  }
  return { icon: FileText, color: "bg-blue-100", iconColor: "text-blue-600" };
};
