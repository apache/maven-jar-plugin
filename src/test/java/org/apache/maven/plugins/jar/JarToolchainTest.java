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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.jar.Attributes;

import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoExtension;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.plugins.jar.JarTestSupport.files;
import static org.apache.maven.plugins.jar.JarTestSupport.jar;
import static org.apache.maven.plugins.jar.JarTestSupport.layout;
import static org.apache.maven.plugins.jar.JarTestSupport.manifest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code Build-Jdk-Spec} and {@code Build-Tool-Jdk-Spec}: the manifest names the JDK of the {@code jdk} toolchain,
 * not the JDK that runs Maven. The toolchain's {@code javac} is a script that prints a version, so the tests need
 * no second JDK.
 */
@MojoTest
class JarToolchainTest {

    @Inject
    private MavenProject project;

    @TempDir
    private Path tmp;

    @Test
    @InjectMojo(goal = "jar")
    void buildJdkSpecIsTakenFromAJava17Toolchain(JarMojo mojo) throws Exception {
        assertBuildJdkSpec(mojo, "javac 17.0.2", "17");
    }

    @Test
    @InjectMojo(goal = "jar")
    void buildJdkSpecIsTakenFromAJava8Toolchain(JarMojo mojo) throws Exception {
        assertBuildJdkSpec(mojo, "javac 1.8.0_362", "1.8");
    }

    @Test
    @InjectMojo(goal = "jar")
    void buildJdkSpecIsNotSetWithoutAToolchain(JarMojo mojo) throws Exception {
        Attributes attributes = packageWith(mojo, null);

        assertNull(attributes.getValue("Build-Tool-Jdk-Spec"));
        assertEquals(System.getProperty("java.specification.version"), attributes.getValue("Build-Jdk-Spec"));
    }

    private void assertBuildJdkSpec(JarMojo mojo, String javacVersionLine, String expectedSpec) throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win"));
        Path javac = tmp.resolve("jdk/bin/javac");
        Files.createDirectories(javac.getParent());
        Files.write(javac, ("#!/bin/sh\necho '" + javacVersionLine + "' >&2\n").getBytes(StandardCharsets.UTF_8));
        Assumptions.assumeTrue(javac.toFile().setExecutable(true));
        Toolchain toolchain = mock(Toolchain.class);
        when(toolchain.findTool("javac")).thenReturn(javac.toString());

        Attributes attributes = packageWith(mojo, toolchain);

        assertEquals(expectedSpec, attributes.getValue("Build-Jdk-Spec"));
        assertEquals(System.getProperty("java.specification.version"), attributes.getValue("Build-Tool-Jdk-Spec"));
    }

    private Attributes packageWith(JarMojo mojo, Toolchain toolchain) throws Exception {
        Path output = JarTestSupport.initProject(project, tmp);
        Path classes = files(output.resolve("classes"), "myproject/HelloWorld.class");
        layout(mojo, "classesDirectory", classes, output);
        ToolchainManager manager = mock(ToolchainManager.class);
        when(manager.getToolchainFromBuildContext(eq("jdk"), any())).thenReturn(toolchain);
        MojoExtension.setVariableValueToObject(mojo, "toolchainManager", manager);

        mojo.execute();

        return manifest(jar(output, null)).getMainAttributes();
    }
}
