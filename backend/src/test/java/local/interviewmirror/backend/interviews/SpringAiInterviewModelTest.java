package local.interviewmirror.backend.interviews;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.test.util.ReflectionTestUtils;

class SpringAiInterviewModelTest {

    @Test
    void obtainsStructuredOutputAndMetadataFromOneResponseEntity() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        var expected = new SpringAiInterviewModel.FollowupOutput(false, "回答已覆盖当前要点。", "");
        when(client.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.responseEntity(SpringAiInterviewModel.FollowupOutput.class))
                .thenReturn(new org.springframework.ai.chat.client.ResponseEntity<ChatResponse, SpringAiInterviewModel.FollowupOutput>(null, expected));

        var subject = new SpringAiInterviewModel(mock(org.springframework.ai.chat.model.ChatModel.class), true,
                "QWEN", "qwen-test");
        ReflectionTestUtils.setField(subject, "followupClient", client);

        var result = subject.evaluateAnswer("synthetic question", "synthetic answer");

        assertFalse(result.shouldFollowUp());
        verify(response, times(1)).responseEntity(SpringAiInterviewModel.FollowupOutput.class);
        verify(response, never()).chatResponse();
        verify(response, never()).entity(SpringAiInterviewModel.FollowupOutput.class);
    }
}
