package com.kuga.mavenlens.index;

/**
 * 索引中的类信息
 *
 * @author guorunze
 */
public class ClassInfo {

    private final String fqn;
    private final String simpleName;
    private final String artifact;
    private final String groupId;
    private final String artifactId;
    private final String version;

    public ClassInfo(String fqn, String simpleName, String groupId, String artifactId, String version) {
        this.fqn = fqn;
        this.simpleName = simpleName;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.artifact = groupId + ":" + artifactId + ":" + version;
    }

    public String getFqn() {
        return fqn;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public String getArtifact() {
        return artifact;
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
}
