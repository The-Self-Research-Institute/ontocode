package self.research.ontology.owlEditor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import self.research.ontology.owlEditor.util.RdfXmlDefaults;

@SpringBootApplication
public class OwlEditorApplication {

	public static void main(String[] args) {
		RdfXmlDefaults.apply();
		SpringApplication.run(OwlEditorApplication.class, args);
	}
}