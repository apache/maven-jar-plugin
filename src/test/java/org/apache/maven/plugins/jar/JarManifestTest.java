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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Organization;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.jar.ManifestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.plugins.jar.JarTestSupport.fileEntries;
import static org.apache.maven.plugins.jar.JarTestSupport.files;
import static org.apache.maven.plugins.jar.JarTestSupport.jar;
import static org.apache.maven.plugins.jar.JarTestSupport.layout;
import static org.apache.maven.plugins.jar.JarTestSupport.manifest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manifest of the jar: what the {@code archive} configuration, the project and the content of the classes
 * directory turn into.
 */
@MojoTest
class JarManifestTest {

    private static final String FIXTURES = "src/test/resources/unit/";

    @Inject
    private MavenProject project;

    @TempDir
    private Path tmp;

    private Path output;

    private Path classes;

    @BeforeEach
    void setUp() throws IOException {
        output = Files.createDirectories(tmp.resolve("target"));
        classes = output.resolve("classes");
    }

    /** The harness replaces the project model when it creates the mojo, so the project is set up afterwards. */
    private void initProject() throws IOException {
        JarTestSupport.initProject(project, tmp);
    }

    private void initProject(String version) throws IOException {
        JarTestSupport.initProject(project, tmp, version);
    }

    private Attributes mainAttributes() throws IOException {
        return manifest(jar(output, null)).getMainAttributes();
    }

