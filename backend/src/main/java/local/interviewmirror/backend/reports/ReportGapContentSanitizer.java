package local.interviewmirror.backend.reports;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Normalizes persisted gap payloads at read/render time, including rows created by older versions. */
final class ReportGapContentSanitizer {
    static final String NOT_EVALUATED_TEXT = "本场没有足够的直接面试证据，无法评估此项。";

    private ReportGapContentSanitizer() {}

    static JsonNode sanitize(JsonNode content) {
        if (content == null || !content.isObject()) return content;
        JsonNode copy = content.deepCopy();
        ObjectNode root = (ObjectNode) copy;
        Map<String, String> jdRequirementTitles = jdRequirementTitles(root.path("requirements"));
        if (root.path("requirements") instanceof ArrayNode requirements) {
            for (JsonNode node : requirements) {
                if (!(node instanceof ObjectNode requirement)) continue;
                JsonNode evidence = requirement.path("evidence");
                boolean hasResumeEvidence = hasSource(evidence, "RESUME");
                boolean hasTurnEvidence = hasSource(evidence, "TURN");
                Double resumeScore = hasResumeEvidence && requirement.path("resumeScore").isNumber()
                        ? requirement.path("resumeScore").asDouble() : null;
                Double interviewScore = hasTurnEvidence && requirement.path("interviewScore").isNumber()
                        ? requirement.path("interviewScore").asDouble() : null;
                if (resumeScore == null) requirement.putNull("resumeScore");
                requirement.put("resumeStatus", resumeStatus(resumeScore));
                if (interviewScore == null) {
                    requirement.putNull("interviewScore");
                    requirement.put("interviewStatus", "UNASSESSED");
                    requirement.put("rationale", NOT_EVALUATED_TEXT);
                } else {
                    requirement.put("interviewStatus", interviewScore >= 4 ? "DEMONSTRATED"
                            : interviewScore >= 3 ? "PARTIAL" : "NOT_DEMONSTRATED");
                }
            }
        }
        if (root.path("gaps") instanceof ArrayNode gaps) {
            for (JsonNode node : gaps) {
                if (!(node instanceof ObjectNode gap)) continue;
                boolean hasTurnEvidence = hasUsableTurnEvidence(gap.path("evidence"));
                if (!hasTurnEvidence || "NOT_EVALUATED".equals(gap.path("status").asString())) {
                    String requirementTitle = jdRequirementTitles.getOrDefault(
                            gap.path("requirementId").asString(), "未评估要求");
                    markNotEvaluated(gap, requirementTitle);
                } else {
                    gap.set("evidence", turnEvidence(gap.path("evidence")));
                }
            }
            refreshTopGaps(root.path("summary"), gaps);
        }
        return root;
    }

    private static void markNotEvaluated(ObjectNode gap, String requirementTitle) {
        gap.put("status", "NOT_EVALUATED");
        gap.put("title", requirementTitle);
        gap.put("reason", NOT_EVALUATED_TEXT);
        gap.put("recommendation", "");
        gap.put("gapSize", 0);
        gap.put("priorityScore", 0);
        gap.put("priority", "LOW");
        gap.putArray("attribution").removeAll();
        gap.putArray("evidence").removeAll();
    }

    private static Map<String, String> jdRequirementTitles(JsonNode requirements) {
        Map<String, String> titles = new LinkedHashMap<>();
        if (!requirements.isArray()) return titles;
        for (JsonNode requirement : requirements) {
            String id = requirement.path("requirementId").asString("").trim();
            String title = requirement.path("text").asString("").trim();
            if (!id.isEmpty() && !title.isEmpty() && hasSource(requirement.path("evidence"), "JD")) {
                titles.putIfAbsent(id, title);
            }
        }
        return titles;
    }

    private static boolean hasUsableTurnEvidence(JsonNode evidence) {
        if (!evidence.isArray()) return false;
        for (JsonNode item : evidence) {
            if ("TURN".equals(item.path("sourceType").asString())
                    && item.path("quote").isTextual() && !item.path("quote").asString().isBlank()) return true;
        }
        return false;
    }

    private static ArrayNode turnEvidence(JsonNode evidence) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (evidence.isArray()) {
            for (JsonNode item : evidence) {
                if ("TURN".equals(item.path("sourceType").asString())
                        && item.path("quote").isTextual() && !item.path("quote").asString().isBlank()) {
                    result.add(item.deepCopy());
                }
            }
        }
        return result;
    }

    private static void refreshTopGaps(JsonNode summaryNode, ArrayNode gaps) {
        if (!(summaryNode instanceof ObjectNode summary) || !summary.has("topGaps")) return;
        List<ObjectNode> eligible = new ArrayList<>();
        for (JsonNode node : gaps) {
            if (node instanceof ObjectNode gap && !"NOT_EVALUATED".equals(gap.path("status").asString())
                    && gap.path("confidence").isNumber() && gap.path("confidence").asDouble() >= .5) {
                eligible.add(gap);
            }
        }
        eligible.sort(Comparator.comparingDouble((ObjectNode gap) -> gap.path("priorityScore").asDouble()).reversed());
        ArrayNode topGaps = summary.putArray("topGaps");
        for (ObjectNode gap : eligible.stream().limit(3).toList()) {
            ObjectNode topGap = topGaps.addObject();
            for (String field : List.of("gapId", "title", "priority")) {
                if (gap.has(field)) topGap.set(field, gap.get(field).deepCopy());
            }
        }
    }

    private static boolean hasSource(JsonNode evidence, String expected) {
        if (!evidence.isArray()) return false;
        for (JsonNode item : evidence) if (expected.equals(item.path("sourceType").asString())) return true;
        return false;
    }

    private static String resumeStatus(Double score) {
        if (score == null) return "NO_EVIDENCE";
        return score >= 4 ? "MATCH" : score >= 3 ? "PARTIAL" : "NO_EVIDENCE";
    }
}
