package com.example.aichat.service;

import com.example.aichat.config.UploadProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 原文件本地存储 + sha256 计算。
 *
 * <p>存储布局：{storageRoot}/yyyyMMdd/{sha256前2位}/{sha256}.{ext}
 * 用内容 hash 做文件名，天然去重；同 hash 文件只落一份物理文件。</p>
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final UploadProperties props;
    private Path root;

    public FileStorageService(UploadProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        this.root = Paths.get(props.getStorageRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("上传存储目录就绪 root={}", root);
        } catch (IOException e) {
            log.warn("创建上传存储目录失败 root={} reason={}", root, e.getMessage());
        }
    }

    /**
     * 计算输入流的 sha256（大小写统一为小写十六进制）。
     * 流会被消费，调用方如需再用请重置或重新取流。
     */
    public String sha256(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 直接对字节数组求 sha256（小文件/文本内容用） */
    public String sha256(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            return toHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 保存原文件（幂等：同 hash 已存在则不重复写盘）。
     *
     * @return 相对 storageRoot 的存储路径（统一用 / 分隔，便于落库与跨平台）
     */
    public String store(MultipartFile file, String hash, String ext) throws IOException {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String safeExt = sanitizeExt(ext);
        String name = hash + (safeExt.isEmpty() ? "" : "." + safeExt);
        Path dir = root.resolve(date).resolve(hash.substring(0, 2)).normalize();
        // 防目录穿越：解析后必须仍在 root 之下
        if (!dir.startsWith(root)) {
            throw new SecurityException("存储路径越界");
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(name).normalize();
        if (!Files.exists(target)) {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return root.relativize(target).toString().replace('\\', '/');
    }

    /** 保存字节数组（异步任务用；同 hash 幂等，已存在则不重复写盘） */
    public String storeBytes(byte[] bytes, String hash, String ext) throws IOException {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String safeExt = sanitizeExt(ext);
        String name = hash + (safeExt.isEmpty() ? "" : "." + safeExt);
        Path dir = root.resolve(date).resolve(hash.substring(0, 2)).normalize();
        if (!dir.startsWith(root)) {
            throw new SecurityException("存储路径越界");
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(name).normalize();
        if (!Files.exists(target)) {
            Files.write(target, bytes);
        }
        return root.relativize(target).toString().replace('\\', '/');
    }

    /** 读取已存储文件字节（重试用） */
    public byte[] readBytes(String relativePath) throws IOException {
        return Files.readAllBytes(resolve(relativePath));
    }

    /** 读取已存储文件（用于重新解析/下载） */
    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("存储路径为空");
        }
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root)) {
            throw new SecurityException("存储路径越界");
        }
        return p;
    }

    /** 删除原文件（删除文档时调用，best-effort） */
    public void deleteQuietly(String relativePath) {
        try {
            if (relativePath != null && !relativePath.isBlank()) {
                Files.deleteIfExists(resolve(relativePath));
            }
        } catch (Exception e) {
            log.warn("删除原文件失败 path={} reason={}", relativePath, e.getMessage());
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static String sanitizeExt(String ext) {
        if (ext == null) return "";
        String e = ext.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return e.length() > 10 ? e.substring(0, 10) : e;
    }
}