    /** A runtime dependency with a stub file: the archiver only lists classpath elements that exist as files. */
    private static Artifact runtimeArtifact(Path dir, String groupId, String artifactId, String version)
            throws IOException {
        DefaultArtifactHandler handler = new DefaultArtifactHandler("jar");
        handler.setAddedToClasspath(true);
        DefaultArtifact artifact = new DefaultArtifact(groupId, artifactId, version, "compile", "jar", null, handler);
        Path file = dir.resolve(artifactId + "-" + version + ".jar");
        Files.write(file, new byte[0]);
        artifact.setFile(file.toFile());
        return artifact;
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "mjar-60/plugin-config.xml")
    void manifestEntryClassPathIsKeptNextToAddClasspath(JarMojo mojo) throws Exception {
        initProject();
        files(classes, "myproject/HelloWorld.class");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        Attributes attributes = mainAttributes();
        assertEquals("config/", attributes.getValue(Attributes.Name.CLASS_PATH));
        assertEquals("myproject.HelloWorld", attributes.getValue(Attributes.Name.MAIN_CLASS));
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "mjar-228/plugin-config.xml")
    void classPathListsTheDependenciesBelowThePrefix(JarMojo mojo) throws Exception {
        initProject();
        Set<Artifact> artifacts = new LinkedHashSet<>();
        artifacts.add(runtimeArtifact(tmp, "junit", "junit", "4.13.2"));
        artifacts.add(runtimeArtifact(tmp, "org.hamcrest", "hamcrest-core", "1.3"));
        project.setArtifacts(artifacts);
        files(classes, "myproject/HelloWorld.class");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        Attributes attributes = mainAttributes();
        assertEquals("myproject.HelloWorld", attributes.getValue(Attributes.Name.MAIN_CLASS));
        assertEquals("lib/junit-4.13.2.jar lib/hamcrest-core-1.3.jar", attributes.getValue(Attributes.Name.CLASS_PATH));
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "manifest-content/plugin-config.xml")
    void manifestHoldsSpecificationAndImplementationEntries(JarMojo mojo) throws Exception {
        initProject("1.0-SNAPSHOT");
        project.setName("manifest-content-it");
        Organization organization = new Organization();
        organization.setName("jar plugin it");
        project.getModel().setOrganization(organization);
        project.getProperties().setProperty("maven.compiler.release", "11");
        files(classes, "myproject/HelloWorld.class");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        List<String> entryNames = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar(output, null).toFile())) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                entryNames.add(entries.nextElement().getName());
            }
        }
        assertEquals("META-INF/", entryNames.get(0));
        assertEquals("META-INF/MANIFEST.MF", entryNames.get(1));

        Attributes attributes = mainAttributes();
        assertEquals("manifest-content-it", attributes.getValue(Attributes.Name.SPECIFICATION_TITLE));
        // https://docs.oracle.com/javase/6/docs/technotes/guides/versioning/spec/versioning2.html
        // the specification version must be "1.0" and not "1.0-SNAPSHOT"
        assertEquals("1.0", attributes.getValue(Attributes.Name.SPECIFICATION_VERSION));
        assertEquals("jar plugin it", attributes.getValue(Attributes.Name.SPECIFICATION_VENDOR));
        assertEquals("manifest-content-it", attributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE));
        assertEquals("1.0-SNAPSHOT", attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
        assertEquals("jar plugin it", attributes.getValue(Attributes.Name.IMPLEMENTATION_VENDOR));
        assertEquals("myproject.HelloWorld", attributes.getValue(Attributes.Name.MAIN_CLASS));
        assertEquals("11", attributes.getValue("Java-Version"));
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "mjar-71-manifest-file/plugin-config.xml")
    void manifestFileIsUsedAsTemplate(JarMojo mojo) throws Exception {
        initProject();
        files(classes, "foo/project003/App.class");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        assertEquals("foobar-1.23456", mainAttributes().getValue("Archiver-Version"));
    }

    @Test
    @InjectMojo(goal = "jar")
    void manifestInTheClassesDirectoryDoesNotReplaceTheGeneratedOne(JarMojo mojo) throws Exception {
        initProject();
        files(classes, "foo/project003/App.class");
        Files.createDirectories(classes.resolve("META-INF"));
        Files.copy(Paths.get(FIXTURES + "mjar-71-manifest-file/MANIFEST.MF"), classes.resolve("META-INF/MANIFEST.MF"));
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        Attributes attributes = mainAttributes();
        assertNull(attributes.getValue("Archiver-Version"), "Archiver-Version of the stray manifest was used");
        assertNull(attributes.getValue("Built-By"), "Built-By of the stray manifest was used");
        assertTrue(attributes.getValue("Created-By").startsWith("Maven JAR Plugin"));
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "mjar-260-valid/plugin-config.xml")
    void validAutomaticModuleNameIsWrittenToTheManifest(JarMojo mojo) throws Exception {
        initProject();
        files(classes, "myproject/HelloWorld.class");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        assertEquals("valid.automatic.module.name", mainAttributes().getValue("Automatic-Module-Name"));
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "mjar-260-invalid/plugin-config.xml")
    void invalidAutomaticModuleNameFailsTheBuildWithoutWritingTheJar(JarMojo mojo) throws Exception {
        initProject();
        files(classes, "myproject/HelloWorld.class");
        layout(mojo, "classesDirectory", classes, output);

        MojoExecutionException failure = assertThrows(MojoExecutionException.class, mojo::execute);

        assertEquals("Error assembling JAR", failure.getMessage());
        ManifestException cause = assertInstanceOf(ManifestException.class, failure.getCause());
        assertEquals("Invalid automatic module name: 'in-valid.name.with.new.keyword'", cause.getMessage());
        assertFalse(Files.exists(jar(output, null)), "no jar may be written");
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "mjar-292-detect/plugin-config.xml")
    void multiReleaseEntryIsAddedWhenVersionsDirectoryExists(JarMojo mojo) throws Exception {
        initProject();
        files(
                classes,
                "myproject/HelloWorld.class",
                "myproject/Utils.class",
                "META-INF/versions/9/myproject/Utils.class",
                "META-INF/versions/10/myproject/Utils.class");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        Attributes attributes = mainAttributes();
        assertEquals("myproject.HelloWorld", attributes.getValue(Attributes.Name.MAIN_CLASS));
        assertEquals("true", attributes.getValue("Multi-Release"));
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "main-class/plugin-config.xml")
    void multiReleaseEntryIsMissingWithoutVersionsDirectory(JarMojo mojo) throws Exception {
        initProject();
        files(classes, "myproject/HelloWorld.class", "myproject/Utils.class");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        Attributes attributes = mainAttributes();
        assertEquals("myproject.HelloWorld", attributes.getValue(Attributes.Name.MAIN_CLASS));
        assertNull(attributes.getValue("Multi-Release"));
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "main-class/plugin-config.xml")
    @MojoParameter(name = "detectMultiReleaseJar", value = "false")
    void multiReleaseDetectionCanBeDisabled(JarMojo mojo) throws Exception {
        initProject();
        files(
                classes,
                "myproject/HelloWorld.class",
                "myproject/Utils.class",
                "META-INF/versions/9/myproject/Utils.class",
                "META-INF/versions/10/myproject/Utils.class");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        Attributes attributes = mainAttributes();
        assertEquals("myproject.HelloWorld", attributes.getValue(Attributes.Name.MAIN_CLASS));
        assertNull(attributes.getValue("Multi-Release"));
        assertTrue(fileEntries(jar(output, null)).contains("META-INF/versions/9/myproject/Utils.class"));
    }

    @Test
    @InjectMojo(goal = "jar")
    @MojoParameter(name = "useDefaultManifestFile", value = "true")
    void useDefaultManifestFileFailsTheBuild(JarMojo mojo) throws Exception {
        initProject();
        files(classes, "myproject/HelloWorld.class");
        layout(mojo, "classesDirectory", classes, output);

        MojoExecutionException failure = assertThrows(MojoExecutionException.class, mojo::execute);

        assertTrue(failure.getMessage().contains("'useDefaultManifestFile' which has been removed"));
        assertFalse(Files.exists(jar(output, null)), "no jar may be written");
    }
}
