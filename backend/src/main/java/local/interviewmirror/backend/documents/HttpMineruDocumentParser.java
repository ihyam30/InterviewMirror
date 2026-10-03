package local.interviewmirror.backend.documents;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpMineruDocumentParser implements DocumentParser {
    private final URI endpoint;
    private final String workerToken;
    private final HttpClient client;
    private final Duration requestTimeout;

    public HttpMineruDocumentParser(
            @Value("${interviewmirror.mineru.worker-url:http://host.docker.internal:8765}") String workerUrl,
            @Value("${interviewmirror.mineru.worker-token:}") String workerToken,
            @Value("${interviewmirror.mineru.timeout:PT2M}") Duration requestTimeout) {
        this.endpoint = URI.create(workerUrl.replaceAll("/+$", "") + "/parse");
        this.workerToken = workerToken;
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String parse(String filename, String contentType, byte[] bytes) {
        if (workerToken == null || workerToken.isBlank()) {
            throw new DocumentParseException("PARSER_NOT_CONFIGURED", "本地解析服务尚未配置，请按开发文档启动 MinerU。 ");
        }
        String encodedFilename = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(filename.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("X-Worker-Token", workerToken)
                .header("X-Document-Filename-B64", encodedFilename)
                .header("X-Document-Content-Type", contentType)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = response.headers().firstValue("X-Error-Code").orElse("PARSER_FAILED");
                throw failure(code);
            }
            return response.body();
        } catch (java.net.http.HttpTimeoutException error) {
            throw new DocumentParseException("PARSER_TIMEOUT", "文档解析超时，请确认 MinerU 已就绪后重试。", error);
        } catch (IOException error) {
            throw new DocumentParseException("PARSER_UNAVAILABLE", "本地解析服务不可用，请先启动 MinerU。", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DocumentParseException("PARSER_INTERRUPTED", "解析任务被中断，请重试。", error);
        }
    }

    private static DocumentParseException failure(String code) {
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "PARSER_TIMEOUT" -> new DocumentParseException(code, "文档解析超时，请检查文件后重试。");
            case "ENCRYPTED_PDF" -> new DocumentParseException(code, "PDF 已加密，暂不支持解析。请上传未加密文件。");
            case "PDF_PAGE_LIMIT" -> new DocumentParseException(code, "PDF 页数超过 50 页限制。");
            case "INVALID_DOCUMENT" -> new DocumentParseException(code, "文件内容无法读取，请检查文件是否损坏。");
            case "UNSUPPORTED_DOCUMENT" -> new DocumentParseException(code, "文件格式暂不支持。");
            case "PARSER_BUSY" -> new DocumentParseException(code, "解析服务正在处理其他文件，请稍后重试。");
            default -> new DocumentParseException("PARSER_FAILED", "文档解析失败，请检查文件后重试。");
        };
    }
}
