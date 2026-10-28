package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.PrRevertService;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
public class DraftPrRevertController {

    public record RevertPRRequest(String userId, String username, String reason) {}

    private final PrRevertService service;

    public DraftPrRevertController(PrRevertService service) {
        this.service = service;
    }

    @PostMapping("/{projectId}/draft-prs/{prId}/revert")
    public ResponseEntity<Map<String, Object>> revert(@PathVariable String projectId, @PathVariable String prId,
                                                      @RequestParam(defaultValue = "false") boolean dryRun,
                                                      @RequestBody RevertPRRequest request,
                                                      jakarta.servlet.http.HttpServletRequest httpRequest) {
        VerifiedUser.Result caller = VerifiedUser.resolve(httpRequest, request.userId());
        if (caller.denial() != null) {
            return caller.denial();
        }
        return body(service.revert(projectId, prId, caller.userId(), request.username(), request.reason(), dryRun));
    }

    @PostMapping("/{projectId}/draft-prs/{prId}/reapply")
    public ResponseEntity<Map<String, Object>> reapply(@PathVariable String projectId, @PathVariable String prId,
                                                       @RequestParam(defaultValue = "false") boolean dryRun,
                                                       @RequestBody RevertPRRequest request,
                                                       jakarta.servlet.http.HttpServletRequest httpRequest) {
        VerifiedUser.Result caller = VerifiedUser.resolve(httpRequest, request.userId());
        if (caller.denial() != null) {
            return caller.denial();
        }
        return body(service.reapply(projectId, prId, caller.userId(), request.username(), dryRun));
    }

    @PostMapping("/{projectId}/draft-prs/{prId}/restore")
    public ResponseEntity<Map<String, Object>> restore(@PathVariable String projectId, @PathVariable String prId,
                                                       @RequestBody RevertPRRequest request,
                                                       jakarta.servlet.http.HttpServletRequest httpRequest) {
        VerifiedUser.Result caller = VerifiedUser.resolve(httpRequest, request.userId());
        if (caller.denial() != null) {
            return caller.denial();
        }
        return body(service.restore(projectId, prId, caller.userId(), request.username()));
    }

    private static ResponseEntity<Map<String, Object>> body(PrRevertService.Outcome outcome) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", outcome.ok());
        map.put("dryRun", outcome.dryRun());
        map.put("alreadyDone", outcome.alreadyDone());
        map.put("message", outcome.message());
        map.put("changeSets", outcome.changeSets());
        if (!outcome.ok()) {
            map.put("error", outcome.message());
        }
        return ResponseEntity.status(outcome.status()).body(map);
    }
}
