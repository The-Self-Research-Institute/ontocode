package self.research.ontology.owlEditor.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;

@Aspect
@Component
public class MutationWriteLockAspect {

    private final ProjectWriteLockRegistry locks;

    public MutationWriteLockAspect(ProjectWriteLockRegistry locks) {
        this.locks = locks;
    }

    @Around("execution(public * self.research.ontology.owlEditor.service.OntologyMutationService.apply*(String, ..))"
            + " || execution(public * self.research.ontology.owlEditor.service.OntologyMutationService.makeSiblingsDisjoint(String, ..))")
    public Object underTheProjectLock(ProceedingJoinPoint call) throws Throwable {
        Object first = call.getArgs().length == 0 ? null : call.getArgs()[0];
        if (!(first instanceof String projectId) || projectId.isBlank()) {
            return call.proceed();
        }
        return locks.runExclusive(projectId, () -> {
            try {
                return call.proceed();
            } catch (Exception | Error e) {
                throw e;
            } catch (Throwable other) {
                throw new IllegalStateException(other);
            }
        });
    }
}
