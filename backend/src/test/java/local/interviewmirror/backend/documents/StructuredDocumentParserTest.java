package local.interviewmirror.backend.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StructuredDocumentParserTest {
    private final StructuredDocumentParser parser = new StructuredDocumentParser();

    @Test
    void extractsQuestionsAndAnswersFromMineruHtmlTable() {
        String markdown = """
                ## Question Bank Q11
                <table><tbody><tr><td colspan="2">How would you design a reliable document parsing task?</td><td colspan="2">How do you prevent duplicate worker claims? How should an expired task lease be recovered?</td></tr><tr><td colspan="4"></td></tr><tr><td>#</td><td colspan="2">Question</td><td>Answer</td></tr><tr><td>1</td><td colspan="2">How would you design a reliable document parsing task?</td><td>Synthetic answer one</td></tr><tr><td>2</td><td colspan="2">How do you prevent duplicate worker claims?</td><td>Synthetic answer two</td></tr><tr><td>3</td><td colspan="2">How should an expired task lease be recovered?</td><td>Synthetic answer three</td></tr></tbody></table>
                """;

        var questions = parser.parse(DocumentType.QUESTION_BANK, markdown).path("questions");

        assertEquals(3, questions.size());
        assertEquals("How would you design a reliable document parsing task?",
                questions.get(0).path("stem").asString());
        assertEquals("Synthetic answer one", questions.get(0).path("answer").asString());
        assertEquals("How should an expired task lease be recovered?",
                questions.get(2).path("stem").asString());
    }

    @Test
    void extractsResumeSectionsAndMultipleProjectsFromMineruMarkdownHeadings() {
        String markdown = """
                # Synthetic Candidate — Java Intern

                ## 教育背景
                示例大学 软件工程 2023-09 至 2027-06

                ## 项目经历
                ### Agentic RAG 问答平台
                Spring Boot MyBatis-Plus PostgreSQL Redis
                项目描述：
                构建文档问答平台，支持多路检索和引用溯源。
                项目亮点：
                1. 引入 RRF 融合与 Rerank，提升召回相关性。
                2. 增加超时降级，保证模型服务异常时可用。
                ### 智能云图库
                Spring Boot MySQL Redis
                项目描述：支持图片上传、检索和团队空间管理。

                ## 专业技能
                - 熟悉 Java 基础与集合、多线程。
                - 熟悉 Spring Boot、MyBatis-Plus。

                ## 荣誉奖项
                - 校级程序设计竞赛一等奖
                - 蓝桥杯省赛二等奖
                """;

        var content = parser.parse(DocumentType.RESUME, markdown);

        assertEquals("interviewmirror.resume-content.v1", content.path("schemaVersion").asString());
        assertEquals(1, content.path("education").size());
        assertTrue(content.path("education").get(0).path("details").asString().contains("示例大学"));
        assertEquals(2, content.path("projects").size());
        assertEquals("Agentic RAG 问答平台", content.path("projects").get(0).path("name").asString());
        assertTrue(content.path("projects").get(0).path("technologies").size() >= 4);
        assertTrue(content.path("projects").get(0).path("description").asString().contains("多路检索"));
        assertEquals(2, content.path("projects").get(0).path("outcomes").size());
        assertTrue(content.path("projects").get(1).path("technologies").size() >= 3);
        assertEquals(2, content.path("skills").size());
        assertTrue(content.path("skills").get(0).asString().contains("集合、多线程"));
        assertEquals(2, content.path("awards").size());
        assertTrue(parser.hasUsableResumeContent(content));
    }

    @Test
    void keepsMineruProjectFieldHeadingsInsideTheirProjectBlocks() {
        var content = parser.parse(DocumentType.RESUME, """
                ## 项目经历

                ## Synthetic RAG Project

                SpringBoot MyBatis-Plus Redis PostgreSQL

                ## 技术栈:

                SpringBoot MyBatis-Plus Redis PostgreSQL

                ## 项目描述:

                Built a RAG application with retrieval and citation tracing.
                Built a RAG · application with retrieval and citation tracing.

                ## 项目亮点:

                1. Added hybrid retrieval with reranking.

                ## Synthetic Image Library

                Java MySQL Redis

                ## 项目描述:

                Built an image library with team workspaces.

                ## 项目亮点:

                1. Added asynchronous image expansion.
                """);

        var projects = content.path("projects");

        assertEquals(2, projects.size());
        assertEquals("Synthetic RAG Project", projects.get(0).path("name").asString());
        assertEquals(4, projects.get(0).path("technologies").size());
        assertEquals("Built a RAG application with retrieval and citation tracing.",
                projects.get(0).path("description").asString());
        assertEquals(1, projects.get(0).path("outcomes").size());
        assertEquals("Synthetic Image Library", projects.get(1).path("name").asString());
        assertEquals("Built an image library with team workspaces.",
                projects.get(1).path("description").asString());
        assertEquals("Added asynchronous image expansion.", projects.get(1).path("outcomes").get(0).asString());
    }

    @Test
    void recognizesProjectSubheadingsWithoutColonAsFields() {
        var projects = parser.parse(DocumentType.RESUME, """
                ## 项目经历
                ### Knowledge Assistant
                ## 技术栈
                Java Spring Boot PostgreSQL
                ## 项目描述
                Built a knowledge assistant with hybrid retrieval.
                ## 项目亮点
                1. Added reranking and citation tracing.
                """).path("projects");

        assertEquals(1, projects.size());
        assertEquals("Knowledge Assistant", projects.get(0).path("name").asString());
        assertEquals(4, projects.get(0).path("technologies").size());
        assertEquals("Built a knowledge assistant with hybrid retrieval.",
                projects.get(0).path("description").asString());
        assertEquals("Added reranking and citation tracing.", projects.get(0).path("outcomes").get(0).asString());
    }

    @Test
    void retainsExperienceContentFollowingCompanyHeadings() {
        var experiences = parser.parse(DocumentType.RESUME, """
                ## 工作经历
                ### 示例科技有限公司
                Java 后端实习生 · 2025.06 - 2025.09
                负责订单服务接口开发与数据库查询优化。
                ### 另一家公司
                测试开发实习生 · 2024.07 - 2024.09
                编写接口自动化测试并维护回归用例。
                """).path("experiences");

        assertEquals(2, experiences.size());
        assertEquals("示例科技有限公司", experiences.get(0).path("organization").asString());
        assertTrue(experiences.get(0).path("description").asString().contains("订单服务接口开发"));
        assertEquals("另一家公司", experiences.get(1).path("organization").asString());
        assertTrue(experiences.get(1).path("description").asString().contains("接口自动化测试"));
    }

    @Test
    void extractsTargetRoleFromResumeIntentLabel() {
        var content = parser.parse(DocumentType.RESUME, "求职意向：Java 后端开发实习生");

        assertEquals("Java 后端开发实习生", content.path("personalInfo").path("targetRole").asString());
        assertTrue(parser.hasUsableResumeContent(content));
    }

    @Test
    void groupsSkillBulletContinuationAndKeepsPunctuationInsideEachEntry() {
        var content = parser.parse(DocumentType.RESUME, """
                ## 专业技能
                熟悉 Java 基础，了解集合、多线程、JVM 基础知识。
                • 熟悉 Spring Boot、MyBatis-Plus 开发
                  能够完成 RESTful 接口设计。
                - 熟悉 MySQL 数据库设计及常见 SQL 优化。
                """);

        assertEquals(3, content.path("skills").size());
        assertEquals("熟悉 Java 基础，了解集合、多线程、JVM 基础知识。", content.path("skills").get(0).asString());
        assertEquals("熟悉 Spring Boot、MyBatis-Plus 开发 能够完成 RESTful 接口设计。",
                content.path("skills").get(1).asString());
    }

    @Test
    void doesNotAttachGlobalProjectResultsToTheFirstProject() {
        var content = parser.parse(DocumentType.RESUME, """
                ## 项目经历
                ### 项目甲
                Java Spring Boot
                项目描述：甲项目描述。
                项目亮点：
                甲项目自己的亮点。
                ### 项目乙
                Java PostgreSQL
                项目描述：乙项目描述。
                项目亮点：
                乙项目自己的亮点。
                ## 项目成果
                - 汇总成果甲
                - 汇总成果乙
                """);

        assertEquals(2, content.path("projects").size());
        assertEquals(1, content.path("projects").get(0).path("outcomes").size());
        assertEquals("甲项目自己的亮点。", content.path("projects").get(0).path("outcomes").get(0).asString());
        assertEquals("乙项目自己的亮点。", content.path("projects").get(1).path("outcomes").get(0).asString());
        assertEquals(2, content.path("metrics").size());
    }

    @Test
    void doesNotTreatEmptyResumeHeadingsAsRecognizedContent() {
        var content = parser.parse(DocumentType.RESUME, """
                ## 教育背景
                ## 项目经历
                ## 专业技能
                ## 荣誉奖项
                """);

        assertFalse(parser.hasUsableResumeContent(content));
    }

    @Test
    void retainsCompatibilityWithInlineLabeledResumeFields() {
        var content = parser.parse(DocumentType.RESUME, """
                姓名：Synthetic Candidate
                教育经历：示例大学，软件工程，本科
                技能：Java, Spring Boot, PostgreSQL
                项目经历：文档问答项目
                荣誉奖项：校级竞赛一等奖
                """);

        assertEquals("Synthetic Candidate", content.path("personalInfo").path("name").asString());
        assertEquals(1, content.path("education").size());
        assertEquals(1, content.path("skills").size());
        assertEquals("Java, Spring Boot, PostgreSQL", content.path("skills").get(0).asString());
        assertEquals("文档问答项目", content.path("projects").get(0).path("name").asString());
        assertEquals(1, content.path("awards").size());
    }
}
