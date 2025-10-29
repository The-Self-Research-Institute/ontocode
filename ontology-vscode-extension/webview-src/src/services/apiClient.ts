import axios from 'axios';

// The base URL for the API gateway, consistent with the VS Code extension.
const GATEWAY_URL = 'http://localhost:8082';

// Create an Axios instance with a base URL and default timeout.
const axiosInstance = axios.create({
    baseURL: GATEWAY_URL,
    timeout: 60000, // 60 second timeout for potentially long requests
    headers: {
        'Content-Type': 'application/json'
    }
});

/**
 * apiClient provides a centralized place for all API calls.
 * It's designed to be a drop-in replacement for the previous mock implementation,
 * ensuring that components using it don't need to be changed.
 */
const apiClient = {
    /**
     * Performs a GET request using Axios.
     * @param url The endpoint URL (e.g., '/api/ontology/metadata/pizza').
     * @param config Optional Axios request configuration, typically for URL params.
     * @returns A promise that resolves to the Axios response object. The calling code
     *          expects an object with a `data` property, which matches the Axios response.
     */
    get: async (url: string, config?: { params?: any }): Promise<{ data: any }> => {
        console.log(`[API] GET: ${axiosInstance.getUri({ url, ...config })}`);
        return axiosInstance.get(url, config);
    },

    /**
     * Performs a POST request using Axios.
     * @param url The endpoint URL to post to.
     * @param body The request payload.
     * @returns A promise that resolves to the Axios response object.
     */
    post: async (url: string, body?: any): Promise<{ data: any }> => {
        console.log(`[API] POST: ${axiosInstance.getUri({ url })}`, body);
        return axiosInstance.post(url, body);
    },

    /**
     * Performs a DELETE request using Axios.
     * @param url The endpoint URL to send the delete request to.
     * @returns A promise that resolves to the Axios response object.
     */
    delete: async (url: string): Promise<{ data: any }> => {
        console.log(`[API] DELETE: ${axiosInstance.getUri({ url })}`);
        return axiosInstance.delete(url);
    }
};

export default apiClient;
