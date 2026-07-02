package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

/**
 * @deprecated Use {@link ManchesterExpressionService#addGeneralClassAxiom} directly.
 * Kept as a thin delegate for existing injections.
 */
@Service
public class GeneralClassAxiomService {

    private final ManchesterExpressionService manchesterExpressionService;

    public GeneralClassAxiomService(ManchesterExpressionService manchesterExpressionService) {
        this.manchesterExpressionService = manchesterExpressionService;
    }

    public void addGeneralClassAxiom(String projectId, String subClassExpr, String superClassExpr) throws Exception {
        manchesterExpressionService.addGeneralClassAxiom(projectId, subClassExpr, superClassExpr);
    }
}
