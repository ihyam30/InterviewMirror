package local.interviewmirror.backend.files;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class StorageConfiguration {
    @Bean
    MinioClient minioClient(@Value("${interviewmirror.storage.endpoint}") String endpoint,
                            @Value("${interviewmirror.storage.access-key}") String accessKey,
                            @Value("${interviewmirror.storage.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }

    @Bean
    ApplicationRunner ensurePrivateBucket(MinioClient client, @Value("${interviewmirror.storage.bucket}") String bucket) {
        return args -> {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        };
    }
}
