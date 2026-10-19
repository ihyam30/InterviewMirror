package local.interviewmirror.backend.resumes;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ResumeProperties.class)
public class ResumeConfiguration {}
