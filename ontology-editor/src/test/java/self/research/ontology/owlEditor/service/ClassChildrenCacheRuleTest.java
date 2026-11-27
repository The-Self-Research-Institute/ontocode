package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClassChildrenCacheRuleTest {

    @Test
    void childrenFoundWhileTheGraphSizeIsUnknownAreNotCachedForEveryone() throws Exception {
        Cacheable rule = OntologyQueryService.class.getMethod("children", String.class, String.class, int.class, int.class)
                .getAnnotation(Cacheable.class);
        SparqlDatasetService datasets = mock(SparqlDatasetService.class);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("sparqlDatasetService", datasets);
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver(new BeanFactoryResolver(beans));
        context.setVariable("projectId", "p1");
        Expression unless = new SpelExpressionParser().parseExpression(rule.unless());

        when(datasets.knownGraphSize("p1")).thenReturn(OptionalLong.empty());
        assertTrue(unless.getValue(context, Boolean.class));

        when(datasets.knownGraphSize("p1")).thenReturn(OptionalLong.of(10_000));
        assertFalse(unless.getValue(context, Boolean.class));
    }

    @Test
    void theRuleNamesTheRealDatasetServiceBean() {
        assertTrue(SparqlDatasetService.class.isAnnotationPresent(Service.class));
        assertEquals("", SparqlDatasetService.class.getAnnotation(Service.class).value());
    }
}
