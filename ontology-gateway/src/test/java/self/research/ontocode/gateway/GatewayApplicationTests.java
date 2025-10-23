package self.research.ontocode.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.cloud.gateway.routes[0].id=test-route",
    "spring.cloud.gateway.routes[0].uri=http://httpbin.org:80",
    "spring.cloud.gateway.routes[0].predicates[0]=Path=/test/**"
})
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}