package self.research.ontology.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailMaskerTest {

    @Test
    void anAddressKeepsItsFirstLetterAndDomain() {
        assertEquals("d***@gmail.com", EmailMasker.mask("divahartest09@gmail.com"));
    }

    @Test
    void severalAddressesInOneLineAreAllMasked() {
        assertEquals("share a***@x.org with b***@y.co.uk now", EmailMasker.mask("share alice@x.org with bob@y.co.uk now"));
    }

    @Test
    void plusAddressingAndDotsAreCovered() {
        assertEquals("j***@corp.example.com", EmailMasker.mask("john.smith+tag@corp.example.com"));
    }

    @Test
    void anEmailInsideAUrlPathIsMasked() {
        assertEquals("GET /api/v1/issues/user/a***@x.org HTTP", EmailMasker.mask("GET /api/v1/issues/user/alice@x.org HTTP"));
    }

    @Test
    void textWithoutAnAddressIsLeftAlone() {
        String text = "user=u-123 proj=p1 at 10:00 @home";

        assertSame(text, EmailMasker.mask(text));
        assertNull(EmailMasker.mask(null));
    }

    @Test
    void theMessageConverterMasksTheFormattedMessage() {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test");
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.INFO, "Signup blocked for: {}", null,
                new Object[]{"victim@corp.com"});

        String line = new EmailMaskingMessageConverter().convert(event);

        assertEquals("Signup blocked for: v***@corp.com", line);
    }

    @Test
    void theThrowableConverterMasksAddressesInTheStackTrace() {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test");
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.ERROR, "failed", new IllegalStateException(
                "no user alice@x.org"), null);
        EmailMaskingThrowableConverter converter = new EmailMaskingThrowableConverter();
        converter.start();

        String trace = converter.convert(event);

        assertTrue(trace.contains("a***@x.org"));
        assertFalse(trace.contains("alice@x.org"));
    }
}
