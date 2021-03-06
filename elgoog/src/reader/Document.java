package reader;

public class Document {

    // The document super class can only have integer type unique-ids
    // If you want other types of ids, add them in sub-classes
    // that extend Document and expose them through backingDocumentId
    private int uniqueId;

    // This is the DocumentId of the backing sub-class
    // For instance, Document super-class could be backed by a Scene class
    // which can have (playId + SceneId) as its unique String id.
    private String backingDocumentId;

    private String[] termVector;

    public Document(int uId, String backingId, String text) {
        uniqueId = uId;
        backingDocumentId = backingId;
        termVector = text.split("\\s+");
    }

    public int getDocumentUniqueId() {
        return uniqueId;
    }

    public String[] getTermVector() {
        return termVector;
    }

    public String getBackingId() {
        return backingDocumentId;
    }
}
