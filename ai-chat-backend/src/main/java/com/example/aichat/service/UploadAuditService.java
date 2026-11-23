package com.example.aichat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 上传审计日志。
 *
 * <p>非侵入：落地为结构化日志（SECURITY-AUDIT-UPLOAD），不新建审计表，
 * 便于接入现有日志平台（Graylog 等）按 source/action 检索。</p>
 */
@Service
public class UploadAuditService {

    private static final Logger log = LoggerFactory.getLogger(UploadAuditService.class);

    public void log(String action, String user, String tenant, String filename, String detail) {
        log.info("SECURITY-AUDIT-UPLOAD action={} user={} tenant={} filename={} detail={}",
                action, user, tenant, sanitize(filename), sanitize(detail));
    }

    /** 防日志注入：去掉换行与控制字符 */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n\\t]", " ").replaceAll("[\\x00-\\x1F]", "");
    }
}
