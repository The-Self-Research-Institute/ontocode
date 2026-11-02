package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "hierarchy_snapshot_chunks")
public class HierarchySnapshotChunkDoc {

    @Id
    private String id;

    @Indexed
    private String setId;

    private int index;
    private byte[] data;

    @Indexed(expireAfterSeconds = 0)
    private Date expiresAt;

    public HierarchySnapshotChunkDoc() {}

    public HierarchySnapshotChunkDoc(String setId, int index, byte[] data, Date expiresAt) {
        this.id = setId + ":" + index;
        this.setId = setId;
        this.index = index;
        this.data = data;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSetId() { return setId; }
    public void setSetId(String setId) { this.setId = setId; }
    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
}
