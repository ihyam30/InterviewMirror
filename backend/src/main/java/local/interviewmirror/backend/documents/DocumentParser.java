package local.interviewmirror.backend.documents;

public interface DocumentParser {
    String parse(String filename, String contentType, byte[] bytes);
}
