package local.interviewmirror.backend.reports;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.test.util.ReflectionTestUtils;

class SpringAiReportModelTest {

    @Test
    void obtainsStructuredOutputAndMetadataFromOneResponseEntity() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        var expected = new ReportModel.SummaryEvidenceReview(true, List.of("turn-1"));
        when(client.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.responseEntity(ReportModel.SummaryEvidenceReview.class))
                .thenReturn(new org.springframework.ai.chat.client.ResponseEntity<ChatResponse, ReportModel.SummaryEvidenceReview>(null, expected));

        var subject = new SpringAiReportModel(mock(org.springframework.ai.chat.model.ChatModel.class), true,
                "QWEN", "qwen-test", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(subject, "summaryEvidenceClient", client);

        var result = subject.verifySummaryEvidence("synthetic context");

        assertTrue(result.directlySupported());
        verify(response, times(1)).responseEntity(ReportModel.SummaryEvidenceReview.class);
        verify(response, never()).chatResponse();
        verify(response, never()).entity(ReportModel.SummaryEvidenceReview.class);
    }
}
