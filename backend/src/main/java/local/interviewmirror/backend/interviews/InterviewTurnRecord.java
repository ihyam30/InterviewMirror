package local.interviewmirror.backend.interviews;

import java.time.Instant;
import java.util.UUID;

record InterviewTurnRecord(UUID id, UUID interviewId, UUID ownerId, int sequence,
        InterviewTurnType type, int mainQuestionIndex, Integer followupIndex, String sourceQuestionId,
        String question, String answer, String status, String answerRequestId,
        Instant askedAt, Instant answeredAt, String replaceRequestId,
        String replaceRequestFingerprint, Instant replaceClaimedAt) {}
