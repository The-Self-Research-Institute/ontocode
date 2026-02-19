/**
 * Upload Performance Configuration
 * Adjust these settings to optimize upload performance based on your needs
 */

export interface UploadPerformanceConfig {
    // Whether to enable file compression for uploads
    enableCompression: boolean;

    // Minimum file size (in bytes) to trigger compression
    compressionThreshold: number;

    // Maximum number of retry attempts for failed uploads
    maxRetries: number;

    // Upload timeout in milliseconds
    uploadTimeout: number;

    // Whether to enable chunked uploads for large files
    // Note: Backend must support chunked uploads for this to work
    enableChunkedUpload: boolean;

    // Chunk size for chunked uploads (in bytes)
    chunkSize: number;

    // Minimum file size to trigger chunked upload (in bytes)
    chunkedUploadThreshold: number;
}

// Default configuration - optimized for most use cases
export const DEFAULT_UPLOAD_CONFIG: UploadPerformanceConfig = {
    enableCompression: true,
    compressionThreshold: 1 * 1024 * 1024, // 1MB
    maxRetries: 3,
    uploadTimeout: 30 * 60 * 1000, // 30 minutes for large files + GraphDB processing
    enableChunkedUpload: false, // Disabled by default (requires backend support)
    chunkSize: 5 * 1024 * 1024, // 5MB chunks
    chunkedUploadThreshold: 50 * 1024 * 1024 // 50MB
};

// Configuration for slow/unreliable networks
export const SLOW_NETWORK_CONFIG: UploadPerformanceConfig = {
    ...DEFAULT_UPLOAD_CONFIG,
    maxRetries: 5,
    uploadTimeout: 20 * 60 * 1000, // 20 minutes
    chunkSize: 2 * 1024 * 1024, // Smaller 2MB chunks
    chunkedUploadThreshold: 10 * 1024 * 1024 // Enable chunking at 10MB
};

// Configuration for fast networks with large files
export const FAST_NETWORK_CONFIG: UploadPerformanceConfig = {
    ...DEFAULT_UPLOAD_CONFIG,
    compressionThreshold: 5 * 1024 * 1024, // Only compress files > 5MB
    chunkSize: 10 * 1024 * 1024, // Larger 10MB chunks
    chunkedUploadThreshold: 100 * 1024 * 1024 // Enable chunking at 100MB
};

// Configuration for very large files (100MB+) with GraphDB processing
export const LARGE_FILE_GRAPHDB_CONFIG: UploadPerformanceConfig = {
    enableCompression: true,
    compressionThreshold: 512 * 1024, // Compress everything > 512KB
    maxRetries: 5,
    uploadTimeout: 60 * 60 * 1000, // 60 minutes for large GraphDB imports
    enableChunkedUpload: false, // Enable when backend supports it
    chunkSize: 10 * 1024 * 1024, // 10MB chunks for processing
    chunkedUploadThreshold: 100 * 1024 * 1024 // 100MB
};

// Get the active configuration
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
