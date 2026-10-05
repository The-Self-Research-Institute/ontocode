package self.research.ontocode.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.cloud.gateway.routes[0].id=test-route",
    "spring.cloud.gateway.routes[0].uri=http://httpbin.org:80",
    "spring.cloud.gateway.routes[0].predicates[0]=Path=/test/**"
})
class GatewayCorsConfigTest {

    @Autowired
    private WebTestClient client;

    @Test
    void aPathWithNoRouteIsReportedAsNotFoundRatherThanBadGateway() {
        client.get().uri("/api/v1/no-such-route")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().json("{\"error\":\"Not Found\"}");
    }

    @Test
    void anErrorThatAlreadyCarriesAStatusKeepsIt() {
        assertEquals(HttpStatus.NOT_FOUND, GatewayCorsConfig.resolveStatus(new ResponseStatusException(HttpStatus.NOT_FOUND)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                GatewayCorsConfig.resolveStatus(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)));
    }

    @Test
    void anUnreachableUpstreamIsStillABadGateway() {
        assertEquals(HttpStatus.BAD_GATEWAY, GatewayCorsConfig.resolveStatus(new ConnectException("Connection refused")));
    }

    @Test
    void anUpstreamTimeoutIsStillAGatewayTimeout() {
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, GatewayCorsConfig.resolveStatus(new TimeoutException()));
    }
}
