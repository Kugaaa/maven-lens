package com.kuga.mavenlens.tool;

import com.kuga.mavenlens.index.ClassIndex;
import com.kuga.mavenlens.resolver.DependencyResolver;
import com.kuga.mavenlens.resolver.ResolvedArtifact;
import com.kuga.mavenlens.source.Decompiler;
import com.kuga.mavenlens.source.SourceExtractor;
import com.kuga.mavenlens.source.SourceSummaryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目上下文管理：缓存依赖解析结果和类名索引，以 pom_path 为 key。
 * 当 pom.xml 的 lastModified 变化时自动重建。
 *
 * @author guorunze
 */
public class ProjectContext {

    private static final Logger log = LoggerFactory.getLogger(ProjectContext.class);

    private final DependencyResolver resolver;
    private final SourceExtractor sourceExtractor;
    private final Decompiler decompiler;
    private final SourceSummaryParser summaryParser;

    private final Map<String, CachedProject> cache = new ConcurrentHashMap<>();

    public ProjectContext() {
        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        this.resolver = new DependencyResolver(localRepo);
        this.sourceExtractor = new SourceExtractor(resolver.getArtifactLocator());
        this.decompiler = new Decompiler();
        this.summaryParser = new SourceSummaryParser();
    }

    public CachedProject getOrBuild(String pomPath) throws Exception {
        File pomFile = new File(pomPath);
        if (!pomFile.exists()) {
            throw new IllegalArgumentException("pom.xml not found: " + pomPath);
        }

        String canonicalPath = pomFile.getCanonicalPath();
        long lastModified = pomFile.lastModified();

        CachedProject existing = cache.get(canonicalPath);
        if (existing != null && existing.getLastModified() == lastModified) {
            return existing;
        }

        log.info("Building project context for: {}", canonicalPath);
        List<ResolvedArtifact> artifacts = resolver.resolve(pomPath);
        ClassIndex index = new ClassIndex();
        index.buildIndex(artifacts);

        CachedProject project = new CachedProject(artifacts, index, lastModified);
        cache.put(canonicalPath, project);
        return project;
    }

    public SourceExtractor getSourceExtractor() {
        return sourceExtractor;
    }

    public Decompiler getDecompiler() {
        return decompiler;
    }

    public SourceSummaryParser getSummaryParser() {
        return summaryParser;
    }

    public DependencyResolver getResolver() {
        return resolver;
    }

    public static class CachedProject {
        private final List<ResolvedArtifact> artifacts;
        private final ClassIndex index;
        private final long lastModified;

        CachedProject(List<ResolvedArtifact> artifacts, ClassIndex index, long lastModified) {
            this.artifacts = artifacts;
            this.index = index;
            this.lastModified = lastModified;
        }

        public List<ResolvedArtifact> getArtifacts() {
            return artifacts;
        }

        public ClassIndex getIndex() {
            return index;
        }

        public long getLastModified() {
            return lastModified;
        }
    }
}
