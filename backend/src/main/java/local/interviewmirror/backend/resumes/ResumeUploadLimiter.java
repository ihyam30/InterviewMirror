package local.interviewmirror.backend.resumes;

import java.util.List;
import java.util.UUID;
import local.interviewmirror.backend.common.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ResumeUploadLimiter {
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            for i,key in ipairs(KEYS) do
              local count=tonumber(redis.call('GET',key) or '0')
              if count>=tonumber(ARGV[i]) then return 0 end
            end
            for _,key in ipairs(KEYS) do
              local count=redis.call('INCR',key)
              if count==1 then redis.call('EXPIRE',key,60) end
            end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ResumeProperties properties;
    public ResumeUploadLimiter(StringRedisTemplate redis, ResumeProperties properties) { this.redis = redis; this.properties = properties; }
    public void check(UUID userId, String ip) {
        Long allowed;
        try {
            allowed = redis.execute(LIMIT, List.of("resume:rate:global", "resume:rate:ip:" + ResumeEvidenceCatalog.sha256(ip), "resume:rate:user:" + userId),
                    Integer.toString(properties.globalRate()), Integer.toString(properties.ipRate()), Integer.toString(properties.userRate()));
        } catch (RuntimeException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_LIMITER_UNAVAILABLE", "上传服务暂时不可用，请稍后重试。");
        }
        if (!Long.valueOf(1).equals(allowed)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_RATE_LIMIT", "上传过于频繁，请一分钟后重试。");
    }
}
