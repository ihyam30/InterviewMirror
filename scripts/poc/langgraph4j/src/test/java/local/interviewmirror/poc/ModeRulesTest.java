package local.interviewmirror.poc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ModeRulesTest {
    @Test void comprehensiveRequiresResumeAndAllowsOptionalJd() {
        assertTrue(ModeRules.isValid("COMPREHENSIVE", true, false, false));
        assertTrue(ModeRules.isValid("COMPREHENSIVE", true, false, true));
        assertFalse(ModeRules.isValid("COMPREHENSIVE", false, false, false));
        assertFalse(ModeRules.isValid("COMPREHENSIVE", true, true, false));
    }

    @Test void questionBankRequiresConfirmedBankAndRejectsResumeOrJd() {
        assertTrue(ModeRules.isValid("QUESTION_BANK", false, true, false));
        assertFalse(ModeRules.isValid("QUESTION_BANK", false, false, false));
        assertFalse(ModeRules.isValid("QUESTION_BANK", true, true, false));
        assertFalse(ModeRules.isValid("QUESTION_BANK", false, true, true));
    }
}
