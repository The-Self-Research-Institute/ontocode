package self.research.ontology.reasoner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReasonerWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReasonerWorkerApplication.class, args);
    }
}
