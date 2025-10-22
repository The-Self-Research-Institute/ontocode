package self.research.ontology.swrl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class SwrlServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SwrlServiceApplication.class, args);
    }
}