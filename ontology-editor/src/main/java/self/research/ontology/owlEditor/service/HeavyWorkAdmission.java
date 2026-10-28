package self.research.ontology.owlEditor.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import self.research.ontology.common.admission.AdmissionResult;
import self.research.ontology.common.admission.Estimates;
import self.research.ontology.common.admission.Reservation;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.common.admission.WorkKind;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import java.util.concurrent.Callable;

@Service
public class HeavyWorkAdmission {

    public static final long MIN_ESTIMATE_BYTES = 1024L * 1024L;
    public static final String ANONYMOUS_OWNER = "anonymous";

    public interface Hold extends AutoCloseable {

        Hold NONE = new Hold() {
            @Override
            public void close() {
            }

            @Override
            public long bytes() {
                return 0;
            }
        };

        @Override
        void close();

        long bytes();
    }

    private static final class LedgerHold implements Hold {
        private final Reservation reservation;

        LedgerHold(Reservation reservation) {
            this.reservation = reservation;
        }

        @Override
        public void close() {
            reservation.release();
        }

        @Override
        public long bytes() {
            return reservation.bytes();
        }
    }

    private final ReservationLedger ledger;
    private final Estimates estimates;

    public HeavyWorkAdmission(ReservationLedger ledger, Estimates estimates) {
        this.ledger = ledger;
        this.estimates = estimates == null ? new Estimates() : estimates;
    }

    public static HeavyWorkAdmission disabled() {
        return new HeavyWorkAdmission(null, new Estimates());
    }

    public boolean enabled() {
        return ledger != null;
    }

    public Estimates estimates() {
        return estimates;
    }

    public Hold admit(String userId, WorkKind kind, long estimateBytes) {
        if (ledger == null) {
            return Hold.NONE;
        }
        AdmissionResult result = ledger.acquire(ownerKey(userId), kind, Math.max(MIN_ESTIMATE_BYTES, estimateBytes));
        if (result.isRefused()) {
            throw new BusyException(result);
        }
        return new LedgerHold(result.reservation());
    }

    public <T> T run(String userId, WorkKind kind, long estimateBytes, Callable<T> work) throws Exception {
        try (Hold hold = admit(userId, kind, estimateBytes)) {
            return work.call();
        }
    }

    public Hold admitLoad(String userId, WorkKind kind, long triples) {
        return admit(userId, kind, estimates.loadAndReason(triples));
    }

    public Hold admitRuleExecute(String userId, long triples) {
        return admit(userId, WorkKind.EXECUTE_RULES, estimates.ruleExecute(triples));
    }

    public Hold admitSparql(String userId) {
        return admit(userId, WorkKind.SPARQL_QUERY, estimates.sparqlRowsBytesCap());
    }

    public static String ownerKey(String userId) {
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            String verified = JwtIdentityExtractor.extractUserId(request).orElse(null);
            if (verified != null && !verified.isBlank()) {
                return verified;
            }
            String forwarded = request.getHeader("X-Forwarded-For");
            String address = forwarded != null && !forwarded.isBlank()
                    ? forwarded.split(",")[0].trim()
                    : request.getRemoteAddr();
            if (address != null && !address.isBlank()) {
                return ANONYMOUS_OWNER + "@" + address;
            }
        }
        return ANONYMOUS_OWNER;
    }
}
