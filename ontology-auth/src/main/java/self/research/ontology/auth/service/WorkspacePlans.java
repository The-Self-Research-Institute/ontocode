package self.research.ontology.auth.service;

public final class WorkspacePlans {

    private WorkspacePlans() {
    }

    public static int maxMembersForPlan(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> 3;
            case "PRO" -> 10;
            case "ENTERPRISE" -> Integer.MAX_VALUE;
            default -> 3;
        };
    }

    public static int maxWorkspacesForPlan(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> 3;
            case "PRO" -> 10;
            case "ENTERPRISE" -> Integer.MAX_VALUE;
            default -> 3;
        };
    }

    public static int planRank(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> 1;
            case "PRO" -> 2;
            case "ENTERPRISE" -> 3;
            default -> 0;
        };
    }

    public static boolean isDowngrade(String currentPlan, String newPlan) {
        if (currentPlan == null) return false;
        return planRank(newPlan) < planRank(currentPlan);
    }

    public static boolean isUpgrade(String currentPlan, String newPlan) {
        if (currentPlan == null) currentPlan = "FREE";
        return planRank(newPlan) > planRank(currentPlan);
    }

    public static boolean hasBasicCollaboration(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> false;
            case "PRO" -> true;
            case "ENTERPRISE" -> true;
            default -> false;
        };
    }

    public static boolean hasAdvancedCollaboration(String plan) {
        return switch (plan.toUpperCase()) {
            case "FREE" -> false;
            case "PRO" -> false;
            case "ENTERPRISE" -> true;
            default -> false;
        };
    }

    public static String collaborationLevel(String plan) {
        if (hasAdvancedCollaboration(plan)) {
            return "advanced";
        } else if (hasBasicCollaboration(plan)) {
            return "basic";
        } else {
            return "none";
        }
    }
}
