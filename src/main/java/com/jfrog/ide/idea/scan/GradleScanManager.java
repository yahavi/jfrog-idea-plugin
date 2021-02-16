package com.jfrog.ide.idea.scan;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.project.AbstractNamedData;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.project.dependencies.ArtifactDependencyNode;
import com.intellij.openapi.externalSystem.model.project.dependencies.ComponentDependencies;
import com.intellij.openapi.externalSystem.model.project.dependencies.DependencyNode;
import com.intellij.openapi.externalSystem.model.project.dependencies.ProjectDependencies;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager;
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback;
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataManagerImpl;
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.jfrog.ide.common.scan.ComponentPrefix;
import com.jfrog.ide.idea.inspections.GradleInspection;
import com.jfrog.ide.idea.utils.Utils;
import com.jfrog.xray.client.impl.services.summary.ComponentDetailImpl;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;
import org.jfrog.build.extractor.scan.DependenciesTree;
import org.jfrog.build.extractor.scan.GeneralInfo;
import org.jfrog.build.extractor.scan.Scope;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.jfrog.ide.idea.utils.Utils.getProjectBasePath;

/**
 * Created by Yahav Itzhak on 9 Nov 2017.
 */
public class GradleScanManager extends ScanManager {

    private Map<String, Collection<DataNode<ProjectDependencies>>> projectDependencies = Maps.newHashMap();
    private Map<String, Collection<DataNode<ModuleData>>> moduleData = Maps.newHashMap();
    private Map<String, Boolean> projectResolved = Maps.newHashMap();

    GradleScanManager(Project project) throws IOException {
        super(project, project, ComponentPrefix.GAV);
    }

    static boolean isApplicable(@NotNull Project project) {
        GradleSettings.MyState state = GradleSettings.getInstance(project).getState();
        return state != null && !state.getLinkedExternalProjectsSettings().isEmpty();
    }

    /**
     * Returns all project modules locations as Paths.
     * Other scanners such as npm will use this paths in order to find modules.
     *
     * @return all project modules locations as Paths
     */
    public Set<Path> getProjectPaths() {
        Set<Path> paths = super.getProjectPaths();
        GradleSettings.MyState gradleState = GradleSettings.getInstance(project).getState();
        if (gradleState != null) {
            gradleState.getLinkedExternalProjectsSettings()
                    .stream()
                    .map(ExternalProjectSettings::getModules)
                    .forEach(module -> paths.addAll(module.stream()
                            .map(Paths::get)
                            .collect(Collectors.toSet())));
        } else {
            getLog().warn("Gradle state is null");
        }
        return paths;
    }

    public boolean areAllProjectsResolved() {
        return !projectResolved.containsValue(Boolean.FALSE);
    }

    public void projectResolved(String projectId) {
        if (projectResolved.isEmpty()) {
            Collection<ExternalProjectInfo> externalProjectInfos = ProjectDataManagerImpl.getInstance().getExternalProjectsData(project, GradleConstants.SYSTEM_ID);
            projectResolved = externalProjectInfos.stream()
                    .map(ExternalProjectInfo::getExternalProjectStructure)
                    .filter(Objects::nonNull)
                    .map(DataNode::getData)
                    .collect(Collectors.toMap(AbstractNamedData::getExternalName, (externalProjectInfo) -> Boolean.FALSE));
        }
        projectResolved.replace(projectId, true);
    }

    /**
     * Set the dependency data after Gradle dependencies refresh.
     * This function is called after {@link com.jfrog.ide.idea.gradle.GradleDependenciesDataService#importData}
     *
     * @param dependencyData - The dependencies data
     */
    public void setDependencyData(String projectId, Collection<DataNode<ProjectDependencies>> dependencyData) {
        this.projectDependencies.put(projectId, dependencyData);
    }

    /**
     * Set the dependencies data after Gradle dependencies refresh.
     * This function is called after {@link com.jfrog.ide.idea.gradle.GradleModulesDataService#importData}
     *
     * @param modulesData - The modules data
     */
    public void setModulesData(String projectId, Collection<DataNode<ModuleData>> modulesData) {
        this.moduleData.put(projectId, modulesData);
    }

    @Override
    protected void refreshDependencies(ExternalProjectRefreshCallback cbk) {
        if (!projectDependencies.isEmpty() && !moduleData.isEmpty()) {
            cbk.onSuccess(null);
            return;
        }


        String projectBasePath = getProjectBasePath(project).toString();
        ExternalSystemProcessingManager processingManager = ServiceManager.getService(ExternalSystemProcessingManager.class);
        if (processingManager != null && processingManager.findTask(ExternalSystemTaskType.RESOLVE_PROJECT, GradleConstants.SYSTEM_ID, projectBasePath) != null) {
            // Another scan in progress
            return;
        }
        ImportSpecBuilder builder = new ImportSpecBuilder(project, GradleConstants.SYSTEM_ID).use(ProgressExecutionMode.IN_BACKGROUND_ASYNC).forceWhenUptodate();
        ExternalSystemUtil.refreshProject(projectBasePath, builder);
    }

