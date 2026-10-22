package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document("datatype_definitions")
public class DatatypeDefinitionEntity {

    @Id
    private String id;
    private String projectId;
    private String datatypeIri;
    private String expression;
    private String definitionType;
    private Date createdAt = new Date();
    private Date updatedAt = new Date();
    private boolean draft;
    private String ownerUserId;
    private String replacesId;
    private boolean deleted;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getDatatypeIri() {
        return datatypeIri;
    }

    public void setDatatypeIri(String datatypeIri) {
        this.datatypeIri = datatypeIri;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getDefinitionType() {
        return definitionType;
    }

    public void setDefinitionType(String definitionType) {
        this.definitionType = definitionType;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isDraft() {
        return draft;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getReplacesId() {
        return replacesId;
    }

    public void setReplacesId(String replacesId) {
        this.replacesId = replacesId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public DatatypeDefinitionEntity copy() {
        DatatypeDefinitionEntity copy = new DatatypeDefinitionEntity();
        copy.id = id;
        copy.projectId = projectId;
        copy.datatypeIri = datatypeIri;
        copy.expression = expression;
        copy.definitionType = definitionType;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.draft = draft;
        copy.ownerUserId = ownerUserId;
        copy.replacesId = replacesId;
        copy.deleted = deleted;
        return copy;
    }

    public boolean isOwnedBy(String userId) {
        return draft && ownerUserId != null && ownerUserId.equals(userId);
    }
}
