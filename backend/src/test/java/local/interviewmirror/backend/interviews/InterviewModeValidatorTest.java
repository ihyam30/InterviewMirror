package local.interviewmirror.backend.interviews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.List;
import local.interviewmirror.backend.common.ApiException;
import org.junit.jupiter.api.Test;

class InterviewModeValidatorTest {
    private final InterviewModeValidator validator = new InterviewModeValidator();

    @Test
    void comprehensiveRequiresConfirmedResumeAndRejectsBank() {
        assertCode("INTERVIEW_MODE_INPUT_INVALID", create(InterviewMode.COMPREHENSIVE, null, null, null, true));
        assertCode("MODE_INPUT_CONFLICT", create(InterviewMode.COMPREHENSIVE, UUID.randomUUID(), UUID.randomUUID(), null, true));
        validator.validate(create(InterviewMode.COMPREHENSIVE, UUID.randomUUID(), null, "后端工程师", true));
        validator.validate(create(InterviewMode.COMPREHENSIVE, UUID.randomUUID(), null, null, true));
    }

    @Test
    void questionBankModeRequiresBankAndRejectsResumeOrAnyNonNullJd() {
        assertCode("INTERVIEW_MODE_INPUT_INVALID", create(InterviewMode.QUESTION_BANK, null, null, null, true));
        assertCode("MODE_INPUT_CONFLICT", create(InterviewMode.QUESTION_BANK, UUID.randomUUID(), UUID.randomUUID(), null, true));
        assertCode("MODE_INPUT_CONFLICT", create(InterviewMode.QUESTION_BANK, null, UUID.randomUUID(), "", true));
        validator.validate(create(InterviewMode.QUESTION_BANK, null, UUID.randomUUID(), null, true));
        InterviewRequests.Create explicitNull = new InterviewRequests.Create(InterviewModeValidator.SCHEMA_VERSION,
                UUID.randomUUID().toString(), InterviewMode.QUESTION_BANK, null, UUID.randomUUID(),
                null, true, "zh-CN", true);
        assertCode("MODE_INPUT_CONFLICT", explicitNull);
    }

    @Test
    void modelDataConsentIsRequiredAndJdHasBoundedLength() {
        ApiException consent = assertThrows(ApiException.class,
                () -> validator.validate(create(InterviewMode.COMPREHENSIVE, UUID.randomUUID(), null, null, false)));
        assertEquals("MODEL_DATA_CONSENT_REQUIRED", consent.code());
        ApiException tooLong = assertThrows(ApiException.class,
                () -> validator.validate(create(InterviewMode.COMPREHENSIVE, UUID.randomUUID(), null, "x".repeat(1501), true)));
        assertEquals("JD_TOO_LONG", tooLong.code());
    }

    @Test
    void everyModeSourcePresenceCombinationMatchesTheValidationMatrix() {
        UUID resume = UUID.randomUUID();
        UUID bank = UUID.randomUUID();
        List<MatrixCase> cases = List.of(
                new MatrixCase("comprehensive resume only", InterviewMode.COMPREHENSIVE, resume, null, null, false, true),
                new MatrixCase("comprehensive resume with jd", InterviewMode.COMPREHENSIVE, resume, null, "后端工程师", true, true),
                new MatrixCase("comprehensive resume with blank jd", InterviewMode.COMPREHENSIVE, resume, null, "  ", true, true),
                new MatrixCase("comprehensive without resume", InterviewMode.COMPREHENSIVE, null, null, null, false, false),
                new MatrixCase("comprehensive with bank", InterviewMode.COMPREHENSIVE, resume, bank, null, false, false),
                new MatrixCase("comprehensive with jd property null", InterviewMode.COMPREHENSIVE, resume, null, null, true, false),
                new MatrixCase("specialized bank only", InterviewMode.QUESTION_BANK, null, bank, null, false, true),
                new MatrixCase("specialized without bank", InterviewMode.QUESTION_BANK, null, null, null, false, false),
                new MatrixCase("specialized with resume", InterviewMode.QUESTION_BANK, resume, bank, null, false, false),
                new MatrixCase("specialized with empty jd property", InterviewMode.QUESTION_BANK, null, bank, "", true, false),
                new MatrixCase("specialized with null jd property", InterviewMode.QUESTION_BANK, null, bank, null, true, false));

        for (MatrixCase testCase : cases) {
            InterviewRequests.Create request = new InterviewRequests.Create(InterviewModeValidator.SCHEMA_VERSION,
                    UUID.randomUUID().toString(), testCase.mode(), testCase.resumeId(), testCase.bankId(),
                    testCase.jdText(), testCase.jdPresent(), "zh-CN", true);
            if (testCase.valid()) {
                validator.validate(request);
            } else {
                assertThrows(ApiException.class, () -> validator.validate(request), testCase.name());
            }
        }
        assertEquals(11, cases.size());
    }

    private static InterviewRequests.Create create(InterviewMode mode, UUID resume, UUID bank, String jd, boolean consent) {
        return new InterviewRequests.Create(InterviewModeValidator.SCHEMA_VERSION, UUID.randomUUID().toString(),
                mode, resume, bank, jd, "zh-CN", consent);
    }

    private void assertCode(String code, InterviewRequests.Create request) {
        ApiException failure = assertThrows(ApiException.class, () -> validator.validate(request));
        assertEquals(code, failure.code());
        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank());
    }

    private record MatrixCase(String name, InterviewMode mode, UUID resumeId, UUID bankId,
            String jdText, boolean jdPresent, boolean valid) {}
}
