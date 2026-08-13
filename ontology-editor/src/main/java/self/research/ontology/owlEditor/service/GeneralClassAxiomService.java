package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

@Service
public class GeneralClassAxiomService {

    private final ManchesterExpressionService manchesterExpressionService;

    public GeneralClassAxiomService(ManchesterExpressionService manchesterExpressionService) {
        this.manchesterExpressionService = manchesterExpressionService;
    }

    public void addGeneralClassAxiom(String projectId, String subClassExpr, String superClassExpr) throws Exception {
        manchesterExpressionService.addGeneralClassAxiom(projectId, subClassExpr, superClassExpr);
    }

    public void addGeneralClassAxiom(String projectId, String subClassExpr, String superClassExpr,
                                     boolean draft, String userId) throws Exception {
        manchesterExpressionService.addGeneralClassAxiom(projectId, subClassExpr, superClassExpr, draft, userId);
    }
}
