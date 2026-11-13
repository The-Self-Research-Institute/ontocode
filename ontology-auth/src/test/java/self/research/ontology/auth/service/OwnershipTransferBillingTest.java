package self.research.ontology.auth.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipTransferBillingTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 12, 0);

    private static Optional<String> check(String workspacePlan, boolean bypass, String plan, String status, LocalDateTime end) {
        return OwnershipTransferBilling.blockedReason(workspacePlan, bypass, "Asha", plan, status, end, NOW);
    }

    @Test
    void aFreeWorkspaceCanGoToAnyMember() {
        assertEquals(Optional.empty(), check("FREE", false, null, null, null));
        assertEquals(Optional.empty(), check(null, false, "PRO", "canceled", null));
    }

    @Test
    void aPaidWorkspaceCanGoToAMemberWithAnActivePaidPlan() {
        assertEquals(Optional.empty(), check("PRO", false, "PRO", "active", NOW.plusDays(20)));
        assertEquals(Optional.empty(), check("PRO", false, "ENTERPRISE", "trialing", NOW.plusDays(3)));
    }

    @Test
    void aPaidWorkspaceCannotGoToAMemberWithNoPaidPlan() {
        Optional<String> reason = check("PRO", false, null, null, null);
        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("Asha"));
        assertTrue(reason.get().contains("PRO"));
        assertTrue(check("PRO", false, "FREE", "active", null).isPresent());
    }

    @Test
    void aPaidWorkspaceCannotGoToAMemberWhosePlanIsNotActive() {
        for (String status : new String[] {"incomplete", "past_due", "canceled", "unpaid", null}) {
            assertTrue(check("PRO", false, "PRO", status, NOW.plusDays(20)).isPresent(), "status " + status);
        }
    }

    @Test
    void aPaidWorkspaceCannotGoToAMemberWhosePlanPeriodHasEnded() {
        assertTrue(check("PRO", false, "PRO", "active", NOW.minusDays(1)).isPresent());
    }

    @Test
    void anEnterpriseBypassMemberIsAlwaysAllowed() {
        assertEquals(Optional.empty(), check("ENTERPRISE", true, null, null, null));
    }

    @Test
    void aMissingNameStillGivesAReadableMessage() {
        Optional<String> reason = OwnershipTransferBilling.blockedReason("PRO", false, null, null, null, null, NOW);
        assertTrue(reason.orElseThrow().startsWith("This member"));
    }
}
