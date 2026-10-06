package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.dto.ProposeEditRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JacksonRecordTest {

    @Test
    void fuzzyMembershipsSurviveJsonDeserialization() throws Exception {
        String json = "{\"groups\":[{\"clientGroupId\":\"c1\",\"operation\":{\"type\":\"add_fuzzy_membership\","
                + "\"targetPath\":\"turtle\",\"memberships\":[{\"entityIri\":\"http://ex.org/alice\","
                + "\"classIri\":\"http://ex.org/Diabetic\",\"degree\":0.9}]}}]}";

        ProposeEditRequest request = new ObjectMapper().readValue(json, ProposeEditRequest.class);

        ProposeEditRequest.EditOperation op = request.groups().get(0).operation();
        assertNotNull(op.memberships(), "memberships should not be null when present in JSON");
        assertEquals(1, op.memberships().size());
        assertEquals("http://ex.org/alice", op.memberships().get(0).entityIri());
        assertEquals(0.9, op.memberships().get(0).degree());
    }

    @Test
    void inferredAxiomsSurviveJsonDeserialization() throws Exception {
        String json = "{\"groups\":[{\"clientGroupId\":\"c1\",\"operation\":{\"type\":\"add_inferred_axioms\","
                + "\"targetPath\":\"turtle\",\"axioms\":[{\"axiomType\":\"ClassAssertion\","
                + "\"subjectIri\":\"http://ex.org/a\",\"predicateIri\":\"http://ex.org/p\"}]}}]}";

        ProposeEditRequest request = new ObjectMapper().readValue(json, ProposeEditRequest.class);

        ProposeEditRequest.EditOperation op = request.groups().get(0).operation();
        assertNotNull(op.axioms(), "axioms should not be null when present in JSON");
        assertEquals(1, op.axioms().size());
    }
}
