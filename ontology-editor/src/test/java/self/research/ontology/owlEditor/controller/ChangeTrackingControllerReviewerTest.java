package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.config.CallerIdentity;
import self.research.ontology.owlEditor.service.HistorySyncService;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeTrackingControllerReviewerTest {

    private final HistorySyncService historySync = mock(HistorySyncService.class);
    private final ChangeTrackingController controller = new ChangeTrackingController();

    ChangeTrackingControllerReviewerTest() {
        ReflectionTestUtils.setField(controller, "historySyncService", historySync);
        when(historySync.approveChange(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    @Test
    void aSignedInCallerWithoutAUserIdClaimIsRecordedByEmailNotByTheUserIdInTheBody() {
        controller.approveChange("p1", "c1", Map.of("userId", "victim", "username", "victim"),
                new CallerIdentity(null, "reviewer@x.org"));

        verify(historySync).approveChange("p1", "c1", "reviewer@x.org", "reviewer@x.org");
        verify(historySync, never()).approveChange(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("victim"), anyString());
    }

    @Test
    void aCallerWithAUserIdClaimIsRecordedByThatId() {
        controller.approveChange("p1", "c1", Map.of("userId", "victim"), new CallerIdentity("u-1", "reviewer@x.org"));

        verify(historySync).approveChange("p1", "c1", "u-1", "reviewer@x.org");
    }
}
