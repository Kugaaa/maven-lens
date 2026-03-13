package com.kuga.mavenlens.source;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CFR 反编译封装，从 jar 中提取 .class 并反编译为 Java 源码
 *
 * @author guorunze
 */
public class Decompiler {

    private static final Logger log = LoggerFactory.getLogger(Decompiler.class);

    // 匹配类声明关键字：去掉注释和字符串后，找第一个 class/interface/enum/record/@interface
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "\\b(?:@interface|interface|enum|record|class)\\b");

    /**
     * 反编译指定 jar 中的指定类
     *
     * @return 反编译后的源码，失败返回 null
     */
    public String decompile(File jarFile, String fqn) {
        String classEntryPath = fqn.replace('.', '/') + ".class";

        Path tempFile = null;
        try {
            // 从 jar 中提取 .class 到临时文件
            tempFile = extractClassToTemp(jarFile, classEntryPath);
            if (tempFile == null) {
                return null;
            }

            return decompileClassFile(tempFile.toString(), jarFile.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to decompile {} from {}: {}", fqn, jarFile, e.getMessage());
            return null;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Path extractClassToTemp(File jarFile, String classEntryPath) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(classEntryPath);
            if (entry == null) {
                return null;
            }
            Path tempFile = Files.createTempFile("maven-lens-", ".class");
            try (InputStream is = jar.getInputStream(entry);
                 OutputStream os = Files.newOutputStream(tempFile)) {
                is.transferTo(os);
            }
            return tempFile;
        }
    }

    private String decompileClassFile(String classFilePath, String jarPath) {
        StringBuilder result = new StringBuilder();

        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                return List.of(SinkClass.STRING);
            }

            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                if (sinkType == SinkType.JAVA) {
                    return content -> result.append(content);
                }
                return content -> {};
            }
        };

        Map<String, String> options = new HashMap<>();
        options.put("showversion", "false");
        options.put("silent", "true");
        options.put("extraclasspath", jarPath);

        CfrDriver driver = new CfrDriver.Builder()
                .withOptions(options)
                .withOutputSink(sinkFactory)
                .build();
        driver.analyse(Collections.singletonList(classFilePath));

        String source = result.toString().trim();
        return source.isEmpty() ? null : source;
    }

    /**
     * 反编译 target/classes 目录中的指定类
     */
    public String decompileFromDirectory(File classesDir, String fqn) {
        String classFilePath = fqn.replace('.', File.separatorChar) + ".class";
        File classFile = new File(classesDir, classFilePath);
        if (!classFile.exists()) {
            return null;
        }
        try {
            return decompileClassFile(classFile.getAbsolutePath(), classesDir.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to decompile {} from directory {}: {}", fqn, classesDir, e.getMessage());
            return null;
        }
    }

    /**
     * 通过 CFR 反编译后解析源码关键字判断类的类型
     */
    public String detectType(File jarFile, String fqn) {
        String source = decompile(jarFile, fqn);
        if (source == null) {
            return "class";
        }
        return parseTypeFromSource(source);
    }

    public String detectTypeFromDirectory(File classesDir, String fqn) {
        String source = decompileFromDirectory(classesDir, fqn);
        if (source == null) {
            return "class";
        }
        return parseTypeFromSource(source);
    }

    private String parseTypeFromSource(String source) {
        // 去掉注释和字符串，避免干扰
        String cleaned = removeCommentsAndStrings(source);
        Matcher matcher = TYPE_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            String keyword = matcher.group();
            return switch (keyword) {
                case "@interface" -> "annotation";
                case "interface" -> "interface";
                case "enum" -> "enum";
                case "record" -> "record";
                default -> "class";
            };
        }
        return "class";
    }

    private String removeCommentsAndStrings(String source) {
        StringBuilder sb = new StringBuilder();
        char[] chars = source.toCharArray();
        int i = 0;
        while (i < chars.length) {
            if (i + 1 < chars.length && chars[i] == '/' && chars[i + 1] == '/') {
                // 单行注释，跳到行尾
                while (i < chars.length && chars[i] != '\n') {
                    i++;
                }
            } else if (i + 1 < chars.length && chars[i] == '/' && chars[i + 1] == '*') {
                // 块注释，跳到 */
                i += 2;
                while (i + 1 < chars.length && !(chars[i] == '*' && chars[i + 1] == '/')) {
                    i++;
                }
                i += 2;
            } else if (chars[i] == '"') {
                // 字符串，跳到结束引号
                i++;
                while (i < chars.length && chars[i] != '"') {
                    if (chars[i] == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
            } else {
                sb.append(chars[i]);
                i++;
            }
        }
        return sb.toString();
    }
}
