package local.interviewmirror.poc;

final class ModeRules {
    private ModeRules() {}

    static boolean isValid(String mode, boolean resumeConfirmed, boolean questionBankConfirmed, boolean jdProvided) {
        return ("COMPREHENSIVE".equals(mode) && resumeConfirmed && !questionBankConfirmed)
                || ("QUESTION_BANK".equals(mode) && questionBankConfirmed && !resumeConfirmed && !jdProvided);
    }
}
