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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.archiver.Archiver;

/**
 * Build a JAR of the test classes for the current project.
 *
 * @author <a href="evenisse@apache.org">Emmanuel Venisse</a>
 * @version $Id$
 */
@Mojo(
        name = "test-jar",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresProject = true,
        threadSafe = true,
        requiresDependencyResolution = ResolutionScope.TEST)
public class TestJarMojo extends AbstractJarMojo {

    /**
     * Set this to <code>true</code> to bypass test-jar generation. Its use is <b>NOT RECOMMENDED</b>, but quite
     * convenient on occasion.
     */
    @Parameter(property = "maven.test.skip")
    private boolean skip;

    /**
     * Directory containing the test classes and resource files that should be packaged into the JAR.
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
    private File testClassesDirectory;

    /**
     * Classifier to use for {@code test-jar}.
     */
    @Parameter(defaultValue = "tests")
    private String classifier;

    @Inject
    TestJarMojo(
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
        return "test-jar";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected File getClassesDirectory() {
        return testClassesDirectory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping packaging of the test-jar");
        } else {
            super.execute();
        }
    }
}
