package local.interviewmirror.backend.files;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!test")
public class MinioObjectStorage implements ObjectStorage {
    private final MinioClient client;
    private final String bucket;

    public MinioObjectStorage(MinioClient client, @Value("${interviewmirror.storage.bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, java.io.InputStream content, long size, String contentType) throws Exception {
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(content, size, -1L)
                .contentType(contentType).build());
    }

    @Override
    public java.io.InputStream get(String key) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    }

    @Override
    public void delete(String key) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    }
}
