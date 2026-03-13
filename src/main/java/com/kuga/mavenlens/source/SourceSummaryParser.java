package com.kuga.mavenlens.source;

/**
 * 源码精简：去掉方法体，只保留类声明、字段、方法签名和 Javadoc。
 * 使用字符级解析，不依赖外部 AST 解析库。
 *
 * @author guorunze
 */
public class SourceSummaryParser {

    /**
     * 将完整源码精简为签名概要
     */
    public String summarize(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }

        StringBuilder result = new StringBuilder();
        char[] chars = source.toCharArray();
        int len = chars.length;
        int i = 0;

        while (i < len) {
            // 跳过空白
            if (Character.isWhitespace(chars[i])) {
                result.append(chars[i]);
                i++;
                continue;
            }

            // 处理行注释
            if (i + 1 < len && chars[i] == '/' && chars[i + 1] == '/') {
                int end = indexOf(chars, '\n', i);
                if (end < 0) end = len;
                result.append(source, i, end);
                i = end;
                continue;
            }

            // 处理块注释 / Javadoc
            if (i + 1 < len && chars[i] == '/' && chars[i + 1] == '*') {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) end = len - 2;
                result.append(source, i, end + 2);
                i = end + 2;
                continue;
            }

            // 找到方法体的开始：检测是否是方法体的 { 而非类/接口/枚举/注解的 {
            if (chars[i] == '{') {
                if (isMethodBody(result.toString())) {
                    // 跳过方法体内容
                    int closeIndex = findMatchingClose(chars, i);
                    result.append("{ ... }");
                    i = closeIndex + 1;
                    // 跳过紧跟的换行
                    if (i < len && chars[i] == '\n') {
                        result.append('\n');
                        i++;
                    }
                } else {
                    result.append(chars[i]);
                    i++;
                }
                continue;
            }

            result.append(chars[i]);
            i++;
        }

        return result.toString();
    }

    /**
     * 判断当前 { 是否是方法体（而非类/接口/枚举/注解声明或初始化块）
     */
    private boolean isMethodBody(String preceding) {
        String trimmed = preceding.stripTrailing();
        if (trimmed.isEmpty()) {
            return false;
        }

        // 找到最后一行有意义的非空内容
        int lastNewline = trimmed.lastIndexOf('\n');
        String lastSegment = trimmed.substring(lastNewline + 1).trim();

        // 如果最后一段以 ) 结尾，或者以 throws XxxException 结尾，是方法体
        if (lastSegment.endsWith(")")) {
            return true;
        }

        // throws 子句
        if (lastSegment.matches(".*\\bthrows\\b.*")) {
            return true;
        }

        // default 方法体（annotation 中）
        if (lastSegment.matches(".*\\bdefault\\b.*")) {
            return false;
        }

        // 从 preceding 尾部回溯检查是否有 ) 出现在 { 之前
        String beforeBrace = trimmed;
        // 去掉末尾的注解等，回溯找 )
        int parenPos = beforeBrace.lastIndexOf(')');
        if (parenPos >= 0) {
            // 检查 ) 和 { 之间是否只有 throws 子句或空白
            String between = beforeBrace.substring(parenPos + 1).trim();
            if (between.isEmpty() || between.matches("throws\\s+[\\w.,\\s]+")) {
                return true;
            }
        }

        // 类/接口/枚举/注解声明
        if (lastSegment.matches(".*\\b(class|interface|enum|@interface)\\b.*")) {
            return false;
        }

        // static/instance 初始化块
        if (lastSegment.equals("static") || lastSegment.isEmpty()) {
            return false;
        }

        return false;
    }

    private int findMatchingClose(char[] chars, int openIndex) {
        int depth = 1;
        int i = openIndex + 1;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        while (i < chars.length && depth > 0) {
            if (inLineComment) {
                if (chars[i] == '\n') inLineComment = false;
                i++;
                continue;
            }
            if (inBlockComment) {
                if (chars[i] == '*' && i + 1 < chars.length && chars[i + 1] == '/') {
                    inBlockComment = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (inString) {
                if (chars[i] == '\\') {
                    i += 2;
                } else if (chars[i] == '"') {
                    inString = false;
                    i++;
                } else {
                    i++;
                }
                continue;
            }
            if (inChar) {
                if (chars[i] == '\\') {
                    i += 2;
                } else if (chars[i] == '\'') {
                    inChar = false;
                    i++;
                } else {
                    i++;
                }
                continue;
            }

            if (chars[i] == '/' && i + 1 < chars.length && chars[i + 1] == '/') {
                inLineComment = true;
                i += 2;
            } else if (chars[i] == '/' && i + 1 < chars.length && chars[i + 1] == '*') {
                inBlockComment = true;
                i += 2;
            } else if (chars[i] == '"') {
                inString = true;
                i++;
            } else if (chars[i] == '\'') {
                inChar = true;
                i++;
            } else if (chars[i] == '{') {
                depth++;
                i++;
            } else if (chars[i] == '}') {
                depth--;
                i++;
            } else {
                i++;
            }
        }
        return i - 1;
    }

    private int indexOf(char[] chars, char target, int from) {
        for (int i = from; i < chars.length; i++) {
            if (chars[i] == target) return i;
        }
        return -1;
    }
}
