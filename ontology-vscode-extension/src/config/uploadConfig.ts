

export interface UploadPerformanceConfig {

    enableCompression: boolean;

    compressionThreshold: number;

    maxRetries: number;

    uploadTimeout: number;

    enableChunkedUpload: boolean;

    chunkSize: number;

    chunkedUploadThreshold: number;
}

export const DEFAULT_UPLOAD_CONFIG: UploadPerformanceConfig = {
    enableCompression: true,
    compressionThreshold: 1 * 1024 * 1024, // 1MB
    maxRetries: 3,
    uploadTimeout: 120 * 60 * 1000, // 2 hours for uploads up to 1GB
    enableChunkedUpload: true, // Backend now supports chunked uploads (POST /api/ontology/upload-chunk)
    chunkSize: 20 * 1024 * 1024, // 20MB chunks
    chunkedUploadThreshold: 40 * 1024 * 1024 // 40MB — well under Cloudflare's 100MB proxy cap
};

export const SLOW_NETWORK_CONFIG: UploadPerformanceConfig = {
    ...DEFAULT_UPLOAD_CONFIG,
    maxRetries: 5,
    uploadTimeout: 120 * 60 * 1000, // 2 hours for uploads up to 1GB
    chunkSize: 2 * 1024 * 1024, // Smaller 2MB chunks
    chunkedUploadThreshold: 10 * 1024 * 1024 // Enable chunking at 10MB
};

export const FAST_NETWORK_CONFIG: UploadPerformanceConfig = {
    ...DEFAULT_UPLOAD_CONFIG,
    compressionThreshold: 5 * 1024 * 1024, // Only compress files > 5MB
    chunkSize: 10 * 1024 * 1024, // Larger 10MB chunks
    chunkedUploadThreshold: 100 * 1024 * 1024 // Enable chunking at 100MB
};

export const LARGE_FILE_GRAPHDB_CONFIG: UploadPerformanceConfig = {
    enableCompression: true,
    compressionThreshold: 512 * 1024, // Compress everything > 512KB
    maxRetries: 5,
    uploadTimeout: 120 * 60 * 1000, // 2 hours for uploads up to 1GB
    enableChunkedUpload: true, // Backend now supports chunked uploads (POST /api/ontology/upload-chunk)
    chunkSize: 10 * 1024 * 1024, // 10MB chunks for processing
    chunkedUploadThreshold: 100 * 1024 * 1024 // 100MB
};

let activeConfig = DEFAULT_UPLOAD_CONFIG;

export function setUploadConfig(config: Partial<UploadPerformanceConfig>) {
    activeConfig = { ...activeConfig, ...config };
}

export function getUploadConfig(): UploadPerformanceConfig {
    return activeConfig;
}

export function resetUploadConfig() {
    activeConfig = DEFAULT_UPLOAD_CONFIG;
}
