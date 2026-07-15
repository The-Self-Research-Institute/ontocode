/**
 * Upload Performance Configuration
 * Adjust these settings to optimize upload performance based on your needs
 *
 * NOTE: this file is a reference/planning config — the actual chunk size and threshold
 * used at runtime are the constants defined directly in extension.ts's uploadOntologyInChunks
 * and vscodeBridge.ts/uploadWithProgress.ts's uploadBlobInChunks (both 40MB threshold / 20MB
 * chunks). Backend support for chunked uploads now exists (POST /api/ontology/upload-chunk),
 * so enableChunkedUpload below reflects that — update both places together if you retune sizes.
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
    uploadTimeout: 120 * 60 * 1000, // 2 hours for uploads up to 1GB
    enableChunkedUpload: true, // Backend now supports chunked uploads (POST /api/ontology/upload-chunk)
    chunkSize: 20 * 1024 * 1024, // 20MB chunks
    chunkedUploadThreshold: 40 * 1024 * 1024 // 40MB — well under Cloudflare's 100MB proxy cap
};

// Configuration for slow/unreliable networks
export const SLOW_NETWORK_CONFIG: UploadPerformanceConfig = {
    ...DEFAULT_UPLOAD_CONFIG,
    maxRetries: 5,
    uploadTimeout: 120 * 60 * 1000, // 2 hours for uploads up to 1GB
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
    uploadTimeout: 120 * 60 * 1000, // 2 hours for uploads up to 1GB
    enableChunkedUpload: true, // Backend now supports chunked uploads (POST /api/ontology/upload-chunk)
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
