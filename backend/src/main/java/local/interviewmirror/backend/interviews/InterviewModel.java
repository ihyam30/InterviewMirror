package local.interviewmirror.backend.interviews;

import java.util.List;

public interface InterviewModel {
    default boolean isConfigured() { return true; }
    List<PlannedQuestion> generateComprehensivePlan(String resumeSnapshot, String jdText, int targetCount);
    FollowupDecision evaluateAnswer(String question, String answer);
    default FollowupDecision evaluateAnswer(String mainQuestion, String latestQuestion, String answer) {
        return evaluateAnswer(latestQuestion, answer);
    }
    PlannedQuestion generateReplacement(String resumeSnapshot, String jdText, String replacedQuestion,
                                        List<String> alreadyUsedQuestions);
    String provider();
    String modelId();

    record PlannedQuestion(String id, String stem, String category, String rationale, String sourceQuestionId) {}
    record FollowupDecision(boolean shouldFollowUp, String rationale, String question) {}
}
