package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.UndoQueueService;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;

import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class UndoQueueController {

    private final UndoQueueService queue;
    private final RollbackRequestSupport support;

    public UndoQueueController(UndoQueueService queue, RollbackRequestSupport support) {
        this.queue = queue;
        this.support = support;
    }

    @GetMapping("/{projectId}/undo/queue")
    public ResponseEntity<?> queue(@PathVariable String projectId,
                                   @RequestParam(defaultValue = "false") boolean draft,
                                   @RequestParam(defaultValue = "1") int count,
                                   HttpServletRequest request) {
        Identity who = UndoIdentityResolver.resolve(support, request, null);
        if (who == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "error", "Sign in to undo or redo"));
        }
        return ResponseEntity.ok(queue.queue(projectId, draft, who, count));
    }
}
