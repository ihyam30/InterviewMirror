package local.interviewmirror.backend.documents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
public class StructuredDocumentParser {
    private static final Pattern NUMBERED_QUESTION = Pattern.compile(
            "^\\s{0,3}(?:#{1,6}\\s*)?(?:(?:Q|Question|问题|题目)\\s*#?\\s*\\d+\\s*[:.)、|\\-]?|\\d+\\s*[.、)）|\\-])\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_LABEL = Pattern.compile(
            "^\\s*(?:[-*+]\\s*)?(?:\\*{1,2})?([^:：|]{1,36}?)(?:\\*{1,2})?\\s*[:：|]\\s*(.*?)\\s*$");
    private static final Pattern HTML_TABLE_ROW = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern HTML_TABLE_CELL = Pattern.compile("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>");
    private static final Set<String> NAME_LABELS = Set.of("name", "姓名", "候选人", "个人姓名");
    private static final Set<String> EDUCATION_LABELS = Set.of("education", "教育", "教育经历", "学历", "学历经历");
    private static final Set<String> SKILL_LABELS = Set.of("skills", "skill", "技能", "技能关键词", "技术栈", "专业技能");
    private static final Set<String> PROJECT_LABELS = Set.of("project", "projects", "项目", "项目经历", "项目摘要");
    private static final Set<String> METRIC_LABELS = Set.of("metric", "metrics", "result", "results", "成果", "结果", "指标", "量化结果");
    private static final Set<String> EXPERIENCE_LABELS = Set.of("experience", "experiences", "work", "工作经历", "实习经历", "工作/实习经历");
    private static final Set<String> AWARD_LABELS = Set.of("awards", "award", "honors", "荣誉", "奖项", "获奖经历");
    private static final Set<String> QUESTION_HEADERS = Set.of("question", "questions", "题目", "问题", "题干", "面试问题");
    private static final Set<String> ANSWER_HEADERS = Set.of("answer", "suggested answer", "答案", "参考答案");
    private static final Set<String> CATEGORY_HEADERS = Set.of("category", "分类", "类型", "主题");
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[ -]?)?1[3-9]\\d{9}(?!\\d)");
    private final JsonNodeFactory nodes = JsonNodeFactory.instance;

    public ObjectNode parse(DocumentType type, String markdown) {
        if (type == DocumentType.RESUME) return parseResume(markdown);
        return parseQuestionBank(markdown);
    }

    ObjectNode parseResume(String markdown) {
        List<SourceLine> lines = sourceLines(markdown);
        Map<String, LocatedValue> labels = labeledValues(lines);
        ObjectNode result = nodes.objectNode();
        result.put("schemaVersion", "interviewmirror.resume-content.v1");
        ObjectNode personal = result.putObject("personalInfo");
        putLabel(personal, "name", labels, NAME_LABELS);
        putLabel(personal, "email", labels, Set.of("email", "邮箱", "电子邮箱"));
        putLabel(personal, "phone", labels, Set.of("phone", "telephone", "mobile", "电话", "手机"));
        putLabel(personal, "location", labels, Set.of("location", "所在地", "居住地", "城市"));
        if (!personal.hasNonNull("email")) findAndPut(personal, "email", EMAIL, lines);
        if (!personal.hasNonNull("phone")) findAndPut(personal, "phone", PHONE, lines);

        ArrayNode education = result.putArray("education");
        LocatedValue educationValue = first(labels, EDUCATION_LABELS);
        if (educationValue != null) {
            ObjectNode item = education.addObject();
            addSource(item, educationValue.lineNumber());
            item.put("details", educationValue.value());
            String[] parts = educationValue.value().split("\\s*[，,；;|/]\\s*");
            for (String part : parts) {
                String value = clean(part);
                if (value.isBlank()) continue;
                if (value.matches(".*(大学|学院|University|College).*")) item.put("institution", value);
                else if (value.matches(".*(本科|硕士|博士|专科|Bachelor|Master|PhD|Associate).*")) item.put("degree", value);
                else if (!item.has("major")) item.put("major", value);
            }
            if (!item.has("degree")) item.put("degree", educationValue.value());
        }

        ArrayNode skills = result.putArray("skills");
        LocatedValue skillValue = first(labels, SKILL_LABELS);
        if (skillValue != null) {
            for (String skill : skillValue.value().split("[,，、;；|/]+")) {
                String value = clean(skill);
                if (!value.isBlank()) skills.add(value);
            }
        }

        ArrayNode projects = result.putArray("projects");
        LocatedValue projectValue = first(labels, PROJECT_LABELS);
        if (projectValue != null) {
            ObjectNode project = projects.addObject();
            project.put("name", projectValue.value());
            project.putArray("technologies");
            project.putArray("outcomes");
            addSource(project, projectValue.lineNumber());
        }

        ArrayNode metrics = result.putArray("metrics");
        LocatedValue metricValue = first(labels, METRIC_LABELS);
        if (metricValue != null) {
            metrics.add(metricValue.value());
            if (!projects.isEmpty()) ((ArrayNode) projects.get(0).get("outcomes")).add(metricValue.value());
        }

        ArrayNode experiences = result.putArray("experiences");
        LocatedValue experienceValue = first(labels, EXPERIENCE_LABELS);
        if (experienceValue != null) {
            ObjectNode experience = experiences.addObject();
            experience.put("description", experienceValue.value());
            addSource(experience, experienceValue.lineNumber());
        }

        ArrayNode awards = result.putArray("awards");
        LocatedValue awardValue = first(labels, AWARD_LABELS);
        if (awardValue != null) {
            for (String award : awardValue.value().split("[;；|]+")) {
                if (!clean(award).isBlank()) awards.addObject().put("name", clean(award));
            }
        }
        return result;
    }

    ObjectNode parseQuestionBank(String markdown) {
        List<SourceLine> lines = sourceLines(markdown);
        ArrayNode items = nodes.arrayNode();
        List<Question> found = new ArrayList<>();
        int questionColumn = -1;
        int answerColumn = -1;
        int categoryColumn = -1;
        boolean questionTable = false;

        for (SourceLine line : lines) {
            String trimmed = line.text().trim();
            Matcher htmlRows = HTML_TABLE_ROW.matcher(trimmed);
            if (htmlRows.find()) {
                do {
                    List<String> htmlCells = htmlCells(htmlRows.group(1));
                    if (htmlCells.isEmpty()) continue;
                    if (isHeaderRow(htmlCells)) {
                        questionColumn = findHeader(htmlCells, QUESTION_HEADERS);
                        answerColumn = findHeader(htmlCells, ANSWER_HEADERS);
                        categoryColumn = findHeader(htmlCells, CATEGORY_HEADERS);
                        questionTable = questionColumn >= 0;
                        continue;
                    }
                    if (questionTable && questionColumn < htmlCells.size()) {
                        String stem = htmlCells.get(questionColumn);
                        if (isQuestionText(stem)) {
                            String answer = answerColumn >= 0 && answerColumn < htmlCells.size()
                                    ? htmlCells.get(answerColumn) : "";
                            String category = categoryColumn >= 0 && categoryColumn < htmlCells.size()
                                    ? htmlCells.get(categoryColumn) : "";
                            found.add(new Question(stem, answer, category, line.number()));
                        }
                    }
                } while (htmlRows.find());
                continue;
            }
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                List<String> cells = cells(trimmed);
                if (!cells.isEmpty() && isHeaderRow(cells)) {
                    questionColumn = findHeader(cells, QUESTION_HEADERS);
                    answerColumn = findHeader(cells, ANSWER_HEADERS);
                    categoryColumn = findHeader(cells, CATEGORY_HEADERS);
                    questionTable = questionColumn >= 0;
                    continue;
                }
                if (questionTable && questionColumn < cells.size()) {
                    String stem = clean(cells.get(questionColumn));
                    if (isQuestionText(stem)) {
                        String answer = answerColumn >= 0 && answerColumn < cells.size() ? clean(cells.get(answerColumn)) : "";
                        String category = categoryColumn >= 0 && categoryColumn < cells.size() ? clean(cells.get(categoryColumn)) : "";
                        found.add(new Question(stem, answer, category, line.number()));
                        continue;
                    }
                }
                if (isNumberedTableRow(cells)) {
                    int valueColumn = cells.size() > 1 ? 1 : 0;
                    String stem = clean(cells.get(valueColumn));
                    if (isQuestionText(stem)) found.add(new Question(stem, "", "", line.number()));
                }
                continue;
            }

            Matcher numbered = NUMBERED_QUESTION.matcher(trimmed);
            if (numbered.matches()) {
                String stem = clean(numbered.group(1));
                if (isQuestionText(stem)) found.add(new Question(stem, "", "", line.number()));
                continue;
            }

            Matcher labelled = SECTION_LABEL.matcher(trimmed);
            if (labelled.matches() && ANSWER_HEADERS.contains(normalize(labelled.group(1)))) {
                if (!found.isEmpty()) {
                    Question previous = found.removeLast();
                    found.add(new Question(previous.stem(), clean(labelled.group(2)), previous.category(), previous.lineNumber()));
                }
                continue;
            }
            if (isQuestionText(trimmed) && trimmed.length() <= 300) {
                found.add(new Question(clean(trimmed), "", "", line.number()));
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        int position = 1;
        for (Question question : found) {
            String key = normalize(question.stem());
            if (key.isBlank() || !seen.add(key)) continue;
            ObjectNode item = items.addObject();
            item.put("position", position++);
            item.put("stem", question.stem());
            item.put("answer", question.answer());
            if (!question.category().isBlank()) item.put("category", question.category());
            item.putObject("sourceLocator").put("line", question.lineNumber());
        }

        ObjectNode result = nodes.objectNode();
        result.put("schemaVersion", "interviewmirror.question-bank-content.v1");
        result.set("questions", items);
        return result;
    }

    private static Map<String, LocatedValue> labeledValues(List<SourceLine> lines) {
        Map<String, LocatedValue> result = new LinkedHashMap<>();
        for (SourceLine source : lines) {
            String text = source.text().trim();
            if (text.isBlank()) continue;
            List<String> tableCells = text.startsWith("|") && text.endsWith("|") ? cells(text) : List.of();
            if (tableCells.size() >= 2 && isResumeLabel(tableCells.getFirst())) {
                result.putIfAbsent(normalize(tableCells.getFirst()), new LocatedValue(clean(tableCells.get(1)), source.number()));
                continue;
            }
            Matcher matcher = SECTION_LABEL.matcher(text);
            if (matcher.matches() && isResumeLabel(matcher.group(1))) {
                result.putIfAbsent(normalize(matcher.group(1)), new LocatedValue(clean(matcher.group(2)), source.number()));
            }
        }
        return result;
    }

    private static void putLabel(ObjectNode target, String field, Map<String, LocatedValue> labels, Set<String> aliases) {
        LocatedValue value = first(labels, aliases);
        if (value != null && !value.value().isBlank()) {
            target.put(field, value.value());
            target.putObject(field + "Source").put("line", value.lineNumber());
        }
    }

    private static void findAndPut(ObjectNode target, String field, Pattern pattern, List<SourceLine> lines) {
        for (SourceLine line : lines) {
            Matcher matcher = pattern.matcher(line.text());
            if (matcher.find()) {
                target.put(field, matcher.group());
                target.putObject(field + "Source").put("line", line.number());
                return;
            }
        }
    }

    private static LocatedValue first(Map<String, LocatedValue> values, Set<String> aliases) {
        for (String alias : aliases) {
            LocatedValue value = values.get(normalize(alias));
            if (value != null && !value.value().isBlank()) return value;
        }
        return null;
    }

    private static void addSource(ObjectNode item, int line) {
        item.putObject("sourceLocator").put("line", line);
    }

    private static List<SourceLine> sourceLines(String markdown) {
        List<SourceLine> lines = new ArrayList<>();
        String[] split = markdown == null ? new String[0] : markdown.split("\\R");
        for (int index = 0; index < split.length; index++) lines.add(new SourceLine(index + 1, split[index]));
        return lines;
    }

    private static List<String> cells(String row) {
        String[] values = row.substring(1, row.length() - 1).split("\\|", -1);
        List<String> cells = new ArrayList<>(values.length);
        for (String value : values) cells.add(clean(value));
        return cells;
    }

    private static List<String> htmlCells(String row) {
        List<String> result = new ArrayList<>();
        Matcher matcher = HTML_TABLE_CELL.matcher(row);
        while (matcher.find()) {
            String text = matcher.group(1).replaceAll("(?i)<br\\s*/?>", " ")
                    .replaceAll("(?is)<[^>]+>", " ");
            result.add(clean(HtmlUtils.htmlUnescape(text).replace('\u00a0', ' ').replaceAll("\\s+", " ")));
        }
        return result;
    }

    private static boolean isHeaderRow(List<String> cells) {
        return cells.stream().anyMatch(cell -> QUESTION_HEADERS.contains(normalize(cell)));
    }

    private static int findHeader(List<String> cells, Set<String> accepted) {
        for (int index = 0; index < cells.size(); index++) {
            if (accepted.contains(normalize(cells.get(index)))) return index;
        }
        return -1;
    }

    private static boolean isNumberedTableRow(List<String> cells) {
        return cells.size() >= 2 && cells.getFirst().trim().matches("#?\\d{1,3}");
    }

    private static boolean isResumeLabel(String value) {
        String normalized = normalize(value);
        return NAME_LABELS.contains(normalized) || EDUCATION_LABELS.contains(normalized)
                || SKILL_LABELS.contains(normalized) || PROJECT_LABELS.contains(normalized)
                || METRIC_LABELS.contains(normalized) || EXPERIENCE_LABELS.contains(normalized)
                || AWARD_LABELS.contains(normalized)
                || Set.of("email", "邮箱", "电子邮箱", "phone", "telephone", "mobile", "电话", "手机",
                "location", "所在地", "居住地", "城市").contains(normalized);
    }

    private static boolean isQuestionText(String value) {
        String text = clean(value);
        if (text.isBlank() || text.length() < 6 || text.length() > 500) return false;
        String normalized = normalize(text);
        return !QUESTION_HEADERS.contains(normalize(text)) && !ANSWER_HEADERS.contains(normalize(text))
                && !normalized.matches("[0-9#]+") && !normalized.startsWith("syntheticfixture")
                && !normalized.matches("(?:synthetic)?(?:custom)?questionbank(?:q?\\d+)?");
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("^\\s*(?:#{1,6}\\s*)?", "")
                .replaceAll("^\\*{1,2}|\\*{1,2}$", "").replaceAll("^`+|`+$", "").trim();
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private record SourceLine(int number, String text) { }
    private record LocatedValue(String value, int lineNumber) { }
    private record Question(String stem, String answer, String category, int lineNumber) { }
}
