package local.interviewmirror.backend.reports;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

final class EvidenceCatalog {
    private final Map<String, Evidence> entries = new LinkedHashMap<>();
    private int next = 1;

    EvidenceCatalog(ReportSource source) {
        // Reserve evidence IDs for every answer first. Resume snapshots can contain
        // many long fields, but must never crowd turn evidence out of the catalog.
        for (ReportSource.SourceTurn turn : source.turns()) {
            if (turn.answer() != null && !turn.answer().isBlank()) {
                addText("TURN", turn.id().toString(), "turn:" + turn.sequence() + ":answer", turn.answer());
            }
        }
        String jd = source.sourceSnapshot().path("jdText").asString(source.jdText() == null ? "" : source.jdText());
        addText("JD", "jd-" + source.interviewId(), "jd", jd);
        JsonNode resume = source.sourceSnapshot().path("resume");
        if (resume.isObject()) collectStrings(resume.path("content"), "RESUME",
                resume.path("id").asString("resume"), "resume", 0);
    }

    private void collectStrings(JsonNode node, String type, String sourceId, String path, int depth) {
        if (node == null || node.isNull() || depth > 12 || entries.size() > 250) return;
        if (node.isTextual()) { addText(type, sourceId, path, node.asString()); return; }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collectStrings(node.get(i), type, sourceId, path + "/" + i, depth + 1);
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                var field = fields.next();
                collectStrings(field.getValue(), type, sourceId, path + "/" + field.getKey(), depth + 1);
            }
        }
    }

    private void addText(String type, String sourceId, String location, String text) {
        if (text == null || text.isBlank() || entries.size() >= 250) return;
        String normalized = text.trim();
        if (normalized.length() <= 480) { add(type, sourceId, location, normalized); return; }
        for (int start = 0; start < normalized.length() && entries.size() < 250; start += 400) {
            String quote = normalized.substring(start, Math.min(normalized.length(), start + 480)).trim();
            if (!quote.isBlank()) add(type, sourceId, location + ":" + start, quote);
        }
    }

    private void add(String type, String sourceId, String location, String quote) {
        String id = "E" + String.format("%03d", next++);
        entries.put(id, new Evidence(id, type, sourceId, location, quote, sha256(quote)));
    }

    Evidence require(String id) {
        if (id == null || !entries.containsKey(id)) throw new IllegalArgumentException("model returned unknown evidence id");
        return entries.get(id);
    }
    List<Evidence> all() { return List.copyOf(entries.values()); }
    String promptJson(tools.jackson.databind.ObjectMapper json) {
        try { return json.writeValueAsString(entries.values().stream().map(e -> Map.of("evidenceId", e.id(), "sourceType", e.sourceType(),
                "sourceLocation", e.sourceLocation(), "text", e.quote())).toList()); }
        catch (Exception e) { throw new IllegalStateException("evidence context serialization failed", e); }
    }
    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    record Evidence(String id, String sourceType, String sourceId, String sourceLocation, String quote, String sha256) {
        Map<String, Object> toMap() {
            return Map.of("evidenceId", id, "sourceType", sourceType, "sourceId", sourceId, "sourceLocation", sourceLocation,
                    "quote", quote, "sha256", sha256);
        }
    }
}
