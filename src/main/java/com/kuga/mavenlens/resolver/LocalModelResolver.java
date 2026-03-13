package com.kuga.mavenlens.resolver;

import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;

import java.io.File;
import java.nio.file.Path;

/**
 * 从本地 Maven 仓库解析 parent pom 的 ModelResolver
 *
 * @author guorunze
 */
public class LocalModelResolver implements ModelResolver {

    private final Path localRepoPath;

    public LocalModelResolver(Path localRepoPath) {
        this.localRepoPath = localRepoPath;
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        File pom = locatePom(groupId, artifactId, version);
        if (!pom.exists()) {
            throw new UnresolvableModelException(
                    "POM not found in local repo: " + groupId + ":" + artifactId + ":" + version,
                    groupId, artifactId, version);
        }
        return new FileModelSource(pom);
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public ModelSource resolveModel(org.apache.maven.model.Dependency dependency)
            throws UnresolvableModelException {
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
    }

    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
        // 纯本地解析，忽略远程仓库
    }

    @Override
    public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
        // 纯本地解析，忽略远程仓库
    }

    @Override
    public ModelResolver newCopy() {
        return new LocalModelResolver(localRepoPath);
    }

    private File locatePom(String groupId, String artifactId, String version) {
        String groupPath = groupId.replace('.', File.separatorChar);
        return localRepoPath.resolve(groupPath).resolve(artifactId).resolve(version)
                .resolve(artifactId + "-" + version + ".pom").toFile();
    }
}
