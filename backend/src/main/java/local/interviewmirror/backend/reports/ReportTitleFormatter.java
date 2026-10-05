package local.interviewmirror.backend.reports;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class ReportTitleFormatter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年MM月dd日")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private ReportTitleFormatter() {}

    static String format(String username, String mode, String questionBankTitle, Instant completedAt, long ordinal) {
        String date = completedAt == null ? "日期待定" : DATE.format(completedAt);
        String user = username == null || username.isBlank() ? "用户" : username.trim();
        long attempt = Math.max(1, ordinal);
        if ("QUESTION_BANK".equals(mode)) {
            String bank = questionBankTitle == null || questionBankTitle.isBlank()
                    ? "自定义题库" : questionBankTitle.trim();
            return "%s %s 的「%s」专项面试报告（第 %d 次）".formatted(date, user, bank, attempt);
        }
        return "%s %s 的第 %d 次综合面试报告".formatted(date, user, attempt);
    }
}
