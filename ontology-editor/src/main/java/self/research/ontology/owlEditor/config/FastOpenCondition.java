package self.research.ontology.owlEditor.config;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ConfigurationCondition;

/**
 * Enables OWLAPI in-memory warm (fast-open) on desktop builds and on cloud
 * when {@code ontocode.fastopen.enabled=true} (default).
 */
public class FastOpenCondition extends AnyNestedCondition {

    public FastOpenCondition() {
        super(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
    static class DesktopMode {}

    @ConditionalOnProperty(name = "ontocode.fastopen.enabled", havingValue = "true", matchIfMissing = true)
    static class FastOpenEnabled {}
}
