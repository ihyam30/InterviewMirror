package local.interviewmirror.backend.resumes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import local.interviewmirror.backend.common.ApiException;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ResumeParser {
    private final ResumeProperties properties;
    private final Semaphore slots;
    public ResumeParser(ResumeProperties properties) {
        this.properties = properties;
        slots = new Semaphore(properties.parseConcurrency());
    }
    public String detect(byte[] bytes, String filename) {
        try {
            String type = new Tika().detect(bytes, filename);
            String suffix = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (!properties.allowedExtensions().contains(suffix)) throw failure("UNSUPPORTED_RESUME_TYPE", "该简历格式未启用。");
            String expected = switch (suffix) {
                case "pdf" -> "application/pdf";
                case "doc" -> "application/msword";
                case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "txt" -> "text/plain";
                default -> "";
            };
            if (!expected.equals(type)) throw failure("UNSUPPORTED_RESUME_TYPE", "简历仅支持真实的 PDF、DOC、DOCX、TXT 文件。");
            return type;
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw failure("INVALID_DOCUMENT", "无法识别文件格式。"); }
    }
    public String parse(byte[] bytes, String filename, String contentType) {
        if (!slots.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PARSER_BUSY", "简历解析繁忙，请稍后上传。");
        Process process = null;
        Path directory = null;
        try {
            directory = Files.createTempDirectory("interviewmirror-resume-");
            Path input = directory.resolve("input"), output = directory.resolve("output");
            Files.write(input, bytes);
            // Keep every child temporary file inside the job directory, so a force-killed parse cannot
            // leave Tika/OCR intermediates behind in the system temporary directory.
            Path temporary = Files.createDirectory(directory.resolve("tmp"));
            String executable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            List<String> command = new ArrayList<>(List.of(executable, "-Xmx384m", "-XX:-UsePerfData", "-Djava.awt.headless=true",
                    "-Djava.io.tmpdir=" + temporary.toAbsolutePath().normalize()));
            boolean packaged = ResumeParseProcess.class.getResource("ResumeParseProcess.class").toString().contains("BOOT-INF");
            if (packaged) command.add("-Dloader.main=" + ResumeParseProcess.class.getName());
            command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), packaged
                    ? "org.springframework.boot.loader.launch.PropertiesLauncher" : ResumeParseProcess.class.getName(),
                    input.toString(), output.toString(), filename, contentType,
                    Integer.toString(properties.textLimit()), Integer.toString(properties.pageLimit()),
                    properties.ocrLanguages(), properties.tesseractPath(),
                    Long.toString(Math.max(1, properties.parseTimeout().toSeconds()))));
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("process.log").toFile());
            // Native helpers such as Tesseract resolve their scratch space from the environment as well.
            for (String key : List.of("TMPDIR", "TEMP", "TMP")) builder.environment().put(key, temporary.toString());
            if (!properties.tesseractPath().isBlank()) {
                String pathKey = builder.environment().keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
                builder.environment().put(pathKey, properties.tesseractPath() + java.io.File.pathSeparator + builder.environment().getOrDefault(pathKey, ""));
            }
            process = builder.start();
            if (!process.waitFor(properties.parseTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw failure("PARSER_TIMEOUT", "简历解析超时，请检查文件后重试。");
            }
            if (process.exitValue() != 0 || !Files.exists(output)) {
                Path error = directory.resolve("output.error");
                String code = Files.exists(error) ? Files.readString(error) : "INVALID_DOCUMENT";
                Path typeFile = directory.resolve("output.type");
                String failureType = Files.exists(typeFile) ? Files.readString(typeFile) : "ChildProcessFailure";
                org.slf4j.LoggerFactory.getLogger(ResumeParser.class).warn("resume parsing failed code={} type={} exit={}", code, failureType, process.exitValue());
                throw failure(code, switch (code) {
                    case "ENCRYPTED_PDF" -> "请上传未加密的 PDF。";
                    case "PDF_PAGE_LIMIT" -> "PDF 页数超过限制。";
                    case "RESUME_TEXT_LIMIT" -> "简历文本超过长度限制。";
                    case "OCR_NOT_CONFIGURED" -> "未提取到正文，请安装 Tesseract 和中文、英文语言包。";
                    case "EMPTY_RESUME_TEXT" -> "简历未提取到有效正文。";
                    default -> "简历解析失败，请检查文件或 OCR 环境。";
                });
            }
            return Files.readString(output);
        } catch (ApiException error) { throw error; }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw failure("PARSER_INTERRUPTED", "简历解析被中断。");
        } catch (Exception error) { throw failure("PARSER_UNAVAILABLE", "简历解析环境不可用。"); }
        finally {
            if (process != null && process.isAlive()) {
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            // Reap descendants as well, so no OCR helper keeps a handle on the job directory during cleanup.
            kill(process);
            if (directory != null) {
                for (int attempt = 0; attempt < 4; attempt++) {
                    try (var paths = Files.walk(directory)) {
                        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                        break;
                    } catch (java.io.IOException error) {
                        if (attempt == 3) org.slf4j.LoggerFactory.getLogger(ResumeParser.class).warn("resume temporary cleanup failed type={}", error.getClass().getSimpleName());
                        else try { Thread.sleep(100L << attempt); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    }
                }
            }
            slots.release();
        }
    }
    /** Terminates the parse JVM and its OCR descendants, waiting until none of them is alive. */
    private static void kill(Process process) {
        if (process == null || (!process.isAlive() && process.descendants().findAny().isEmpty())) return;
        var handles = new java.util.ArrayList<ProcessHandle>();
        process.descendants().forEach(handles::add);
        handles.add(process.toHandle());
        handles.forEach(ProcessHandle::destroyForcibly);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && handles.stream().anyMatch(ProcessHandle::isAlive)) {
            try { Thread.sleep(50); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
        }
    }
    private static ApiException failure(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
}
