package local.interviewmirror.backend.resumes;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.exception.WriteLimitReachedException;

/** Isolated JVM entrypoint. Parent kills this JVM and OCR descendants on deadline. */
public final class ResumeParseProcess {
    private ResumeParseProcess() {}
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        try {
            byte[] bytes = Files.readAllBytes(Path.of(args[0]));
            if ("application/pdf".equals(args[3])) {
                // Require OCR for PDFs even when some pages have text, so scanned pages cannot silently disappear.
                if (!new TesseractOCRParser().hasTesseract()) throw new IllegalArgumentException("OCR_NOT_CONFIGURED");
                Process languages = new ProcessBuilder("tesseract", "--list-langs").redirectErrorStream(true).start();
                String installed = new String(languages.getInputStream().readNBytes(65536), java.nio.charset.StandardCharsets.UTF_8);
                languages.waitFor();
                if (languages.exitValue() != 0) throw new IllegalArgumentException("OCR_NOT_CONFIGURED");
                var available = installed.lines().map(String::strip).collect(java.util.stream.Collectors.toSet());
                for (String language : args[6].split("\\+")) if (!available.contains(language)) throw new IllegalArgumentException("OCR_NOT_CONFIGURED");
                try (var pdf = Loader.loadPDF(bytes)) {
                    if (pdf.isEncrypted()) throw new IllegalArgumentException("ENCRYPTED_PDF");
                    if (pdf.getNumberOfPages() > Integer.parseInt(args[5])) throw new IllegalArgumentException("PDF_PAGE_LIMIT");
                }
            }
            ParseContext context = new ParseContext();
            TesseractOCRConfig ocr = new TesseractOCRConfig();
            ocr.setLanguage(args[6]);
            ocr.setTimeoutSeconds(Integer.parseInt(args[8]));
            context.set(TesseractOCRConfig.class, ocr);
            PDFParserConfig pdf = new PDFParserConfig();
            pdf.setSortByPosition(true);
            pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.AUTO);
            context.set(PDFParserConfig.class, pdf);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, args[2]);
            metadata.set(Metadata.CONTENT_TYPE, args[3]);
            BodyContentHandler handler = new BodyContentHandler(Integer.parseInt(args[4]));
            try (var input = Files.newInputStream(Path.of(args[0]))) {
                new AutoDetectParser().parse(input, handler, metadata, context);
            }
            String text = handler.toString().replace("\u0000", "").replace("\r\n", "\n")
                    .replace('\r', '\n').replaceAll("[\\t\\x0B\\f]+", " ")
                    .replaceAll("(?m)[ ]+$", "").replaceAll("\n{3,}", "\n\n").strip();
            if (text.isBlank()) {
                if (!new TesseractOCRParser().hasTesseract()) throw new IllegalArgumentException("OCR_NOT_CONFIGURED");
                throw new IllegalArgumentException("EMPTY_RESUME_TEXT");
            }
            Files.writeString(output, text);
        } catch (Exception error) {
            String code = "INVALID_DOCUMENT";
            if (WriteLimitReachedException.isWriteLimitReached(error)) code = "RESUME_TEXT_LIMIT";
            else if (error instanceof org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) code = "ENCRYPTED_PDF";
            else if (error instanceof IllegalArgumentException && error.getMessage() != null
                    && java.util.Set.of("ENCRYPTED_PDF", "PDF_PAGE_LIMIT", "OCR_NOT_CONFIGURED", "EMPTY_RESUME_TEXT").contains(error.getMessage())) code = error.getMessage();
            Files.writeString(Path.of(args[1] + ".error"), code);
            Files.writeString(Path.of(args[1] + ".type"), error.getClass().getName());
            System.exit(2);
        }
    }
}
