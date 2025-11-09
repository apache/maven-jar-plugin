/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.jar;

import javax.inject.Inject;

import java.io.File;
import java.util.Map;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.archiver.Archiver;

/**
 * Build a JAR from the current project.
 *
 * @author <a href="evenisse@apache.org">Emmanuel Venisse</a>
 * @version $Id$
 */
@Mojo(
        name = "jar",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresProject = true,
        threadSafe = true,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
public class JarMojo extends AbstractJarMojo {
    /**
     * Directory containing the classes and resource files that should be packaged into the JAR.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File classesDirectory;

    /**
     * Classifier to add to the artifact generated. If given, the artifact will be attached
     * as a supplemental artifact.
     * If not given this will create the main artifact which is the default behavior.
     * If you try to do that a second time without using a classifier the build will fail.
     */
    @Parameter
    private String classifier;

    @Inject
    JarMojo(
            MavenProject project,
            MavenSession session,
            ToolchainsJdkSpecification toolchainsJdkSpecification,
            ToolchainManager toolchainManager,
            Map<String, Archiver> archivers,
            MavenProjectHelper projectHelper) {
        super(project, session, toolchainsJdkSpecification, toolchainManager, archivers, projectHelper);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getClassifier() {
        return classifier;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getType() {
        return "jar";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected File getClassesDirectory() {
        return classesDirectory;
    }
}
