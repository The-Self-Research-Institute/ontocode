package self.research.ontology.owlEditor.service;

import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

final class FusekiHttpClients {

    static final int MAX_CONNECTIONS = 60;
    static final int CONNECT_TIMEOUT_MILLIS = 30_000;
    static final int SOCKET_TIMEOUT_MILLIS = 7_200_000;
    static final int POOL_WAIT_MILLIS = 10_000;

    private FusekiHttpClients() {
    }

    static String basicAuth(String user, String password) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static PoolingHttpClientConnectionManager pool() {
        PoolingHttpClientConnectionManager pool = new PoolingHttpClientConnectionManager();
        pool.setMaxTotal(MAX_CONNECTIONS);
        pool.setDefaultMaxPerRoute(MAX_CONNECTIONS);
        return pool;
    }

    static RequestConfig requestConfig() {
        return RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setSocketTimeout(SOCKET_TIMEOUT_MILLIS)
                .setConnectionRequestTimeout(POOL_WAIT_MILLIS)
                .build();
    }

    static CloseableHttpClient pooled(CredentialsProvider credentials, String basicAuthHeader) {
        HttpClientBuilder builder = HttpClients.custom()
                .setConnectionManager(pool())
                .setDefaultRequestConfig(requestConfig())
                .setDefaultCredentialsProvider(credentials);
        if (basicAuthHeader != null) {
            builder.addInterceptorFirst((HttpRequestInterceptor) (request, context) -> {
                if (!request.containsHeader("Authorization")) {
                    request.addHeader("Authorization", basicAuthHeader);
                }
            });
        }
        return builder.build();
    }
}
