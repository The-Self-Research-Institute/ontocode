package self.research.ontology.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import self.research.ontology.auth.repository.UserRepository;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = AuthApplication.class, properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none?connectTimeout=1&socketTimeout=2",
        "spring.datasource.username=nobody",
        "spring.datasource.password=nothing",
        "spring.datasource.hikari.connection-timeout=1000",
        "jwt.secret=dGVzdC1vbmx5LWp3dC1zZWNyZXQtdGVzdC1vbmx5LWp3dC1zZWNyZXQtdGVzdA==",
        "spring.mail.password=unused",
        "stripe.api.secret-key=sk_test_unused",
        "stripe.api.publishable-key=pk_test_unused",
        "stripe.webhook.secret=whsec_unused",
        "stripe.price.pro-monthly=price_test_pro_monthly",
        "stripe.price.pro-yearly=price_test_pro_yearly",
        "stripe.price.enterprise-monthly=price_test_ent_monthly",
        "stripe.price.enterprise-yearly=price_test_ent_yearly",
        "app.base-url=http://localhost:8080",
        "security.rate-limit.enabled=false"
})
class DatabaseDownStartupTest {

    @Autowired
    UserRepository users;

    @Test
    void theServiceStartsWithoutTheDatabaseAndRepositoriesFailWithADataAccessError() {
        assertNotNull(users);
        assertThrows(DataAccessException.class, () -> users.findByEmail("someone@example.com"));
    }
}
