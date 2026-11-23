package com.example.aichat.service;

import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * 文件类型嗅探：扩展名 + 魔数双重校验，返回真实格式。
 *
 * <p>安全要点：不信任客户端传来的 Content-Type 与扩展名；
 * 魔数识别结果与扩展名一致才放行，识别不出时按扩展名兜底但标记 LOW_CONFIDENCE。</p>
 */
@Component
public class FileTypeDetector {

    /** 嗅探结果 */
    public record Detected(String ext, String mimeType, boolean magicMatched) {}

    private static final Map<String, String> MIME_BY_EXT = Map.ofEntries(
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("csv", "text/csv"),
            Map.entry("log", "text/plain"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    );

    /**
     * 魔数嗅探（字节数组版，推荐）。
     *
     * <p>直接基于已读入内存的字节判断，完全不依赖 InputStream 的 mark/reset。
     * 生产上传路径走此重载，从根上避免 MultipartFile 底层流不支持标记重现导致的
     * "java.io.IOException: mark/reset not supported"。</p>
     */
    public Detected detect(byte[] bytes, String filename, boolean magicEnabled) {
        String ext = extOf(filename);
        if (!magicEnabled || bytes == null || bytes.length == 0) {
            return new Detected(ext, mimeOf(ext), true);
        }
        int n = Math.min(8, bytes.length);
        byte[] head = Arrays.copyOf(bytes, 8); // 不足 8 字节补 0，用 n 控制有效长度
        return fromMagic(head, n, ext);
    }

    /**
     * 魔数嗅探（InputStream 版，兼容保留）。
     *
     * <p>若传入流不支持 mark/reset（Servlet 上传流常见），自动包装成 BufferedInputStream
     * 再嗅探，避免直接抛 IOException。注意包装流可能预读底层字节；需要嗅探后继续完整读取
     * 同一流时请改用 {@link #detect(byte[], String, boolean)}（生产上传路径已采用）。</p>
     */
    public Detected detect(InputStream in, String filename, boolean magicEnabled) throws IOException {
        String ext = extOf(filename);
        if (!magicEnabled) {
            return new Detected(ext, mimeOf(ext), true);
        }
        InputStream bin = in.markSupported() ? in : new BufferedInputStream(in);
        bin.mark(8);
        byte[] head = new byte[8];
        int n = readFully(bin, head);
        bin.reset();
        return fromMagic(head, n, ext);
    }

    /** 根据 8 字节头解析真实类型（两版嗅探共用） */
    private Detected fromMagic(byte[] head, int n, String ext) {
        String magicExt = magicExt(head, n);
        if (magicExt != null) {
            // 魔数识别成功：以魔数为准（如 .docx 实际是 zip / pdf 改名都拦得住）
            return new Detected(magicExt, mimeOf(magicExt), true);
        }
        // 纯文本类魔数不可靠，回退扩展名（标记为未验证，由白名单继续把关）
        return new Detected(ext, mimeOf(ext), false);
    }

    /** 根据魔数返回规范扩展名，识别不出返回 null */
    private String magicExt(byte[] h, int n) {
        if (n < 4) return null;
        // PDF: %PDF
        if (h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') return "pdf";
        // OLE2 复合文档（老 Office）：D0 CF 11 E0 A1 B1 1A E1 —— 需再区分 doc/xls/ppt，这里统一按 ole 处理
        if (h[0] == (byte) 0xD0 && h[1] == (byte) 0xCF && h[2] == 0x11 && h[3] == (byte) 0xE0) return "ole";
        // ZIP 基 Office（docx/xlsx/pptx）：PK.. —— 需看内部条目，这里统一标 zip
        if (h[0] == 'P' && h[1] == 'K' && h[2] == 0x03 && h[3] == 0x04) return "zip";
        return null;
    }

    /** 读取至多 len 字节，返回实际读取数 */
    private int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n == -1) break;
            total += n;
        }
        return total;
    }

    public String extOf(String filename) {
        if (filename == null) return "";
        int i = filename.lastIndexOf('.');
        if (i < 0 || i == filename.length() - 1) return "";
        return filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public String mimeOf(String ext) {
        return MIME_BY_EXT.getOrDefault(ext == null ? "" : ext.toLowerCase(Locale.ROOT), "application/octet-stream");
    }

    /** 魔数识别为 zip/ole 时，用扩展名进一步确认具体 Office 子类型 */
    public String resolveOfficeType(String magicExt, String declaredExt) {
        if ("zip".equals(magicExt)) {
            return switch (declaredExt) {
                case "docx", "xlsx", "pptx" -> declaredExt;
                default -> "docx"; // 无法细分时按 docx 尝试，失败由解析器兜底
            };
        }
        if ("ole".equals(magicExt)) {
            return switch (declaredExt) {
                case "doc", "xls", "ppt" -> declaredExt;
                default -> "doc";
            };
        }
        return magicExt;
    }
}
