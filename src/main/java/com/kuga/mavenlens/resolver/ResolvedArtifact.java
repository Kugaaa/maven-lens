package com.kuga.mavenlens.resolver;

import java.io.File;

/**
 * 解析后的 artifact 信息，支持 jar 文件和 classes 目录两种形式
 *
 * @author guorunze
 */
public class ResolvedArtifact {

    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String scope;
    // jar 文件或 classes 目录
    private final File jarFile;
    private final boolean directory;

    public ResolvedArtifact(String groupId, String artifactId, String version,
                            String scope, File jarFile) {
        this(groupId, artifactId, version, scope, jarFile, false);
    }

    public ResolvedArtifact(String groupId, String artifactId, String version,
                            String scope, File jarFile, boolean directory) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.scope = scope;
        this.jarFile = jarFile;
        this.directory = directory;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getScope() {
        return scope;
    }

    public File getJarFile() {
        return jarFile;
    }

    public boolean isDirectory() {
        return directory;
    }

    public String getCoordinate() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
