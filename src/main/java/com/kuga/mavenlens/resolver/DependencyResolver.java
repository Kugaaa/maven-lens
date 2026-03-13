package com.kuga.mavenlens.resolver;

import org.apache.maven.model.Model;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.*;

/**
 * Maven 依赖解析：使用 maven-model-builder 构建 effective model，
 * maven-resolver 收集依赖树，从本地仓库定位 jar 文件
 *
 * @author guorunze
 */
public class DependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(DependencyResolver.class);

    private final RepositorySystem repoSystem;
    private final RepositorySystemSession session;
    private final ArtifactLocator artifactLocator;
    private final List<RemoteRepository> remoteRepositories;
    private final Path localRepoPath;

    public DependencyResolver(Path localRepoPath) {
        this.localRepoPath = localRepoPath;
        this.artifactLocator = new ArtifactLocator(localRepoPath);
        this.repoSystem = new RepositorySystemSupplier().get();
        this.session = newSession(localRepoPath);
        this.remoteRepositories = List.of(
                new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build()
        );
    }

    public List<ResolvedArtifact> resolve(String pomPath) throws Exception {
        File pomFile = new File(pomPath);
        if (!pomFile.exists()) {
            throw new IllegalArgumentException("pom.xml not found: " + pomPath);
        }

        // 尝试向上查找父 pom，如果当前 pom 属于多模块项目的子模块，则从父 pom 入口解析
        File rootPom = findMultiModuleRoot(pomFile);
        if (rootPom != null) {
            log.info("Detected multi-module project, resolving from root: {}", rootPom.getAbsolutePath());
            return resolveFromPom(rootPom);
        }

        return resolveFromPom(pomFile);
    }

    private List<ResolvedArtifact> resolveFromPom(File pomFile) throws Exception {
        Model rawModel = readRawModel(pomFile);
        if (rawModel.getModules() != null && !rawModel.getModules().isEmpty()) {
            return resolveMultiModule(pomFile.getParentFile(), rawModel);
        }

        Model model = buildEffectiveModel(pomFile);
        return resolveSinglePom(model, pomFile);
    }

    /**
     * 向上查找多模块项目的根 pom。
     * 检查当前 pom 的父目录是否存在 pom.xml 且其 modules 中包含当前模块。
     * 使用 readRawModel 直接读 XML，不依赖 parent 继承解析。
     */
    private File findMultiModuleRoot(File pomFile) {
        File moduleDir = pomFile.getParentFile();
        if (moduleDir == null) {
            return null;
        }
        File parentDir = moduleDir.getParentFile();
        if (parentDir == null) {
            return null;
        }
        File parentPom = new File(parentDir, "pom.xml");
        if (!parentPom.exists()) {
            return null;
        }

        try {
            Model parentModel = readRawModel(parentPom);
            if (parentModel.getModules() == null || parentModel.getModules().isEmpty()) {
                return null;
            }
            String moduleName = moduleDir.getName();
            if (parentModel.getModules().contains(moduleName)) {
                // 递归向上，支持嵌套多模块
                File higherRoot = findMultiModuleRoot(parentPom);
                return higherRoot != null ? higherRoot : parentPom;
            }
        } catch (Exception e) {
            log.debug("Failed to check parent pom {}: {}", parentPom, e.getMessage());
        }
        return null;
    }

    private List<ResolvedArtifact> resolveMultiModule(File baseDir, Model rawParentModel) throws Exception {
        List<String> modules = rawParentModel.getModules();

        List<ResolvedArtifact> allArtifacts = new ArrayList<>();

        // 收集内部模块自身作为 artifact（用 readRawModel 读取，不依赖 parent 继承解析）
        allArtifacts.addAll(collectInternalModules(baseDir, modules));

        // 解析 parent pom 自身的依赖（需要 effective model 才能正确解析版本变量）
        File parentPomFile = new File(baseDir, "pom.xml");
        try {
            Model effectiveParent = buildEffectiveModel(parentPomFile);
            allArtifacts.addAll(resolveSinglePom(effectiveParent, parentPomFile));
        } catch (Exception e) {
            log.warn("Failed to resolve parent pom dependencies: {}", e.getMessage());
        }

        // 解析每个子模块的外部依赖
        for (String module : modules) {
            File modulePom = new File(baseDir, module + "/pom.xml");
            if (!modulePom.exists()) {
                log.warn("Module pom not found: {}", modulePom.getAbsolutePath());
                continue;
            }
            try {
                Model rawModel = readRawModel(modulePom);
                // 嵌套多模块递归
                if (rawModel.getModules() != null && !rawModel.getModules().isEmpty()) {
                    // 嵌套模块需要 effective model 来解析外部依赖
                    try {
                        Model effectiveModel = buildEffectiveModel(modulePom);
                        allArtifacts.addAll(resolveMultiModule(modulePom.getParentFile(), effectiveModel));
                    } catch (Exception e) {
                        log.warn("Failed to build effective model for nested module {}, using raw model for sub-modules", module);
                        allArtifacts.addAll(collectInternalModules(modulePom.getParentFile(), rawModel.getModules()));
                    }
                } else {
                    try {
                        Model effectiveModel = buildEffectiveModel(modulePom);
                        allArtifacts.addAll(resolveSinglePom(effectiveModel, modulePom));
                    } catch (Exception e) {
                        log.warn("Failed to resolve module {}: {}", module, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read module pom {}: {}", module, e.getMessage());
            }
        }

        return allArtifacts.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ResolvedArtifact::getCoordinate, a -> a, (a, b) -> a))
                .values().stream().toList();
    }

    /**
     * 收集多模块项目中每个子模块自身的 artifact。
     * 使用 readRawModel 直接读 pom.xml，不依赖 buildEffectiveModel，
     * 避免因 parent pom 未 install 到本地仓库导致解析失败。
     */
    private List<ResolvedArtifact> collectInternalModules(File baseDir, List<String> modules) {
        List<ResolvedArtifact> internalModules = new ArrayList<>();

        for (String module : modules) {
            File modulePom = new File(baseDir, module + "/pom.xml");
            if (!modulePom.exists()) {
                continue;
            }

            try {
                Model rawModel = readRawModel(modulePom);

                if ("pom".equals(rawModel.getPackaging())) {
                    // pom 类型模块（聚合模块）递归收集其子模块
                    if (rawModel.getModules() != null && !rawModel.getModules().isEmpty()) {
                        internalModules.addAll(collectInternalModules(modulePom.getParentFile(), rawModel.getModules()));
                    }
                    continue;
                }

                // 从 raw model 和 parent 中提取 GAV
                String groupId = rawModel.getGroupId();
                String version = rawModel.getVersion();
                if (groupId == null && rawModel.getParent() != null) {
                    groupId = rawModel.getParent().getGroupId();
                }
                if (version == null && rawModel.getParent() != null) {
                    version = rawModel.getParent().getVersion();
                }
                String artifactId = rawModel.getArtifactId();

                if (groupId == null || artifactId == null || version == null) {
                    log.warn("Incomplete GAV for internal module {}, skipping", module);
                    continue;
                }

                // 优先 target/classes
                File targetClasses = new File(baseDir, module + "/target/classes");
                if (targetClasses.isDirectory()) {
                    internalModules.add(new ResolvedArtifact(
                            groupId, artifactId, version, "compile", targetClasses, true));
                    log.info("Indexed internal module from target/classes: {}:{}:{}", groupId, artifactId, version);
                    continue;
                }

                // 其次本地仓库 jar
                File localJar = artifactLocator.locateJar(groupId, artifactId, version);
                if (localJar.exists()) {
                    internalModules.add(new ResolvedArtifact(
                            groupId, artifactId, version, "compile", localJar));
                    log.info("Indexed internal module from local repo: {}:{}:{}", groupId, artifactId, version);
                    continue;
                }

                log.warn("Internal module not compiled and not in local repo: {}:{}:{} (run 'mvn compile' first)",
                        groupId, artifactId, version);
            } catch (Exception e) {
                log.warn("Failed to read module pom {}: {}", module, e.getMessage());
            }
        }

        return internalModules;
    }

    private List<ResolvedArtifact> resolveSinglePom(Model model, File pomFile) {
        List<org.eclipse.aether.graph.Dependency> aetherDeps = new ArrayList<>();
        if (model.getDependencies() != null) {
            for (Dependency dep : model.getDependencies()) {
                String depVersion = dep.getVersion();
                if (depVersion == null || depVersion.startsWith("$")) {
                    log.warn("Skipping dependency with unresolved version: {}:{}", dep.getGroupId(), dep.getArtifactId());
                    continue;
                }
                String depScope = dep.getScope() != null ? dep.getScope() : JavaScopes.COMPILE;
                Artifact artifact = new DefaultArtifact(dep.getGroupId(), dep.getArtifactId(), "jar", depVersion);
                aetherDeps.add(new org.eclipse.aether.graph.Dependency(artifact, depScope));
            }
        }

        if (aetherDeps.isEmpty()) {
            return Collections.emptyList();
        }

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setDependencies(aetherDeps);
        collectRequest.setRepositories(remoteRepositories);

        List<ResolvedArtifact> result = new ArrayList<>();
        CollectResult collectResult;
        try {
            collectResult = repoSystem.collectDependencies(session, collectRequest);
        } catch (DependencyCollectionException e) {
            log.warn("Partial dependency collection failure for {}: {}", pomFile.getAbsolutePath(), e.getMessage());
            collectResult = e.getResult();
        }

        if (collectResult != null && collectResult.getRoot() != null) {
            // 不做 scope 过滤，索引所有依赖（maven 依赖树去重可能把 compile scope 的 artifact 合并到 test 路径下）
            collectArtifacts(collectResult.getRoot(), result, new HashSet<>());
        }

        return result;
    }

    private void collectArtifacts(DependencyNode node, List<ResolvedArtifact> result, Set<String> visited) {
        for (DependencyNode child : node.getChildren()) {
            if (child.getDependency() == null) {
                continue;
            }

            Artifact artifact = child.getArtifact();
            String coordinate = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
            if (!visited.add(coordinate)) {
                continue;
            }

            log.debug("Dependency tree node: {}:{}:{} (children={})",
                    artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                    child.getChildren().size());
            File jarFile = artifactLocator.locateJar(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
            if (jarFile.exists()) {
                String scope = child.getDependency().getScope();
                result.add(new ResolvedArtifact(
                        artifact.getGroupId(), artifact.getArtifactId(),
                        artifact.getVersion(), scope, jarFile));
            } else {
                log.warn("Jar not found in local repo: {}:{}:{}", artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
            }

            if (child.getChildren().isEmpty()) {
                // maven-resolver 未能展开此节点的传递依赖，尝试从本地仓库 pom 补充
                supplementFromLocalPom(artifact, result, visited);
            } else {
                collectArtifacts(child, result, visited);
            }
        }
    }

    /**
     * 从本地仓库的 pom 文件补充传递依赖。
     * 当 maven-resolver 未能展开某个依赖的子依赖时（children=0），
     * 尝试读取该 artifact 在本地仓库中的 pom，解析其声明的依赖并递归补充。
     */
    private void supplementFromLocalPom(Artifact artifact, List<ResolvedArtifact> result, Set<String> visited) {
        File pomFile = artifactLocator.locatePom(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
        if (!pomFile.exists()) {
            return;
        }

        log.info("Supplementing transitive dependencies from local pom: {}:{}:{}",
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
        try {
            Model model = buildEffectiveModel(pomFile);
            if (model.getDependencies() == null) {
                return;
            }
            for (Dependency dep : model.getDependencies()) {
                String scope = dep.getScope();
                if ("test".equals(scope) || "provided".equals(scope)) {
                    continue;
                }
                String version = dep.getVersion();
                if (version == null || version.startsWith("$")) {
                    continue;
                }

                String depCoordinate = dep.getGroupId() + ":" + dep.getArtifactId() + ":" + version;
                if (!visited.add(depCoordinate)) {
                    continue;
                }

                File jarFile = artifactLocator.locateJar(dep.getGroupId(), dep.getArtifactId(), version);
                if (jarFile.exists()) {
                    result.add(new ResolvedArtifact(
                            dep.getGroupId(), dep.getArtifactId(), version,
                            scope != null ? scope : "compile", jarFile));
                    // 递归补充此依赖的传递依赖
                    Artifact depArtifact = new DefaultArtifact(dep.getGroupId(), dep.getArtifactId(), "jar", version);
                    supplementFromLocalPom(depArtifact, result, visited);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to supplement dependencies from local pom for {}:{}:{}: {}",
                    artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), e.getMessage());
        }
    }

    /**
     * 直接读取 pom.xml 的原始 model，不做 parent 继承解析。
     * 用于只需要 modules、packaging、GAV 等本文件内信息的场景。
     */
    private Model readRawModel(File pomFile) throws Exception {
        try (FileReader reader = new FileReader(pomFile)) {
            return new MavenXpp3Reader().read(reader);
        }
    }

    /**
     * 使用 maven-model-builder 构建 effective model，
     * 自动处理 parent 继承、dependencyManagement、属性插值
     */
    private Model buildEffectiveModel(File pomFile) throws ModelBuildingException {
        ModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile(pomFile);
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        request.setProcessPlugins(false);
        request.setSystemProperties(System.getProperties());
        // 从本地仓库解析 parent pom
        request.setModelResolver(new LocalModelResolver(localRepoPath));

        ModelBuildingResult result = modelBuilder.build(request);
        return result.getEffectiveModel();
    }

    private DefaultRepositorySystemSession newSession(Path localRepoPath) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(localRepoPath.toFile());
        try {
            session.setLocalRepositoryManager(
                    new SimpleLocalRepositoryManagerFactory().newInstance(session, localRepo));
        } catch (NoLocalRepositoryManagerException e) {
            throw new RuntimeException("Failed to create local repository manager", e);
        }
        return session;
    }

    public ArtifactLocator getArtifactLocator() {
        return artifactLocator;
    }
}
