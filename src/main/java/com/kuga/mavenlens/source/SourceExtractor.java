package com.kuga.mavenlens.source;

import com.kuga.mavenlens.resolver.ArtifactLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 从 sources.jar 中提取 .java 源码
 *
 * @author guorunze
 */
public class SourceExtractor {

    private static final Logger log = LoggerFactory.getLogger(SourceExtractor.class);

    private final ArtifactLocator artifactLocator;

    public SourceExtractor(ArtifactLocator artifactLocator) {
        this.artifactLocator = artifactLocator;
    }

    /**
     * 从 sources.jar 提取指定类的源码
     *
     * @return 源码内容，找不到返回 null
     */
    public String extractSource(String groupId, String artifactId, String version, String fqn) {
        File sourcesJar = artifactLocator.locateSourcesJar(groupId, artifactId, version);
        if (!sourcesJar.exists()) {
            return null;
        }

        String entryPath = fqn.replace('.', '/') + ".java";
        try (JarFile jar = new JarFile(sourcesJar)) {
            JarEntry entry = jar.getJarEntry(entryPath);
            if (entry == null) {
                return null;
            }
            try (InputStream is = jar.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        } catch (IOException e) {
            log.warn("Failed to extract source from {}: {}", sourcesJar, e.getMessage());
            return null;
        }
    }
}
