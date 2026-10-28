package self.research.ontology.owlEditor.service.infer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import self.research.ontology.common.admission.CacheAccount;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.owlEditor.service.RetainedMemory;

@Component
public class LedgerInferenceCacheAccounting implements InferenceCacheAccounting {

    public static final String ACCOUNT_NAME = "inferenceSnapshots";

    private final CacheAccount account;

    public LedgerInferenceCacheAccounting(ObjectProvider<ReservationLedger> ledger) {
        ReservationLedger found = ledger.getIfAvailable();
        this.account = found != null ? found.cacheAccount(ACCOUNT_NAME) : RetainedMemory.detachedAccount(ACCOUNT_NAME);
    }

    public LedgerInferenceCacheAccounting(CacheAccount account) {
        this.account = account;
    }

    public CacheAccount account() {
        return account;
    }

    @Override
    public void add(long bytes) {
        account.add(bytes);
    }

    @Override
    public void remove(long bytes) {
        account.remove(bytes);
    }
}
