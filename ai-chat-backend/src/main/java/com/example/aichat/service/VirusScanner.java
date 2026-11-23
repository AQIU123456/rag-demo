package com.example.aichat.service;

import com.example.aichat.config.UploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 病毒扫描可插拔接口。
 *
 * <p>默认 noop（无外部 ClamAV 服务时放行并记录）；配置 virus-scanner=clamav 时
 * 预留对接点。设计成接口风格是为了不引入外部服务依赖即可编译运行。</p>
 */
@Component
public class VirusScanner {

    private static final Logger log = LoggerFactory.getLogger(VirusScanner.class);
    private final UploadProperties props;

    public VirusScanner(UploadProperties props) {
        this.props = props;
    }

    /** 扫描结果 */
    public record ScanResult(boolean clean, String detail) {}

    public ScanResult scan(byte[] bytes, String filename) {
        String scanner = props.getVirusScanner() == null ? "noop" : props.getVirusScanner().toLowerCase();
        if ("clamav".equals(scanner)) {
            log.warn("ClamAV 扫描器尚未配置外部服务，本次放行 filename={}（生产环境须接入）", filename);
            return new ScanResult(true, "CLAMAV_NOT_CONFIGURED");
        }
        // noop：仅记录，不阻断
        return new ScanResult(true, "NOOP");
    }

    public boolean isBlockOnDetect() {
        return props.isVirusScanBlock();
    }
}
