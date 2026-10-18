package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import self.research.ontology.owlEditor.config.WebMvcConfig;
import self.research.ontology.owlEditor.service.ReasonerType;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ReasonerTypeBindingTest {

    private static final String CLASS_DETAILS_URL = "/api/ontology/p1/reasoner/inferred-class-details";
    private static final String PROPERTY_DETAILS_URL = "/api/ontology/p1/reasoner/inferred-property-details";

    private static FormattingConversionService mvcConversionService() {
        FormattingConversionService conversion = new DefaultFormattingConversionService();
        new WebMvcConfig(null, null, null, null, null).addFormatters(conversion);
        return conversion;
    }

    private static MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new ReasonerController())
                .setConversionService(mvcConversionService())
                .build();
    }

    @Test
    void converterAcceptsIdsAndDisplayLabelsInAnyCase() {
        FormattingConversionService conversion = mvcConversionService();
        for (ReasonerType type : ReasonerType.values()) {
            assertEquals(type, conversion.convert(type.name(), ReasonerType.class));
            assertEquals(type, conversion.convert(type.getDisplayName(), ReasonerType.class));
            assertEquals(type, conversion.convert(type.name().toLowerCase(Locale.ROOT), ReasonerType.class));
            assertEquals(type, conversion.convert(" " + type.getDisplayName() + " ", ReasonerType.class));
        }
        assertThrows(ConversionFailedException.class, () -> conversion.convert("Bogus", ReasonerType.class));
    }

    @Test
    void endpointsBindDisplayLabelsAndIds() throws Exception {
        MockMvc mvc = mvc();
        String[] values = {"HermiT", "Pellet", "Openllet", "Structural", "FaCT++",
                "HERMIT", "PELLET", "OPENLLET", "STRUCTURAL", "ELK"};
        for (String value : values) {
            MvcResult classResult = mvc.perform(get(CLASS_DETAILS_URL)
                    .param("classIri", "http://example.org/onto#A")
                    .param("reasonerType", value)).andReturn();
            assertFalse(classResult.getResolvedException() instanceof MethodArgumentTypeMismatchException,
                    "inferred-class-details rejected reasonerType=" + value);
            assertEquals(200, classResult.getResponse().getStatus());

            MvcResult propertyResult = mvc.perform(get(PROPERTY_DETAILS_URL)
                    .param("propertyIri", "http://example.org/onto#p")
                    .param("type", value)).andReturn();
            assertFalse(propertyResult.getResolvedException() instanceof MethodArgumentTypeMismatchException,
                    "inferred-property-details rejected type=" + value);
            assertEquals(200, propertyResult.getResponse().getStatus());
        }
    }

    @Test
    void endpointStillRejectsUnknownReasoner() throws Exception {
        MvcResult result = mvc().perform(get(CLASS_DETAILS_URL)
                .param("classIri", "http://example.org/onto#A")
                .param("reasonerType", "Bogus")).andReturn();
        assertInstanceOf(MethodArgumentTypeMismatchException.class, result.getResolvedException());
        assertEquals(400, result.getResponse().getStatus());
    }
}
