package com.kuga.mavenlens.resolver;

import java.io.File;
import java.nio.file.Path;

/**
 * 本地 Maven 仓库中 artifact 的 jar 文件定位
 *
 * @author guorunze
 */
public class ArtifactLocator {

    private final Path localRepoPath;

    public ArtifactLocator(Path localRepoPath) {
        this.localRepoPath = localRepoPath;
    }

    public File locateJar(String groupId, String artifactId, String version) {
        return buildArtifactPath(groupId, artifactId, version,
                artifactId + "-" + version + ".jar").toFile();
    }

    public File locatePom(String groupId, String artifactId, String version) {
        return buildArtifactPath(groupId, artifactId, version,
                artifactId + "-" + version + ".pom").toFile();
    }

    public File locateSourcesJar(String groupId, String artifactId, String version) {
        return buildArtifactPath(groupId, artifactId, version,
                artifactId + "-" + version + "-sources.jar").toFile();
    }

    public boolean hasSourcesJar(String groupId, String artifactId, String version) {
        return locateSourcesJar(groupId, artifactId, version).exists();
    }

    private Path buildArtifactPath(String groupId, String artifactId, String version, String fileName) {
        String groupPath = groupId.replace('.', File.separatorChar);
        return localRepoPath.resolve(groupPath).resolve(artifactId).resolve(version).resolve(fileName);
    }
}
