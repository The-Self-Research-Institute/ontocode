package self.research.ontology.common.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogContextTest {

    @Test
    void aUserIdIsShownAsUserEqualsId() {
        assertEquals("user=6a59f59ff5397e39516628ab", LogContext.userLabel("6a59f59ff5397e39516628ab"));
    }

    @Test
    void noUserIdIsAnonymous() {
        assertEquals("(anon)", LogContext.userLabel(null));
        assertEquals("(anon)", LogContext.userLabel(""));
    }

    @Test
    void anIdThatCouldForgeALogLineIsNotPrinted() {
        assertEquals("(anon)", LogContext.userLabel("abc\nINFO fake line"));
        assertEquals("(anon)", LogContext.userLabel("someone@x.org"));
        assertEquals("(anon)", LogContext.userLabel("a".repeat(65)));
    }
}
