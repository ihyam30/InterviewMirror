package local.interviewmirror.backend.resumes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Analysis-local IDs, immutable source offsets and backend-owned quotations. No silent cap. */
public final class ResumeEvidenceCatalog {
    private final Map<String, Evidence> entries = new LinkedHashMap<>();
    public ResumeEvidenceCatalog(UUID resumeId, String text) {
        int cursor = 0;
        while (cursor < text.length()) {
            int end = Math.min(cursor + 480, text.length());
            int newline = text.lastIndexOf('\n', end - 1);
            if (newline >= cursor + 120) end = newline + 1;
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String quote = text.substring(cursor, end);
            if (!quote.isBlank()) {
                String id = "E" + String.format("%03d", entries.size() + 1);
                entries.put(id, new Evidence(id, "RESUME", resumeId.toString(),
                        "resume_text:" + cursor + ":" + end, quote, sha256(quote)));
            }
            cursor = end;
        }
    }
    public List<Evidence> all() { return List.copyOf(entries.values()); }
    public List<Evidence> resolve(List<String> ids, boolean required) {
        if (ids == null || (required && ids.isEmpty())) throw new IllegalArgumentException("Missing evidence IDs");
        List<Evidence> result = new ArrayList<>();
        for (String id : ids.stream().distinct().toList()) {
            Evidence evidence = entries.get(id);
            if (evidence == null) throw new IllegalArgumentException("Unknown evidence ID");
            result.add(evidence);
        }
        return List.copyOf(result);
    }
    public List<Map<String, String>> promptEntries() {
        return entries.values().stream().map(e -> Map.of("evidenceId", e.evidenceId(),
                "sourceLocation", e.sourceLocation(), "text", e.quote())).toList();
    }
    public static String sha256(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public record Evidence(String evidenceId, String sourceType, String sourceId,
            String sourceLocation, String quote, String sha256) {}
}
