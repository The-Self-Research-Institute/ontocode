package self.research.ontology.owlEditor.controller;

import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.service.JobAccess;
import self.research.ontology.owlEditor.service.JobOwnerRegistry;
import self.research.ontology.owlEditor.service.RuleRunAccess;

final class JobAccessTestSupport {

    private JobAccessTestSupport() {
    }

    static JobAccess desktopJobAccess() {
        return new JobAccess(new RuleRunAccess(null, true), new JobOwnerRegistry());
    }

    static MockHttpServletRequest desktopRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
