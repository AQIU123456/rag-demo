package com.example.aichat.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量 BM25 打分器（纯 Java，零依赖）。
 *
 * <p>中文按单字 + 相邻双字切分，英文按词切分，不需要分词器或 ES。
 * 用于 S3 的关键词通道，与向量通道做 RRF 融合；切换到 ES / OpenSearch 时
 * 只需把 {@link #score} 的调用替换为搜索引擎结果，融合层不变。</p>
 */
@Component
public class Bm25Scorer {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    public static class Doc {
        private final String id;
        private final String text;

        public Doc(String id, String text) {
            this.id = id;
            this.text = text == null ? "" : text;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }
    }

    /**
     * @return docId -> BM25 分数（仅返回分数 &gt; 0 的文档，已按分数降序）
     */
    public Map<String, Double> score(String query, Collection<Doc> docs) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (query == null || query.isBlank() || docs == null || docs.isEmpty()) {
            return scores;
        }
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return scores;
        }

        Map<String, List<Integer>> termFreq = new HashMap<>();
        Map<String, Integer> docFreq = new HashMap<>();
        List<Integer> lengths = new ArrayList<>(docs.size());
        int totalLength = 0;

        for (Doc doc : docs) {
            List<String> terms = tokenize(doc.getText());
            lengths.add(terms.size());
            totalLength += terms.size();
            Map<String, Integer> tf = new HashMap<>();
            for (String t : terms) {
                tf.merge(t, 1, Integer::sum);
            }
            for (String t : tf.keySet()) {
                docFreq.merge(t, 1, Integer::sum);
            }
            termFreq.put(doc.getId(), new ArrayList<>());
            // 直接保存 tf 供第二阶段使用，避免二次分词
            for (String qt : queryTerms) {
                termFreq.get(doc.getId()).add(tf.getOrDefault(qt, 0));
            }
        }

        int n = docs.size();
        double avgdl = totalLength == 0 ? 1.0 : (double) totalLength / n;
        double[] idf = new double[queryTerms.size()];
        for (int i = 0; i < queryTerms.size(); i++) {
            int df = docFreq.getOrDefault(queryTerms.get(i), 0);
            idf[i] = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
        }

        int row = 0;
        for (Doc doc : docs) {
            List<Integer> freqs = termFreq.get(doc.getId());
            double dl = lengths.get(row++);
            double value = 0d;
            for (int i = 0; i < queryTerms.size(); i++) {
                int f = freqs.get(i);
                if (f == 0) {
                    continue;
                }
                value += idf[i] * (f * (K1 + 1))
                        / (f + K1 * (1 - B + B * dl / avgdl));
            }
            if (value > 0) {
                scores.put(doc.getId(), value);
            }
        }
        // 按分数降序，便于取 topN 参与 RRF
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<String, Double> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : entries) {
            sorted.put(e.getKey(), e.getValue());
        }
        return sorted;
    }

    /**
     * 中英混排分词：ASCII 按 [a-z0-9]+ 取词；连续 CJK 片段取单字与相邻双字。
     */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean asciiWord = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (asciiWord) {
                flushCjk(cjk, tokens);
                int j = i;
                while (j < lower.length()) {
                    char d = lower.charAt(j);
                    if ((d >= 'a' && d <= 'z') || (d >= '0' && d <= '9')) {
                        j++;
                    } else {
                        break;
                    }
                }
                tokens.add(lower.substring(i, j));
                i = j - 1;
            } else if (c >= 0x4e00 && c <= 0x9fff) {
                cjk.append(c);
            } else {
                flushCjk(cjk, tokens);
            }
        }
        flushCjk(cjk, tokens);
        return tokens;
    }

    private void flushCjk(StringBuilder cjk, List<String> tokens) {
        if (cjk.length() == 0) {
            return;
        }
        String s = cjk.toString();
        for (int i = 0; i < s.length(); i++) {
            tokens.add(String.valueOf(s.charAt(i)));
            if (i + 1 < s.length()) {
                tokens.add(s.substring(i, i + 2));
            }
        }
        cjk.setLength(0);
    }
}
