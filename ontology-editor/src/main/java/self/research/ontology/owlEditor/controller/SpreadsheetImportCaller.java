package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import self.research.ontology.owlEditor.config.JwtClaimUtils;

final class SpreadsheetImportCaller {

    record Caller(String userId, String username) {
    }

    private SpreadsheetImportCaller() {
    }

    static Caller resolve(HttpServletRequest request, String userId, String username) {
        String auth = request == null ? null : request.getHeader("Authorization");
        String email = JwtClaimUtils.extractEmail(auth);
        String[] planAndUser = JwtClaimUtils.extractPlanAndUserId(auth);
        String tokenUser = planAndUser != null && planAndUser[1] != null ? planAndUser[1] : email;
        if (tokenUser == null) {
            return new Caller(userId, username);
        }
        return new Caller(tokenUser, email != null ? email : tokenUser);
    }
}
