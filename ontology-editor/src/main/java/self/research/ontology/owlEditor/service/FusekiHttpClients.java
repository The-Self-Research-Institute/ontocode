package self.research.ontology.owlEditor.service;

import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class FusekiHttpClients {

    static final int MAX_CONNECTIONS = 60;
    static final int CONNECT_TIMEOUT_MILLIS = 30_000;
    static final int SOCKET_TIMEOUT_MILLIS = 7_200_000;
    static final int POOL_WAIT_MILLIS = 10_000;
    static final int COUNT_BUDGET_MILLIS = 25_000;
    private static final ThreadLocal<Long> COUNT_DEADLINE = new ThreadLocal<>();

    private FusekiHttpClients() {
    }

    static String basicAuth(String user, String password) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static long boundedCount(LongSupplier count) {
        COUNT_DEADLINE.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COUNT_BUDGET_MILLIS));
        try {
            return count.getAsLong();
        } finally {
            COUNT_DEADLINE.remove();
        }
    }

    private static int remainingCountMillis() {
        Long deadline = COUNT_DEADLINE.get();
        if (deadline == null) {
            return 0;
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new IllegalStateException("Fuseki graph count exceeded its time budget");
        }
        return Math.max(1, (int) TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    static int countQueryTimeoutSeconds() {
        return (remainingCountMillis() + 999) / 1000;
    }

    private static int limit(int configured, int budget) {
        return configured > 0 ? Math.min(configured, budget) : budget;
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
        return HttpClients.custom()
                .setConnectionManager(pool())
                .setDefaultRequestConfig(requestConfig())
                .setDefaultCredentialsProvider(credentials)
                .addInterceptorFirst((HttpRequestInterceptor) (request, context) -> {
                    int remaining = remainingCountMillis();
                    if (remaining > 0) {
                        HttpClientContext requestContext = HttpClientContext.adapt(context);
                        RequestConfig config = requestContext.getRequestConfig();
                        requestContext.setRequestConfig(RequestConfig.copy(config)
                                .setConnectTimeout(limit(config.getConnectTimeout(), Math.min(5_000, remaining)))
                                .setConnectionRequestTimeout(limit(config.getConnectionRequestTimeout(), remaining))
                                .setSocketTimeout(limit(config.getSocketTimeout(), remaining))
                                .build());
                    }
                    if (basicAuthHeader != null && !request.containsHeader("Authorization")) {
                        request.addHeader("Authorization", basicAuthHeader);
                    }
                })
                .build();
    }
}
