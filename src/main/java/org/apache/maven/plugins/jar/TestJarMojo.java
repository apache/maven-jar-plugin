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

import java.nio.file.Path;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

/**
 * Build a JAR of the test classes for the current project.
 *
 * @author <a href="evenisse@apache.org">Emmanuel Venisse</a>
 */
@Mojo(name = "test-jar", defaultPhase = "package")
public class TestJarMojo extends AbstractJarMojo {

    /**
     * Set this to {@code true} to bypass test-jar generation.
     * Its use is not recommended, but quite convenient on occasion.
     */
    @Parameter(property = "maven.test.skip")
    private boolean skip;

    /**
     * Directory containing the test classes and resource files that should be packaged into the JAR.
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
    private Path testClassesDirectory;

    /**
     * Classifier to use for {@code test-jar}.
     */
    @Parameter(defaultValue = "tests")
    private String classifier;

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
    protected Path getClassesDirectory() {
        return testClassesDirectory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoException {
        if (skip) {
            getLog().info("Skipping packaging of the test-jar.");
        } else {
            super.execute();
        }
    }
}
