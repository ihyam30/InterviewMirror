package local.interviewmirror.backend.files;

import java.io.InputStream;

public interface ObjectStorage {
    void put(String key, InputStream content, long size, String contentType) throws Exception;
    InputStream get(String key) throws Exception;
    void delete(String key) throws Exception;
}
