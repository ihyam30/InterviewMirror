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
            "^\\s{0,3}(?:#{1,6}\\s*)?(?:(?:Q|Question|问题|题目)\\s*#?\\s*\\d+\\s*[:：.)、|\\-]?|\\d+\\s*[.、)）|\\-])\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_LABEL = Pattern.compile(
            "^\\s*(?:[-*+]\\s*)?(?:\\*{1,2})?([^:：|]{1,36}?)(?:\\*{1,2})?\\s*[:：|]\\s*(.*?)\\s*$");
    private static final Pattern QUESTION_LABEL = Pattern.compile(
            "^\\s*(?:[-*+]\\s*)?(?:\\*{1,2})?(?:问题|题目|question|q)\\s*(?:(?:#|第)?\\s*\\d+)?(?:\\*{1,2})?\\s*[:：|]\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER_LABEL = Pattern.compile(
            "^\\s*(?:[-*+]\\s*)?(?:\\*{1,2})?(?:(?:参考|标准|建议|示例|正确)?答案(?:要点|解析)?|解析|解答|回答|答|suggested answer|sample answer|answer|ans|a)(?:\\s*(?:#|第)?\\s*\\d+)?(?:\\*{1,2})?\\s*[:：|]\\s*(.*?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern HTML_TABLE_ROW = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern HTML_TABLE_CELL = Pattern.compile("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>");
    private static final Set<String> NAME_LABELS = Set.of("name", "姓名", "候选人", "个人姓名");
    private static final Set<String> ROLE_LABELS = Set.of(
            "targetrole", "role", "position", "求职意向", "求职方向", "意向岗位", "岗位意向", "目标岗位", "应聘岗位", "期望职位", "意向职位");
    private static final Set<String> EDUCATION_LABELS = Set.of("education", "教育", "教育经历", "教育背景", "学历", "学历经历");
    private static final Set<String> SKILL_LABELS = Set.of("skills", "skill", "技能", "技能关键词", "技能清单", "个人技能", "核心技能", "技术栈", "专业技能");
    private static final Set<String> PROJECT_LABELS = Set.of("project", "projects", "项目", "项目经历", "项目经验", "项目摘要");
    private static final Set<String> PROJECT_FIELD_HEADINGS = Set.of(
            "项目描述", "项目介绍", "description", "项目职责", "项目亮点", "核心亮点", "highlights",
            "技术栈", "使用技术", "techstack", "technologies");
    private static final Set<String> METRIC_LABELS = Set.of("metric", "metrics", "result", "results", "成果", "结果", "指标", "量化结果", "项目亮点", "项目成果", "核心亮点");
    private static final Set<String> EXPERIENCE_LABELS = Set.of("experience", "experiences", "work", "工作经历", "实习经历", "工作/实习经历");
    private static final Set<String> AWARD_LABELS = Set.of("awards", "award", "honors", "荣誉", "荣誉奖项", "奖项", "获奖经历");
    private static final Set<String> QUESTION_HEADERS = Set.of("question", "questions", "题目", "问题", "题干", "面试问题");
    private static final Set<String> ANSWER_HEADERS = Set.of(
            "answer", "suggested answer", "答案", "参考答案", "标准答案", "建议答案", "示例答案", "正确答案", "答案要点", "答案解析", "解答", "解析");
    private static final Set<String> ANSWER_HEADING_LABELS = Set.of(
            "answer", "suggestedanswer", "sampleanswer", "ans", "a", "答案", "参考答案", "标准答案", "建议答案", "示例答案", "正确答案", "答案要点", "答案解析", "解析", "解答", "回答", "答", "面试口语版");
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
        Map<String, List<ResumeBlock>> sections = resumeSections(lines);
        ObjectNode result = nodes.objectNode();
        result.put("schemaVersion", "interviewmirror.resume-content.v1");
        ObjectNode personal = result.putObject("personalInfo");
        putLabel(personal, "name", labels, NAME_LABELS);
        putLabel(personal, "targetRole", labels, ROLE_LABELS);
        putLabel(personal, "email", labels, Set.of("email", "邮箱", "电子邮箱"));
        putLabel(personal, "phone", labels, Set.of("phone", "telephone", "mobile", "电话", "手机"));
        putLabel(personal, "location", labels, Set.of("location", "所在地", "居住地", "城市"));
        if (!personal.hasNonNull("email")) findAndPut(personal, "email", EMAIL, lines);
        if (!personal.hasNonNull("phone")) findAndPut(personal, "phone", PHONE, lines);

        ArrayNode education = result.putArray("education");
        List<ResumeBlock> educationBlocks = sections.getOrDefault("education", List.of());
        if (!educationBlocks.isEmpty()) {
            for (ResumeBlock block : educationBlocks) addEducation(education, block);
        } else {
            LocatedValue educationValue = first(labels, EDUCATION_LABELS);
            if (educationValue != null) addEducation(education,
                    new ResumeBlock("", educationValue.lineNumber(),
                            List.of(new SourceLine(educationValue.lineNumber(), educationValue.value()))));
        }

        ArrayNode skills = result.putArray("skills");
        List<ResumeBlock> skillBlocks = sections.getOrDefault("skills", List.of());
        if (!skillBlocks.isEmpty()) {
            for (ResumeBlock block : skillBlocks) addSkillBlock(skills, block.lines());
        } else {
            LocatedValue skillValue = first(labels, SKILL_LABELS);
            if (skillValue != null) addInlineSkillValues(skills, skillValue.value());
        }

        ArrayNode projects = result.putArray("projects");
        List<ResumeBlock> projectBlocks = sections.getOrDefault("projects", List.of());
        if (!projectBlocks.isEmpty()) {
            for (ResumeBlock block : projectBlocks) addProject(projects, block);
        } else {
            LocatedValue projectValue = first(labels, PROJECT_LABELS);
            if (projectValue != null) {
                ObjectNode project = projects.addObject();
                project.put("name", projectValue.value());
                project.putArray("technologies");
                project.putArray("outcomes");
                project.put("description", projectValue.value());
                addSource(project, projectValue.lineNumber());
            }
        }

        ArrayNode metrics = result.putArray("metrics");
        List<ResumeBlock> metricBlocks = sections.getOrDefault("metrics", List.of());
        if (!metricBlocks.isEmpty()) {
            for (ResumeBlock block : metricBlocks) {
                for (SourceLine line : block.lines()) addNonBlank(metrics, line.text());
            }
        } else {
            LocatedValue metricValue = first(labels, METRIC_LABELS);
            if (metricValue != null) metrics.add(metricValue.value());
        }
        ArrayNode experiences = result.putArray("experiences");
        List<ResumeBlock> experienceBlocks = sections.getOrDefault("experience", List.of());
        if (!experienceBlocks.isEmpty()) {
            for (ResumeBlock block : experienceBlocks) {
                String description = blockText(block.lines());
                if (description.isBlank()) continue;
                ObjectNode experience = experiences.addObject();
                if (!EXPERIENCE_LABELS.contains(normalize(block.title()))) {
                    experience.put("organization", clean(block.title()));
                }
                experience.put("description", description);
                addSource(experience, block.lineNumber());
            }
        } else {
            LocatedValue experienceValue = first(labels, EXPERIENCE_LABELS);
            if (experienceValue != null) {
                ObjectNode experience = experiences.addObject();
                experience.put("description", experienceValue.value());
                addSource(experience, experienceValue.lineNumber());
            }
        }

        ArrayNode awards = result.putArray("awards");
        List<ResumeBlock> awardBlocks = sections.getOrDefault("awards", List.of());
        if (!awardBlocks.isEmpty()) {
            for (ResumeBlock block : awardBlocks) {
                for (SourceLine line : block.lines()) addAwardLines(awards, line.text());
            }
        } else {
            LocatedValue awardValue = first(labels, AWARD_LABELS);
            if (awardValue != null) addAwardLines(awards, awardValue.value());
        }
        return result;
    }

    boolean hasUsableResumeContent(ObjectNode content) {
        if (content == null) return false;
        var personal = content.path("personalInfo");
        if (!personal.path("name").asString("").isBlank()
                || !personal.path("targetRole").asString("").isBlank()) return true;
        for (String field : new String[]{"education", "experiences", "projects", "skills", "awards"}) {
            if (!content.path(field).isEmpty()) return true;
        }
        return false;
    }

    private static Map<String, List<ResumeBlock>> resumeSections(List<SourceLine> lines) {
        Map<String, List<ResumeBlock>> sections = new LinkedHashMap<>();
        String activeSection = "";
        ResumeBlockBuilder current = null;
        for (SourceLine line : lines) {
            Matcher heading = MARKDOWN_HEADING.matcher(line.text());
            if (heading.matches()) {
                String title = clean(heading.group(2));
                if (activeSection.equals("projects") && PROJECT_FIELD_HEADINGS.contains(normalize(title))) {
                    if (current != null) current.lines().add(line);
                    continue;
                }
                String kind = sectionKind(title);
                if (!kind.isBlank()) {
                    if (current != null) addBlock(sections, activeSection, current.build());
                    current = null;
                    activeSection = kind;
                    if (!kind.equals("projects")) current = new ResumeBlockBuilder(title, line.number());
                } else if (activeSection.equals("projects")) {
                    if (current != null) addBlock(sections, activeSection, current.build());
                    current = new ResumeBlockBuilder(title, line.number());
                } else if (activeSection.equals("experience")) {
                    if (current != null) addBlock(sections, activeSection, current.build());
                    current = new ResumeBlockBuilder(title, line.number());
                } else {
                    if (current != null) addBlock(sections, activeSection, current.build());
                    current = null;
                    activeSection = "";
                }
                continue;
            }
            if (current != null) current.lines().add(line);
        }
        if (current != null) addBlock(sections, activeSection, current.build());
        return sections;
    }

    private static String sectionKind(String title) {
        String key = normalize(title);
        if (EDUCATION_LABELS.contains(key)) return "education";
        if (SKILL_LABELS.contains(key)) return "skills";
        if (PROJECT_LABELS.contains(key)) return "projects";
        if (METRIC_LABELS.contains(key)) return "metrics";
        if (EXPERIENCE_LABELS.contains(key)) return "experience";
        if (AWARD_LABELS.contains(key)) return "awards";
        return "";
    }

    private static void addBlock(Map<String, List<ResumeBlock>> sections, String section, ResumeBlock block) {
        if (!section.isBlank()) sections.computeIfAbsent(section, ignored -> new ArrayList<>()).add(block);
    }

    private static void addEducation(ArrayNode education, ResumeBlock block) {
        String details = blockText(block.lines());
        if (details.isBlank()) return;
        ObjectNode item = education.addObject();
        item.put("details", details);
        addSource(item, block.lineNumber());
        for (String part : details.split("[\\r\\n,，;；|/]+")) {
            String value = clean(part);
            if (value.isBlank()) continue;
            if (value.matches(".*(大学|学院|University|College).*")) {
                if (!item.has("institution")) item.put("institution", value);
            } else if (value.matches(".*(本科|硕士|博士|专科|Bachelor|Master|PhD|Associate).*")) {
                if (!item.has("degree")) item.put("degree", value);
            } else if (!item.has("major")) item.put("major", value);
        }
    }

    private static void addProject(ArrayNode projects, ResumeBlock block) {
        ObjectNode project = projects.addObject();
        project.put("name", clean(block.title()));
        ArrayNode technologies = project.putArray("technologies");
        ArrayNode outcomes = project.putArray("outcomes");
        List<String> description = new ArrayList<>();
        boolean inHighlights = false;
        boolean capturedTechLine = false;
        for (SourceLine source : block.lines()) {
            String text = clean(source.text());
            if (text.isBlank()) continue;
            Matcher label = SECTION_LABEL.matcher(text);
            Matcher heading = MARKDOWN_HEADING.matcher(source.text());
            boolean hasLabel = label.matches();
            boolean isHeading = heading.matches();
            String labelName = hasLabel ? normalize(label.group(1)) : isHeading ? normalize(text) : "";
            String labelValue = hasLabel ? clean(label.group(2)) : "";
            if (Set.of("项目亮点", "核心亮点", "成果", "结果", "outcomes", "highlights").contains(labelName)) {
                inHighlights = true;
                addNonBlank(outcomes, labelValue);
                continue;
            }
            if (Set.of("项目描述", "项目介绍", "description", "项目职责").contains(labelName)) {
                inHighlights = false;
                addNonBlank(description, labelValue);
                continue;
            }
            if (Set.of("技术栈", "使用技术", "techstack", "technologies").contains(labelName)) {
                addTechnologyLine(technologies, labelValue);
                capturedTechLine = !labelValue.isBlank();
                continue;
            }
            if (!capturedTechLine && description.isEmpty() && !inHighlights
                    && !text.contains("：") && !text.contains(":")) {
                addTechnologyLine(technologies, text);
                capturedTechLine = true;
                continue;
            }
            if (inHighlights) addNonBlank(outcomes, text);
            else addNonBlank(description, text);
        }
        description.removeIf(line -> isTechnologyEcho(line, technologies));
        String descriptionText = String.join("\n", description).trim();
        if (!descriptionText.isBlank()) project.put("description", descriptionText);
        addSource(project, block.lineNumber());
    }

    private static boolean isTechnologyEcho(String value, ArrayNode technologies) {
        String contentKey = normalize(value);
        if (contentKey.isBlank() || technologies.isEmpty()) return false;
        StringBuilder technologyKey = new StringBuilder();
        for (var technology : technologies) technologyKey.append(normalize(technology.asString("")));
        return contentKey.equals(technologyKey.toString());
    }

    private static void addSkillBlock(ArrayNode target, List<SourceLine> lines) {
        boolean hasBullets = lines.stream().anyMatch(line -> isListItem(line.text()));
        StringBuilder current = new StringBuilder();
        for (SourceLine line : lines) {
            String value = clean(line.text());
            if (value.isBlank()) {
                flushSkill(target, current);
                continue;
            }
            if (hasBullets && isListItem(value)) {
                flushSkill(target, current);
                current.append(stripListMarker(value));
            } else if (hasBullets && current.length() > 0) {
                current.append(' ').append(stripListMarker(value));
            } else {
                // Without list markers, keep each source paragraph as one skill statement.
                flushSkill(target, current);
                current.append(stripListMarker(value));
            }
        }
        flushSkill(target, current);
    }

    private static void addInlineSkillValues(ArrayNode target, String raw) {
        String value = clean(raw);
        Matcher label = SECTION_LABEL.matcher(value);
        if (label.matches() && SKILL_LABELS.contains(normalize(label.group(1)))) value = clean(label.group(2));
        addNonBlank(target, value);
    }

    private static void flushSkill(ArrayNode target, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isBlank()) addNonBlank(target, value);
        current.setLength(0);
    }

    private static boolean isListItem(String value) {
        return value != null && value.matches("^\\s*(?:[-*+•·]\\s*|\\d+[.)、）]\\s*).+");
    }

    private static String stripListMarker(String value) {
        return value == null ? "" : value.replaceFirst("^\\s*(?:(?:[-*+•·])\\s*|(?:\\d+[.)、）])\\s*)", "").trim();
    }

    private static void addAwardLines(ArrayNode target, String raw) {
        String value = clean(raw);
        if (value.isBlank()) return;
        Matcher label = SECTION_LABEL.matcher(value);
        if (label.matches() && AWARD_LABELS.contains(normalize(label.group(1)))) value = clean(label.group(2));
        for (String part : value.split("[;；|]+")) {
            String award = clean(part).replaceAll("^[-*+•·\\d.、)）]+\\s*", "");
            if (!award.isBlank()) target.addObject().put("name", award);
        }
    }

    private static void addTechnologyLine(ArrayNode target, String raw) {
        for (String technology : clean(raw).split("[,，、;；|\\s]+")) addNonBlank(target, technology);
    }

    private static void addNonBlank(ArrayNode target, String raw) {
        String value = clean(raw).replaceAll("^[-*+•·\\d.、)）]+\\s*", "").trim();
        if (!value.isBlank() && !containsText(target, value)) target.add(value);
    }

    private static void addNonBlank(List<String> target, String raw) {
        String value = clean(raw).replaceAll("^[-*+•·\\d.、)）]+\\s*", "").trim();
        if (!value.isBlank()) {
            String key = normalize(value);
            boolean alreadyPresent = target.stream().anyMatch(existing -> normalize(existing).equals(key));
            if (!alreadyPresent) target.add(value);
        }
    }

    private static boolean containsText(ArrayNode array, String value) {
        for (var node : array) if (node.asString("").equals(value)) return true;
        return false;
    }

    private static String blockText(List<SourceLine> lines) {
        return lines.stream().map(line -> clean(line.text())).filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right).orElse("");
    }

    ObjectNode parseQuestionBank(String markdown) {
        List<SourceLine> lines = sourceLines(markdown);
        ArrayNode items = nodes.arrayNode();
        List<Question> found = new ArrayList<>();
        int questionColumn = -1;
        int answerColumn = -1;
        int categoryColumn = -1;
        boolean questionTable = false;
        boolean questionContext = false;
        boolean answerStarted = false;
        boolean explicitAnswerContext = false;

        for (SourceLine line : lines) {
            String trimmed = line.text().trim();
            if (trimmed.isBlank()) continue;

            Matcher answerHeading = MARKDOWN_HEADING.matcher(trimmed);
            if (answerHeading.matches() && isAnswerHeading(answerHeading.group(2))) {
                questionContext = !found.isEmpty();
                answerStarted = questionContext;
                explicitAnswerContext = questionContext;
                continue;
            }
            if (isAnswerHeading(trimmed)) {
                questionContext = !found.isEmpty();
                answerStarted = questionContext;
                explicitAnswerContext = questionContext;
                continue;
            }

            Matcher labelledAnswer = ANSWER_LABEL.matcher(trimmed);
            if (labelledAnswer.matches()) {
                if (!found.isEmpty()) {
                    appendQuestionAnswer(found, labelledAnswer.group(1));
                    questionContext = true;
                    answerStarted = true;
                    explicitAnswerContext = true;
                }
                continue;
            }

            Matcher labelledQuestion = QUESTION_LABEL.matcher(trimmed);
            if (labelledQuestion.matches()) {
                String stem = clean(labelledQuestion.group(1));
                if (isQuestionText(stem)) {
                    found.add(new Question(stem, "", "", line.number()));
                    questionContext = true;
                    answerStarted = false;
                    explicitAnswerContext = false;
                }
                continue;
            }

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
                            questionContext = true;
                            answerStarted = !answer.isBlank();
                            explicitAnswerContext = !answer.isBlank();
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
                        questionContext = true;
                        answerStarted = !answer.isBlank();
                        explicitAnswerContext = !answer.isBlank();
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
                if (isQuestionText(stem)) {
                    if (isMarkdownNumberedQuestionHeading(trimmed) || !questionContext
                            || hasExplicitQuestionPrefix(trimmed)
                            || (!explicitAnswerContext && looksLikeQuestion(stem))
                            || (!answerStarted && isNextQuestionNumber(trimmed, found))
                            || (explicitAnswerContext && isNextQuestionNumber(trimmed, found)
                                    && looksLikeQuestion(stem))) {
                        found.add(new Question(stem, "", "", line.number()));
                        questionContext = true;
                        answerStarted = false;
                        explicitAnswerContext = false;
                    } else {
                        appendQuestionAnswer(found, trimmed);
                        answerStarted = true;
                    }
                }
                continue;
            }

            if (isQuestionText(trimmed) && trimmed.length() <= 500) {
                if (!explicitAnswerContext && looksLikeQuestion(trimmed)) {
                    found.add(new Question(clean(trimmed), "", "", line.number()));
                    questionContext = true;
                    answerStarted = false;
                    explicitAnswerContext = false;
                } else if (questionContext) {
                    appendQuestionAnswer(found, trimmed);
                    answerStarted = true;
                }
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
                || ROLE_LABELS.contains(normalized)
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

    private static boolean hasExplicitQuestionPrefix(String value) {
        return value != null && value.matches("(?i)^\\s{0,3}(?:#{1,6}\\s*)?(?:Q|Question|问题|题目)\\s*#?\\s*\\d+.*");
    }

    private static boolean isMarkdownNumberedQuestionHeading(String value) {
        return value != null && value.matches("^\\s{0,3}#{1,6}\\s+\\d+\\s*[.、)）|\\-]\\s*.+$");
    }

    private static boolean isNextQuestionNumber(String value, List<Question> questions) {
        Matcher numbered = Pattern.compile("^\\s{0,3}(?:#{1,6}\\s*)?(\\d+)\\s*[.、)）|\\-].*").matcher(value);
        if (!numbered.matches()) return false;
        try {
            return Integer.parseInt(numbered.group(1)) == questions.size() + 1;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isAnswerHeading(String value) {
        String key = normalize(value);
        return ANSWER_HEADING_LABELS.contains(key)
                || key.matches("(?:参考|标准|建议|示例|正确)?答案(?:要点|解析)?\\d+")
                || key.matches("(?:suggestedanswer|sampleanswer|answer|ans|a)\\d+");
    }

    private static boolean looksLikeQuestion(String value) {
        String text = clean(value);
        if (text.contains("?") || text.contains("？")) return true;
        if (text.matches("(?i)^(?:how|what|why|when|where|which|who|whom|whose|can|could|would|should|do|does|did|is|are|was|were|describe|explain|compare|design|discuss|tell|introduce|outline|walk me through|please describe|please explain)\\b.*")) {
            return true;
        }
        if (text.matches("^(?:请问|请你|请|如何|怎样|怎么|什么|为什么|为何|哪些|哪种|是否|能否|可否|描述|解释|比较|设计|讨论|介绍|举例|简述|谈谈|说说|分析|说明|阐述|实现|试说明|试述).+")) {
            return true;
        }
        return text.matches("^(?:你|你们|候选人).{0,24}(?:如何|怎样|怎么|什么|为什么|为何|哪些|哪种|是否|能否|可否|介绍|描述|解释|比较|设计|实现).*");
    }

    private static void appendQuestionAnswer(List<Question> questions, String raw) {
        String answer = clean(raw);
        if (questions.isEmpty() || answer.isBlank()) return;
        Question previous = questions.removeLast();
        String combined = previous.answer().isBlank() ? answer : previous.answer() + "\n" + answer;
        questions.add(new Question(previous.stem(), combined, previous.category(), previous.lineNumber()));
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
    private record ResumeBlock(String title, int lineNumber, List<SourceLine> lines) { }

    private static final class ResumeBlockBuilder {
        private final String title;
        private final int lineNumber;
        private final List<SourceLine> lines = new ArrayList<>();

        private ResumeBlockBuilder(String title, int lineNumber) {
            this.title = title;
            this.lineNumber = lineNumber;
        }

        private List<SourceLine> lines() { return lines; }
        private ResumeBlock build() { return new ResumeBlock(title, lineNumber, List.copyOf(lines)); }
    }
}
