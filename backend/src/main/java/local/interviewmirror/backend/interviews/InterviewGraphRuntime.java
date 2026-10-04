package local.interviewmirror.backend.interviews;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface InterviewGraphRuntime {
    GraphSnapshot begin(UUID interviewId, UUID ownerId, InterviewMode mode,
            UUID resumeId, UUID questionBankId, List<InterviewModel.PlannedQuestion> plan) throws Exception;
    GraphSnapshot answer(UUID interviewId, UUID turnId, String answer) throws Exception;
    GraphSnapshot replace(UUID interviewId, UUID turnId, InterviewModel.PlannedQuestion replacement,
            List<InterviewModel.PlannedQuestion> replacementPlan) throws Exception;
    GraphSnapshot end(UUID interviewId) throws Exception;
    GraphSnapshot snapshot(UUID interviewId);

    record GraphSnapshot(String phase, String completionReason, int mainQuestionIndex, int followupCount,
            int replaceCount, String currentTurnType, String currentQuestionId, String sourceQuestionId,
            String currentQuestion, String currentCategory, String lastAnsweredTurnId,
            String lastAnswer, String fallbackUsed, List<Map<String, Object>> questionPlan) {}
}