    @Override
    protected PsiFile[] getProjectDescriptors() {
        String buildGradlePath = Paths.get(Utils.getProjectBasePath(project).toString(), "build.gradle").toString();
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(buildGradlePath);
        if (file == null) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        return new PsiFile[]{psiFile};
    }

    @Override
    protected LocalInspectionTool getInspectionTool() {
        return new GradleInspection();
    }

    @Override
    protected void buildTree() {
        DependenciesTree rootNode = new DependenciesTree(project.getName());
        GeneralInfo generalInfo = new GeneralInfo().name(project.getName()).path(Utils.getProjectBasePath(project).toString());
        rootNode.setGeneralInfo(generalInfo);

        projectDependencies.forEach((projectSystemId, dataNodes) -> {
            DependenciesTree projectNode = new DependenciesTree(projectSystemId);
            Map<String, DependenciesTree> moduleDependencyTree = createModuleDependencyTree(moduleData.get(projectSystemId));
            moduleDependencyTree.values().forEach(projectNode::add);
            dataNodes.forEach(dataNode -> populateModulesWithDependencies(dataNode, moduleDependencyTree));
            if (projectNode.getChildren().size() == 1) {
                projectNode = (DependenciesTree) projectNode.getChildAt(0);
            }
            rootNode.add(projectNode);
        });

        if (rootNode.getChildren().size() == 1) {
            setScanResults((DependenciesTree) rootNode.getChildAt(0));
        } else {
            setScanResults(rootNode);
        }
        setScanResults(rootNode);
        projectDependencies = Maps.newHashMap();
        moduleData = Maps.newHashMap();
        projectResolved = Maps.newHashMap();
    }

    private void populateModulesWithDependencies(DataNode<ProjectDependencies> dataNode, Map<String, DependenciesTree> moduleDependencyTree) {
        Multimap<DependencyNode, Scope> moduleDependencies = HashMultimap.create();
        ProjectDependencies projectDependencies = dataNode.getData();
        String moduleId = getModuleId(dataNode);
        if (!moduleDependencyTree.containsKey(moduleId)) {
            return;
        }

        // Collect dependencies from project components ('main' and 'test').
        for (ComponentDependencies componentDependency : projectDependencies.getComponentsDependencies()) {
            Stream.concat(componentDependency.getCompileDependenciesGraph().getDependencies().stream(), componentDependency.getRuntimeDependenciesGraph().getDependencies().stream())
                    .filter(GradleScanManager::isArtifactDependencyNode)
                    .forEach(dependencyNode -> moduleDependencies.put(dependencyNode, new Scope(componentDependency.getComponentName())));
        }
        // Populate dependencies-tree for all modules.
        moduleDependencies.asMap().forEach((key, value) -> populateDependenciesTree(moduleDependencyTree.get(moduleId), key, (Set<Scope>) value));
    }

    private Map<String, DependenciesTree> createModuleDependencyTree(Collection<DataNode<ModuleData>> moduleData) {
        Map<String, DependenciesTree> moduleDependencyTree = Maps.newHashMap();
        moduleData.stream().map(DataNode::getData).forEach(module -> {
            String groupId = Objects.toString(module.getGroup(), "");
            String artifactId = StringUtils.removeStart(module.getId(), ":");
            String version = Objects.toString(module.getVersion(), "");
            DependenciesTree scanTreeNode = new DependenciesTree(artifactId);
            scanTreeNode.setGeneralInfo(new GeneralInfo().pkgType("gradle").groupId(groupId).artifactId(artifactId).version(version));
            moduleDependencyTree.put(StringUtils.removeStart(module.getId(), ":"), scanTreeNode);
        });
        return moduleDependencyTree;
    }

    private void populateDependenciesTree(DependenciesTree dependenciesTree, DependencyNode dependencyNode, Set<Scope> scopes) {
        ComponentDetailImpl scanComponent = new ComponentDetailImpl(dependencyNode.getDisplayName(), "");
        DependenciesTree treeNode = new DependenciesTree(scanComponent);
        if (scopes != null) {
            treeNode.setScopes(scopes);
        }

        // Recursively search for dependencies and add to tree.
        List<DependencyNode> childrenList = dependencyNode.getDependencies().stream()
                .filter(GradleScanManager::isArtifactDependencyNode)
                .collect(Collectors.toList());
        childrenList.forEach(child -> populateDependenciesTree(treeNode, child, null));

        dependenciesTree.add(treeNode);
    }

    private static boolean isArtifactDependencyNode(DependencyNode dependencyNode) {
        return dependencyNode instanceof ArtifactDependencyNode;
    }

    private static String getModuleId(DataNode<ProjectDependencies> dataNode) {
        DataNode<ModuleData> moduleDataNode = dataNode.getDataNode(ProjectKeys.MODULE);
        if (moduleDataNode == null) {
            return "";
        }
        return StringUtils.removeStart(moduleDataNode.getData().getId(), ":");
    }
}