package com.example.aichat.domain.chunk;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构感知切分器：标题层级 / 条款号优先切分，段内再交给 token 递归切分器。
 *
 * <p>额外做两件企业级语料保护：</p>
 * <ul>
 *   <li>维护标题路径写入 {@code section_path} 元数据，供上下文前缀与引用溯源使用</li>
 *   <li>Markdown 表格整块保留；超长表格按行切分且每片重复表头，避免"数据行失去列名"</li>
 * </ul>
 */
public class StructureAwareSplitter implements DocumentSplitter {

    /**
     * 标题必须「独占一行且足够短」。
     * 用 [^\n]{0,200} 而非 .*：否则无换行的整篇中文长段落会被 ^...$ 整体误判为一个
     * 巨型标题，section_path 会塞进全文并撑爆上下文前缀（实测 4810 字 -> 299 token/块）。
     */
    private static final int MAX_HEADING_LENGTH = 200;

    private static final Pattern HEADING = Pattern.compile(
            "(?m)^\\s{0,3}(#{1,6}\\s+\\S[^\\n]{0,200}"
                    + "|第[一二三四五六七八九十百零〇\\d]{1,12}[章节条]\\s*\\S[^\\n]{0,200}"
                    + "|\\d+(?:\\.\\d+){0,3}\\s+\\S[^\\n]{0,200})$");

    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");

    private final DocumentSplitter delegate;
    private final int hardMaxChars;

    public StructureAwareSplitter(DocumentSplitter delegate, int hardMaxChars) {
        this.delegate = delegate;
        this.hardMaxChars = hardMaxChars;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        if (text == null || text.isBlank()) {
            return delegate.split(document);
        }

        Matcher m = HEADING.matcher(text);
        List<int[]> ranges = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        int last = 0;
        String pending = "";
        while (m.find()) {
            if (m.start() > last) {
                ranges.add(new int[]{last, m.start()});
                titles.add(pending);
            }
            String candidate = m.group(1).trim();
            // 二次防御：超长行不算标题，避免任何正则遗漏把正文写进 section_path
            pending = candidate.length() <= MAX_HEADING_LENGTH ? candidate : "";
            last = m.start();
        }
        ranges.add(new int[]{last, text.length()});
        titles.add(pending);

        List<TextSegment> result = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        for (int i = 0; i < ranges.size(); i++) {
            String title = titles.get(i);
            if (!title.isEmpty()) {
                int level = headingLevel(title);
                while (!path.isEmpty() && path.size() >= level) {
                    path.removeLast();
                }
                path.addLast(title);
            }
            String sectionPath = String.join(" > ", path);
            if (sectionPath.length() > 512) {
                sectionPath = sectionPath.substring(0, 512);
            }
            int[] r = ranges.get(i);
            String body = text.substring(r[0], r[1]).trim();
            if (body.isEmpty()) {
                continue;
            }
            for (String slice : sectionSlices(body)) {
                if (slice.startsWith("__TABLE__:")) {
                    Metadata md = new Metadata();
                    md.put("section_path", sectionPath);
                    md.put("table_block", "true");
                    result.add(TextSegment.from(slice.substring("__TABLE__:".length()), md));
                    continue;
                }
                for (TextSegment seg : delegate.split(Document.from(slice))) {
                    Metadata md = seg.metadata().copy();
                    md.put("section_path", sectionPath);
                    result.add(TextSegment.from(seg.text(), md));
                }
            }
        }
        return result.isEmpty() ? delegate.split(document) : result;
    }

    /**
     * 把一个章节切成"普通文本片"与"表格片"。表格片加前缀标记，避免被递归切分器拆散。
     */
    private List<String> sectionSlices(String body) {
        List<String> slices = new ArrayList<>();
        StringBuilder normal = new StringBuilder();
        List<String> table = new ArrayList<>();

        for (String line : body.split("\n", -1)) {
            if (TABLE_ROW.matcher(line).matches()) {
                table.add(line);
                continue;
            }
            if (!table.isEmpty()) {
                flushTable(table, slices);
                table.clear();
            }
            normal.append(line).append('\n');
        }
        if (!table.isEmpty()) {
            flushTable(table, slices);
        }
        String text = normal.toString().trim();
        if (!text.isEmpty()) {
            slices.addAll(hardSlice(text));
        }
        return slices;
    }

    /**
     * 整表 <= hardMaxChars 时整块保留；否则按行切，每片重复表头与分隔行。
     */
    private void flushTable(List<String> table, List<String> out) {
        String whole = String.join("\n", table);
        if (whole.length() <= hardMaxChars) {
            out.add("__TABLE__:" + whole);
            return;
        }
        List<String> header = table.size() >= 2 ? table.subList(0, 2) : List.of(table.get(0));
        String headerText = String.join("\n", header);
        StringBuilder buf = new StringBuilder(headerText);
        for (int i = header.size(); i < table.size(); i++) {
            String line = table.get(i);
            if (buf.length() + line.length() + 1 > hardMaxChars && buf.length() > headerText.length()) {
                out.add("__TABLE__:" + buf);
                buf = new StringBuilder(headerText);
            }
            buf.append('\n').append(line);
        }
        if (buf.length() > 0) {
            out.add("__TABLE__:" + buf);
        }
    }

    private List<String> hardSlice(String text) {
        if (text.length() <= hardMaxChars) {
            return List.of(text);
        }
        List<String> slices = new ArrayList<>();
        for (int i = 0; i < text.length(); i += hardMaxChars) {
            slices.add(text.substring(i, Math.min(i + hardMaxChars, text.length())));
        }
        return slices;
    }

    private int headingLevel(String t) {
        if (t.startsWith("#")) {
            int n = 0;
            while (n < t.length() && t.charAt(n) == '#') {
                n++;
            }
            return Math.min(n, 6);
        }
        if (t.startsWith("第")) {
            return 2;
        }
        int dots = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '.') {
                dots++;
            }
        }
        return Math.min(dots + 1, 6);
    }
}
