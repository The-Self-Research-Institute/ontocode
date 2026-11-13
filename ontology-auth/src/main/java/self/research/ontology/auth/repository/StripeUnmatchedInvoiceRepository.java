package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.StripeUnmatchedInvoice;

import java.util.List;

public interface StripeUnmatchedInvoiceRepository {

    void insert(StripeUnmatchedInvoice invoice);

    List<StripeUnmatchedInvoice> findRecent(int limit);
}
