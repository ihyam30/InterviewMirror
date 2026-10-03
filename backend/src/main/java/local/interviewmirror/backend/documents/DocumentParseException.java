package local.interviewmirror.backend.documents;

public class DocumentParseException extends RuntimeException {
    private final String code;
    private final String safeMessage;

    public DocumentParseException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
        this.safeMessage = safeMessage;
    }

    public DocumentParseException(String code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
        this.safeMessage = safeMessage;
    }

    public String code() { return code; }
    public String safeMessage() { return safeMessage; }
}
