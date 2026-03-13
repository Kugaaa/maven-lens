package com.kuga.mavenlens.index;

import com.kuga.mavenlens.resolver.ResolvedArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * jar 包类名索引，构建 简单类名 → List<ClassInfo> 的映射
 *
 * @author guorunze
 */
public class ClassIndex {

    private static final Logger log = LoggerFactory.getLogger(ClassIndex.class);

    // 简单类名 → 匹配的类信息列表
    private final Map<String, List<ClassInfo>> simpleNameIndex = new HashMap<>();
    // FQN → 类信息
    private final Map<String, ClassInfo> fqnIndex = new HashMap<>();

    public void buildIndex(List<ResolvedArtifact> artifacts) {
        simpleNameIndex.clear();
        fqnIndex.clear();

        for (ResolvedArtifact artifact : artifacts) {
            File file = artifact.getJarFile();
            if (file == null || !file.exists()) {
                continue;
            }
            if (artifact.isDirectory()) {
                indexDirectory(artifact);
            } else {
                indexJar(artifact);
            }
        }
        log.info("Index built: {} unique simple names, {} total FQNs", simpleNameIndex.size(), fqnIndex.size());
    }

    private void indexJar(ResolvedArtifact artifact) {
        try (JarFile jar = new JarFile(artifact.getJarFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.contains("module-info") || name.contains("package-info")) {
                    continue;
                }

                String fqn = name.substring(0, name.length() - 6).replace('/', '.');
                String simpleName = extractSimpleName(fqn);

                ClassInfo info = new ClassInfo(fqn, simpleName,
                        artifact.getGroupId(), artifact.getArtifactId(),
                        artifact.getVersion());

                fqnIndex.put(fqn, info);
                simpleNameIndex.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(info);
            }
        } catch (IOException e) {
            log.warn("Failed to index jar {}: {}", artifact.getJarFile(), e.getMessage());
        }
    }

    /**
     * 从编译输出目录（target/classes）索引 .class 文件
     */
    private void indexDirectory(ResolvedArtifact artifact) {
        Path basePath = artifact.getJarFile().toPath();
        try (Stream<Path> stream = Files.walk(basePath)) {
            stream.filter(p -> {
                        String fileName = p.getFileName().toString();
                        return fileName.endsWith(".class")
                                && !fileName.contains("module-info")
                                && !fileName.contains("package-info");
                    })
                    .forEach(classFile -> {
                        String relativePath = basePath.relativize(classFile).toString();
                        String fqn = relativePath
                                .substring(0, relativePath.length() - 6)
                                .replace(File.separatorChar, '.');
                        String simpleName = extractSimpleName(fqn);

                        ClassInfo info = new ClassInfo(fqn, simpleName,
                                artifact.getGroupId(), artifact.getArtifactId(),
                                artifact.getVersion());

                        fqnIndex.put(fqn, info);
                        simpleNameIndex.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(info);
                    });
        } catch (IOException e) {
            log.warn("Failed to index classes directory {}: {}", basePath, e.getMessage());
        }
    }

    /**
     * 按简单类名精确匹配
     */
    public List<ClassInfo> findBySimpleName(String simpleName) {
        List<ClassInfo> result = simpleNameIndex.get(simpleName);
        return result != null ? result : Collections.emptyList();
    }

    /**
     * 模糊搜索：支持 FQN 精确匹配、简单类名精确匹配、前缀/包含匹配
     */
    public List<ClassInfo> search(String keyword) {
        // FQN 精确匹配（包含 '.' 且看起来像包名的情况）
        ClassInfo byFqn = fqnIndex.get(keyword);
        if (byFqn != null) {
            return List.of(byFqn);
        }

        // 简单类名精确匹配
        List<ClassInfo> exact = findBySimpleName(keyword);
        if (!exact.isEmpty()) {
            return exact;
        }

        // 模糊匹配
        String lowerKeyword = keyword.toLowerCase();
        List<ClassInfo> matches = new ArrayList<>();
        for (Map.Entry<String, List<ClassInfo>> entry : simpleNameIndex.entrySet()) {
            if (entry.getKey().toLowerCase().contains(lowerKeyword)) {
                matches.addAll(entry.getValue());
            }
        }
        return matches;
    }

    /**
     * 按 FQN 精确查找
     */
    public ClassInfo findByFqn(String fqn) {
        return fqnIndex.get(fqn);
    }

    private String extractSimpleName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }
}
