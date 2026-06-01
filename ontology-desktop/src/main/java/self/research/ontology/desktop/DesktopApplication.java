package self.research.ontology.desktop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import self.research.ontology.auth.AuthApplication;
import self.research.ontology.owlEditor.OwlEditorApplication;
import self.research.ontology.owlEditor.controller.DesktopController;
import self.research.ontology.owlEditor.controller.ProjectController;
import self.research.ontology.plugins.OntologyPluginServiceApplication;
import self.research.ontology.plugins.config.SecurityConfig;

@SpringBootApplication
@ComponentScan(
    basePackages = {
        "self.research.ontology.auth",
        "self.research.ontology.owlEditor",
        "self.research.ontology.plugins"
    },
    nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {
            AuthApplication.class,
            OwlEditorApplication.class,
            OntologyPluginServiceApplication.class,
            DesktopController.class,   // auth stubs — replaced by real auth
            SecurityConfig.class,       // plugin security — auth chain handles all
            ProjectController.class     // editor's minimal version — auth's full version used
        }
    )
)
@EnableScheduling
@EnableMongoRepositories(basePackages = {
    "self.research.ontology.auth",
    "self.research.ontology.owlEditor",
    "self.research.ontology.plugins"
})
public class DesktopApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DesktopApplication.class);
        app.setBeanNameGenerator(new FullyQualifiedAnnotationBeanNameGenerator());
        app.run(args);
    }
}
