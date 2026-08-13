

export interface UploadConfig {
    chunkSize: number; // Size of each chunk in bytes (default: 5MB)
    maxRetries: number; // Maximum retry attempts per chunk
    enableCompression: boolean; // Whether to compress before upload
    onProgress?: (percent: number, loaded: number, total: number) => void;
}

export interface ChunkMetadata {
    chunkIndex: number;
    totalChunks: number;
    chunkHash: string;
    fileName: string;
}

const DEFAULT_CONFIG: UploadConfig = {
    chunkSize: 5 * 1024 * 1024, // 5MB chunks
    maxRetries: 3,
    enableCompression: true
};

function simpleHash(data: Uint8Array): string {
    let hash = 0;
    for (let i = 0; i < data.length; i++) {
        hash = ((hash << 5) - hash) + data[i];
        hash = hash & hash; // Convert to 32bit integer
    }
    return Math.abs(hash).toString(36);
}

async function compressData(data: Uint8Array): Promise<Uint8Array> {
    try {

        if (typeof (globalThis as any).CompressionStream !== 'undefined') {
            const blob = new Blob([data]);
            const stream = blob.stream().pipeThrough(new (globalThis as any).CompressionStream('gzip'));
            const compressedBlob = await new Response(stream).blob();
            return new Uint8Array(await compressedBlob.arrayBuffer());
        }

        console.warn('[UploadOptimizer] Compression not available, uploading uncompressed');
        return data;
    } catch (error) {
        console.error('[UploadOptimizer] Compression failed, using uncompressed:', error);
        return data;
    }
}

export function splitIntoChunks(data: Uint8Array, chunkSize: number): Uint8Array[] {
    const chunks: Uint8Array[] = [];
    let offset = 0;

    while (offset < data.length) {
        const end = Math.min(offset + chunkSize, data.length);
        chunks.push(data.slice(offset, end));
        offset = end;
    }

    return chunks;
}

async function uploadChunkWithRetry(
    uploadFn: (chunk: Uint8Array, metadata: ChunkMetadata) => Promise<any>,
    chunk: Uint8Array,
    metadata: ChunkMetadata,
    maxRetries: number
): Promise<any> {
    let lastError: Error | null = null;

    for (let attempt = 0; attempt < maxRetries; attempt++) {
        try {
            if (attempt > 0) {

                const delay = Math.pow(2, attempt) * 1000;
                console.log(`[UploadOptimizer] Retrying chunk ${metadata.chunkIndex + 1}/${metadata.totalChunks} after ${delay}ms delay`);
                await new Promise(resolve => setTimeout(resolve, delay));
            }

            return await uploadFn(chunk, metadata);
        } catch (error) {
            lastError = error as Error;
            console.error(`[UploadOptimizer] Chunk upload attempt ${attempt + 1} failed:`, error);

            if (error && typeof error === 'object' && 'status' in error) {
                const status = (error as any).status;
                if (status === 401 || status === 403) {
                    throw error; // Don't retry auth errors
                }
            }
        }
    }

    throw new Error(`Failed to upload chunk after ${maxRetries} attempts: ${lastError?.message}`);
}

export async function optimizedUpload(
    fileData: Uint8Array,
    fileName: string,
    uploadFn: (chunk: Uint8Array, metadata: ChunkMetadata) => Promise<any>,
    config: Partial<UploadConfig> = {}
): Promise<void> {
    const finalConfig = { ...DEFAULT_CONFIG, ...config };

    console.log(`[UploadOptimizer] Starting optimized upload for ${fileName}`);
    console.log(`[UploadOptimizer] Original size: ${fileData.length} bytes`);
    console.log(`[UploadOptimizer] Compression: ${finalConfig.enableCompression ? 'enabled' : 'disabled'}`);

    let processedData = fileData;
    if (finalConfig.enableCompression) {
        const startTime = Date.now();
        processedData = await compressData(fileData);
        const compressionTime = Date.now() - startTime;
        const compressionRatio = ((1 - processedData.length / fileData.length) * 100).toFixed(1);
        console.log(`[UploadOptimizer] Compressed to ${processedData.length} bytes (${compressionRatio}% reduction) in ${compressionTime}ms`);
    }

    const shouldChunk = processedData.length > finalConfig.chunkSize;

    if (!shouldChunk) {
        console.log(`[UploadOptimizer] File is small enough, uploading in single request`);
        const metadata: ChunkMetadata = {
            chunkIndex: 0,
            totalChunks: 1,
            chunkHash: simpleHash(processedData),
            fileName
        };
        await uploadChunkWithRetry(uploadFn, processedData, metadata, finalConfig.maxRetries);
        finalConfig.onProgress?.(100, processedData.length, processedData.length);
        return;
    }

    const chunks = splitIntoChunks(processedData, finalConfig.chunkSize);
    console.log(`[UploadOptimizer] Split into ${chunks.length} chunks of ~${finalConfig.chunkSize / (1024 * 1024)}MB each`);

    let uploadedBytes = 0;
    const totalBytes = processedData.length;

    for (let i = 0; i < chunks.length; i++) {
        const chunk = chunks[i];
        const metadata: ChunkMetadata = {
            chunkIndex: i,
            totalChunks: chunks.length,
            chunkHash: simpleHash(chunk),
            fileName
        };

        console.log(`[UploadOptimizer] Uploading chunk ${i + 1}/${chunks.length} (${chunk.length} bytes)`);

        await uploadChunkWithRetry(uploadFn, chunk, metadata, finalConfig.maxRetries);

        uploadedBytes += chunk.length;
        const percent = Math.round((uploadedBytes / totalBytes) * 100);
        finalConfig.onProgress?.(percent, uploadedBytes, totalBytes);

        console.log(`[UploadOptimizer] Chunk ${i + 1}/${chunks.length} uploaded successfully (${percent}% complete)`);
    }

    console.log(`[UploadOptimizer] All chunks uploaded successfully`);
}

export function shouldCompressFile(fileName: string): boolean {
    const compressibleExtensions = ['.owl', '.rdf', '.ttl', '.n3', '.nt', '.jsonld', '.xml', '.txt'];
    const extension = fileName.toLowerCase().substring(fileName.lastIndexOf('.'));
    return compressibleExtensions.includes(extension);
}
