package com.jfrog.ide.idea.gradle;

import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.project.dependencies.ProjectDependencies;

import java.util.Collection;

/**
 * @author yahavi
 **/
public class GradleImportedProjectData {
    private Collection<DataNode<ProjectDependencies>> dependencies;
    private Collection<DataNode<ModuleData>> modulesData;

    public Collection<DataNode<ProjectDependencies>> getDependencies() {
        return dependencies;
    }

    public void setDependencies(Collection<DataNode<ProjectDependencies>> dependencies) {
        this.dependencies = dependencies;
    }

    public Collection<DataNode<ModuleData>> getModulesData() {
        return modulesData;
    }

    public void setModulesData(Collection<DataNode<ModuleData>> modulesData) {
        this.modulesData = modulesData;
    }
}
