package self.research.ontology.swrl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.data.mongodb.host=localhost",
    "ontology.editor.service.url=http://localhost:8086"
})
class SwrlServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}