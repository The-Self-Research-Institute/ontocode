package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeViewSaveValidatorTest {

    private final CodeViewSaveValidator validator = new CodeViewSaveValidator(null);

    private static String classes(int count) {
        StringBuilder sb = new StringBuilder("@prefix : <http://example.org/> .\n@prefix owl: <http://www.w3.org/2002/07/owl#> .\n");
        for (int i = 0; i < count; i++) {
            sb.append(":C").append(i).append(" a owl:Class .\n");
        }
        return sb.toString();
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void savingLessThanHalfOfALargeOntologyIsRejectedAsSuspicious() {
        Optional<CodeViewSaveValidator.Rejection> rejection =
                validator.validate("p", "turtle", classes(10), bytes(classes(30)), false);

        assertTrue(rejection.isPresent());
        assertEquals(409, rejection.get().status());
        assertEquals("SUSPICIOUS_SIZE_REDUCTION", rejection.get().body().get("errorType"));
        assertEquals(30, rejection.get().body().get("oldAxiomCount"));
        assertEquals(10, rejection.get().body().get("newAxiomCount"));
    }

    @Test
    void anIntentionalLargeDeletionGoesThroughOnceConfirmed() {
        assertTrue(validator.validate("p", "turtle", classes(10), bytes(classes(30)), true).isEmpty());
    }

    @Test
    void keepingAtLeastHalfIsNotFlagged() {
        assertTrue(validator.validate("p", "turtle", classes(15), bytes(classes(30)), false).isEmpty());
    }

    @Test
    void smallOntologiesAreNeverFlagged() {
        assertTrue(validator.validate("p", "turtle", classes(2), bytes(classes(10)), false).isEmpty());
    }

    @Test
    void aSaveWithNoPreviousContentIsNotFlagged() {
        assertTrue(validator.validate("p", "turtle", classes(1), null, false).isEmpty());
    }

    @Test
    void unparseableContentIsStillRejectedAsUnprocessable() {
        Optional<CodeViewSaveValidator.Rejection> rejection =
                validator.validate("p", "turtle", "this is not turtle @@@ <<<" + "x".repeat(2100), bytes(classes(30)), false);

        assertTrue(rejection.isPresent());
        assertEquals(422, rejection.get().status());
    }
}
