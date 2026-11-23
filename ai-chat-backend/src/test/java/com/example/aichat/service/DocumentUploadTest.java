package com.example.aichat.service;

import com.example.aichat.config.UploadProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 企业级上传核心逻辑单测：类型嗅探、文本解析、sha256、限流、白名单 */
class DocumentUploadTest {

    private UploadProperties props() {
        return new UploadProperties();
    }

    @Test
    void magicSniffPdf() throws Exception {
        FileTypeDetector d = new FileTypeDetector();
        byte[] pdf = "%PDF-1.7 fake content".getBytes(StandardCharsets.ISO_8859_1);
        FileTypeDetector.Detected r = d.detect(new ByteArrayInputStream(pdf), "renamed.txt", true);
        assertEquals("pdf", r.ext(), "魔数应识别为 pdf，忽略误导性扩展名");
        assertTrue(r.magicMatched());
    }

    @Test
    void magicSniffOfficeZip() throws Exception {
        FileTypeDetector d = new FileTypeDetector();
        byte[] zip = new byte[]{'P', 'K', 0x03, 0x04, 0, 0, 0, 0};
        FileTypeDetector.Detected r = d.detect(new ByteArrayInputStream(zip), "report.docx", true);
        assertEquals("docx", d.resolveOfficeType(r.ext(), "docx"));
    }

    @Test
    void magicSniffOnNonMarkableStream_noMarkResetException() throws Exception {
        // 模拟 Servlet / MultipartFile 底层流：不支持 mark/reset，调用即抛 IOException
        byte[] pdf = "%PDF-1.7 fake".getBytes(StandardCharsets.ISO_8859_1);
        InputStream nonMarkable = new FilterInputStream(new ByteArrayInputStream(pdf)) {
            @Override public boolean markSupported() { return false; }
            @Override public synchronized void mark(int readlimit) {
                throw new UnsupportedOperationException("mark not supported");
            }
            @Override public synchronized void reset() throws IOException {
                throw new IOException("mark/reset not supported");
            }
        };
        FileTypeDetector d = new FileTypeDetector();
        FileTypeDetector.Detected r = d.detect(nonMarkable, "renamed.txt", true);
        assertEquals("pdf", r.ext(), "不支持 mark/reset 的流也应嗅探成功，且不抛 mark/reset 异常");
        assertTrue(r.magicMatched());
    }

    @Test
    void magicSniffFromBytes_shortInputSafe() {
        // 生产路径：先读 byte[] 再嗅探，短于 8 字节也不能越界
        FileTypeDetector d = new FileTypeDetector();
        FileTypeDetector.Detected r = d.detect("hi".getBytes(StandardCharsets.UTF_8), "note.txt", true);
        assertEquals("txt", r.ext(), "短文本魔数不可靠，应回退扩展名");
        assertFalse(r.magicMatched());
    }

    @Test
    void plainTextParse() {
        DocumentParser parser = new DocumentParser(props());
        String content = "第一章 请假\n员工请假需提前3天申请。";
        DocumentParser.ParseResult r = parser.parse(content.getBytes(StandardCharsets.UTF_8), "md", "text/markdown");
        assertEquals("TEXT_ONLY", r.status());
        assertEquals(content, r.text());
    }

    @Test
    void sha256Deterministic() {
        FileStorageService storage = new FileStorageService(props());
        String a = storage.sha256("hello".getBytes(StandardCharsets.UTF_8));
        String b = storage.sha256("hello".getBytes(StandardCharsets.UTF_8));
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertNotEquals(a, storage.sha256("world".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rateLimitBlocksAfterLimit() {
        UploadProperties p = props();
        p.setRateLimitPerMinute(3);
        UploadRateLimiter limiter = new UploadRateLimiter(p);
        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertTrue(limiter.tryAcquire("u1"));
        assertFalse(limiter.tryAcquire("u1"), "第 4 次应被限流");
        assertTrue(limiter.tryAcquire("u2"), "不同用户互不影响");
    }

    @Test
    void dedupDefaultEnabledAndScannerNoop() {
        assertTrue(props().isDedupEnabled());
        VirusScanner scanner = new VirusScanner(props());
        assertTrue(scanner.scan("x".getBytes(), "a.txt").clean());
    }
}
