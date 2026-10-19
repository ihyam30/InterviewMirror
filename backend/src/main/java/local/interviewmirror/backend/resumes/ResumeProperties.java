package local.interviewmirror.backend.resumes;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("interviewmirror.resume")
public record ResumeProperties(
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("PT2M") Duration parseTimeout,
        @DefaultValue("100000") int textLimit,
        @DefaultValue("50") int pageLimit,
        @DefaultValue("2") int parseConcurrency,
        @DefaultValue("chi_sim+eng") String ocrLanguages,
        @DefaultValue("") String tesseractPath,
        @DefaultValue("PT180S") Duration modelTimeout,
        @DefaultValue("PT10M") Duration pendingTimeout,
        @DefaultValue("PT15M") Duration processingTimeout,
        @DefaultValue("interviewmirror:resumes") String stream,
        @DefaultValue("resume-analysis") String group,
        @DefaultValue("60") int globalRate,
        @DefaultValue("5") int ipRate,
        @DefaultValue("5") int userRate,
        @DefaultValue({"pdf", "doc", "docx", "txt"}) java.util.List<String> allowedExtensions) {
    public ResumeProperties {
        if (parseTimeout.isNegative() || parseTimeout.isZero() || textLimit < 1 || pageLimit < 1
                || parseConcurrency < 1 || globalRate < 1 || ipRate < 1 || userRate < 1
                || modelTimeout.isNegative() || modelTimeout.isZero()
                || pendingTimeout.isNegative() || pendingTimeout.isZero()
                || processingTimeout.isNegative() || processingTimeout.isZero() || allowedExtensions == null || allowedExtensions.isEmpty()
                || !java.util.Set.of("pdf", "doc", "docx", "txt").containsAll(allowedExtensions)) {
            throw new IllegalArgumentException("Invalid resume configuration");
        }
    }
}
