package self.research.ontology.migrator.collections;

import self.research.ontology.migrator.core.CollectionMigration;

import java.util.List;

public final class Migrations {

    private Migrations() {
    }

    public static List<CollectionMigration> all() {
        return List.of(
                new UserMigration(),
                new WorkspaceMigration(),
                new ProjectContainerMigration(),
                new FileMetadataMigration(),
                new InvitationMigration(),
                new StripeEventMigration(),
                new EmailLogMigration(),
                new StripeUnmatchedInvoiceMigration(),
                new SystemSettingsMigration(),
                new PlanFeatureConfigMigration(),
                new DesktopDownloadEventMigration(),
                new AuditEventMigration(),
                new PluginMigration(),
                new PluginVersionMigration(),
                new PluginRatingMigration(),
                new PluginUserInstallMigration(),
                new SwrlRuleMigration(),
                new ReasoningJobMigration(),
                new ReasoningSnapshotMigration(),
                new OntologyFileMigration(),
                new ProjectShareMigration(),
                new UserProjectPreferenceMigration(),
                new PublicGraphVersionMigration(),
                new ProjectWriteLeaseMigration(),
                new ProjectRecoveryLockMigration(),
                new AppMigrationMigration(),
                new DraftChangeMigration(),
                new ParkedDraftChangeMigration(),
                new DraftSessionMigration(),
                new DraftPullRequestMigration(),
                new DraftPrChangeMigration(),
                new HistoryChangeMigration(),
                new OntologyChangeMigration(),
                new RollbackAuditMigration(),
                new UndoIntentMigration(),
                new AssistantAuditMigration(),
                new AssistantApplyOperationMigration(),
                new DatatypeDefinitionMigration(),
                new FuzzyRuleMigration(),
                new SparqlQueryMigration(),
                new IssueReportMigration(),
                new SpreadsheetImportRecordMigration(),
                new SpreadsheetRuleSetMigration());
    }
}
