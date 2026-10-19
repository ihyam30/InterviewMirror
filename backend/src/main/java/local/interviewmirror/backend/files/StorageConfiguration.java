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
        var http = new okhttp3.OkHttpClient.Builder().connectTimeout(java.time.Duration.ofSeconds(5))
                .readTimeout(java.time.Duration.ofMinutes(2)).writeTimeout(java.time.Duration.ofMinutes(2))
                .callTimeout(java.time.Duration.ofMinutes(2)).build();
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).httpClient(http).build();
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
