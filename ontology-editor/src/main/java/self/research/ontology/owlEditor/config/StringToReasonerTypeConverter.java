package self.research.ontology.owlEditor.config;

import org.springframework.core.convert.converter.Converter;
import self.research.ontology.owlEditor.service.ReasonerType;

public class StringToReasonerTypeConverter implements Converter<String, ReasonerType> {

    @Override
    public ReasonerType convert(String source) {
        String value = source.trim();
        if (value.isEmpty()) {
            return null;
        }
        for (ReasonerType type : ReasonerType.values()) {
            if (type.name().equalsIgnoreCase(value) || type.getDisplayName().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown reasoner type: " + source);
    }
}
