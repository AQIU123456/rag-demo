package com.example.aichat.service;

import com.example.aichat.config.UploadProperties;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 多格式文档解析器：按扩展名路由到 POI / PDFBox / 纯文本读取。
 *
 * <p>输出统一为纯文本，解析结果交回 KnowledgeDocumentService.uploadPlainText 链路，
 * 不触碰任何向量/分块代码（复用既有 scheduleIndex）。</p>
 */
@Service
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);
    private final UploadProperties props;

    public DocumentParser(UploadProperties props) {
        this.props = props;
    }

    /** 解析结果 */
    public record ParseResult(String text, String status) {}

    /** 支持的纯文本扩展名 */
    private static final java.util.Set<String> TEXT_EXTS = java.util.Set.of(
            "txt", "md", "markdown", "json", "xml", "csv", "log");

    /**
     * 解析入口。
     *
     * @param ext  规范化后的扩展名（已由 FileTypeDetector 嗅探确认）
     * @param mime 真实 MIME
     * @return 纯文本 + 解析状态（SUCCESS / TEXT_ONLY / FAILED）
     */
    public ParseResult parse(byte[] bytes, String ext, String mime) {
        try {
            String text;
            String status;
            if (TEXT_EXTS.contains(ext)) {
                text = new String(bytes, StandardCharsets.UTF_8);
                status = "TEXT_ONLY";
            } else {
                text = switch (ext) {
                    case "pdf" -> parsePdf(bytes);
                    case "docx", "xlsx", "pptx", "doc", "xls", "ppt" -> parseOffice(bytes, ext);
                    default -> new String(bytes, StandardCharsets.UTF_8);
                };
                status = "SUCCESS";
            }
            // 统一长度上限，防止超长文档挤爆上下文
            int max = props.getMaxParsedTextLength();
            if (text.length() > max) {
                log.warn("解析文本超长截断 ext={} original={} truncatedTo={}", ext, text.length(), max);
                text = text.substring(0, max);
            }
            return new ParseResult(text == null ? "" : text, status);
        } catch (Exception e) {
            log.warn("文档解析失败 ext={} mime={} reason={}", ext, mime, e.getMessage());
            return new ParseResult("", "FAILED");
        }
    }

    /** PDF：PDFBox 抽取文本 */
    private String parsePdf(byte[] bytes) throws IOException {
        try (PDDocument doc = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    /** Office：POI 统一 Extractor（doc/docx/xls/xlsx/ppt/pptx 通吃） */
    private String parseOffice(byte[] bytes, String ext) throws Exception {
        // createExtractor(InputStream) 内部自动识别 OOXML(zip) 与老 OLE2 两种容器
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return ExtractorFactory.createExtractor(in).getText();
        }
    }
}
