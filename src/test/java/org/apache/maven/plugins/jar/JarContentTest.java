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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.plugins.jar.JarTestSupport.FINAL_NAME;
import static org.apache.maven.plugins.jar.JarTestSupport.fileEntries;
import static org.apache.maven.plugins.jar.JarTestSupport.files;
import static org.apache.maven.plugins.jar.JarTestSupport.jar;
import static org.apache.maven.plugins.jar.JarTestSupport.layout;
import static org.apache.maven.plugins.jar.JarTestSupport.withMavenEntries;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which files end up in the jar and where the jar is written: {@code includes}, {@code excludes},
 * {@code classifier}, {@code skipIfEmpty}, {@code skip} and {@code attach} of the {@code jar} and
 * {@code test-jar} goals.
 */
@MojoTest
class JarContentTest {

    @Inject
    private MavenProject project;

    @TempDir
    private Path tmp;

    private Path output;

    private Path classes;

    private Path testClasses;

    @BeforeEach
    void setUp() throws IOException {
        output = Files.createDirectories(tmp.resolve("target"));
        classes = output.resolve("classes");
        testClasses = output.resolve("test-classes");
    }

    /** The harness replaces the project model when it creates the mojo, so the project is set up afterwards. */
    private void initProject() throws IOException {
        JarTestSupport.initProject(project, tmp);
    }

    @Test
    @InjectMojo(goal = "jar")
    void jarContainsAllFilesByDefault(JarMojo mojo) throws Exception {
        initProject();
        files(classes, "default-configuration.properties", "foo/project001/App.class");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        assertEquals(
                withMavenEntries("default-configuration.properties", "foo/project001/App.class"),
                fileEntries(jar(output, null)));
        assertEquals(jar(output, null).toFile(), project.getArtifact().getFile());
    }

    @Test
    @InjectMojo(goal = "jar")
    void jarKeepsEveryFileOfAllPackages(JarMojo mojo) throws Exception {
        initProject();
        files(
                classes,
                "service/TestInterface.class",
                "service/impl/TestImplementation.class",
                "TestCompile1.class",
                "notIncluded.xml");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        assertEquals(
                withMavenEntries(
                        "service/TestInterface.class",
                        "service/impl/TestImplementation.class",
                        "TestCompile1.class",
                        "notIncluded.xml"),
                fileEntries(jar(output, null)));
    }

    @Test
    @InjectMojo(goal = "jar")
    @MojoParameter(name = "excludes", value = "**/*.class,**/*.txt")
    void excludesRemoveMatchingFiles(JarMojo mojo) throws Exception {
        initProject();
        files(classes, "foo/project003/App.class", "default-configuration.properties", "excluded-file.txt");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        assertEquals(withMavenEntries("default-configuration.properties"), fileEntries(jar(output, null)));
    }

    @Test
    @InjectMojo(goal = "jar")
    @MojoParameter(name = "includes", value = "**/service/*")
    void includesLimitTheJarToMatchingFiles(JarMojo mojo) throws Exception {
        initProject();
        files(
                classes,
                "service/TestInterface.class",
                "service/impl/TestImplementation.class",
                "TestCompile1.class",
                "notIncluded.xml");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        assertEquals(withMavenEntries("service/TestInterface.class"), fileEntries(jar(output, null)));
    }

    @Test
    @InjectMojo(goal = "jar")
    @MojoParameter(name = "classifier", value = "service")
    @MojoParameter(name = "includes", value = "**/service/*")
    void classifierJarIsAttachedNextToTheMainArtifact(JarMojo mojo) throws Exception {
        initProject();
        files(
                classes,
                "service/TestInterface.class",
                "service/impl/TestImplementation.class",
                "TestCompile1.class",
                "notIncluded.xml");
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        Path serviceJar = jar(output, "service");
        assertEquals(withMavenEntries("service/TestInterface.class"), fileEntries(serviceJar));
        assertFalse(Files.exists(jar(output, null)), "a classifier jar must not replace the main jar");
        assertEquals(1, project.getAttachedArtifacts().size());
        Artifact attached = project.getAttachedArtifacts().get(0);
        assertEquals("service", attached.getClassifier());
        assertEquals(serviceJar.toFile(), attached.getFile());
    }

    @Test
    @InjectMojo(goal = "test-jar")
    void testJarContainsTestClassesAndResources(TestJarMojo mojo) throws Exception {
        initProject();
        files(
                testClasses,
                "test-default-configuration.properties",
                "foo/project003/AppTest.class",
                "foo/project003/AppIntegrationTest.class");
        layout(mojo, "testClassesDirectory", testClasses, output);

        mojo.execute();

        assertEquals(
                withMavenEntries(
                        "test-default-configuration.properties",
                        "foo/project003/AppTest.class",
                        "foo/project003/AppIntegrationTest.class"),
                fileEntries(jar(output, "tests")));
    }

    @Test
    @InjectMojo(goal = "test-jar")
    @MojoParameter(name = "excludes", value = "**/*.txt")
    void testJarExcludesRemoveMatchingFiles(TestJarMojo mojo) throws Exception {
        initProject();
        files(
                testClasses,
                "test-default-configuration.properties",
                "excluded-file.txt",
                "foo/project003/AppTest.class",
                "foo/project003/AppIntegrationTest.class");
        layout(mojo, "testClassesDirectory", testClasses, output);

        mojo.execute();

        assertEquals(
                withMavenEntries(
                        "test-default-configuration.properties",
                        "foo/project003/AppTest.class",
                        "foo/project003/AppIntegrationTest.class"),
                fileEntries(jar(output, "tests")));
    }

    @Test
    @InjectMojo(goal = "test-jar")
    @MojoParameter(name = "includes", value = "**/*IntegrationTest.class")
    void testJarIncludesLimitTheJarToMatchingFiles(TestJarMojo mojo) throws Exception {
        initProject();
        files(
                testClasses,
                "test-default-configuration.properties",
                "foo/project003/AppTest.class",
                "foo/project003/AppIntegrationTest.class");
        layout(mojo, "testClassesDirectory", testClasses, output);

        mojo.execute();

        assertEquals(withMavenEntries("foo/project003/AppIntegrationTest.class"), fileEntries(jar(output, "tests")));
    }

    @Test
    @InjectMojo(goal = "test-jar")
    @MojoParameter(name = "skipIfEmpty", value = "true")
    void skipIfEmptyWritesNoTestJarWhenTheDirectoryDoesNotExist(TestJarMojo mojo) throws Exception {
        initProject();
        layout(mojo, "testClassesDirectory", testClasses, output);

        mojo.execute();

        assertNoJarWritten();
    }

    @Test
    @InjectMojo(goal = "test-jar")
    @MojoParameter(name = "skipIfEmpty", value = "true")
    void skipIfEmptyWritesNoTestJarWhenTheDirectoryIsEmpty(TestJarMojo mojo) throws Exception {
        initProject();
        Files.createDirectories(testClasses);
        layout(mojo, "testClassesDirectory", testClasses, output);

        mojo.execute();

        assertNoJarWritten();
    }

    @Test
    @InjectMojo(goal = "test-jar")
    void testJarIsNamedAfterTheTestsClassifier(TestJarMojo mojo) throws Exception {
        initProject();
        files(testClasses, "A.class");
        layout(mojo, "testClassesDirectory", testClasses, output);

        mojo.execute();

        assertEquals(
                FINAL_NAME + "-tests.jar", jar(output, "tests").getFileName().toString());
        assertEquals(withMavenEntries("A.class"), fileEntries(jar(output, "tests")));
        assertEquals("tests", project.getAttachedArtifacts().get(0).getClassifier());
    }

    @Test
    @InjectMojo(goal = "test-jar")
    @MojoParameter(name = "classifier", value = "xyz")
    void testJarClassifierIsConfigurable(TestJarMojo mojo) throws Exception {
        initProject();
        files(testClasses, "A.class");
        layout(mojo, "testClassesDirectory", testClasses, output);

        mojo.execute();

        assertEquals(withMavenEntries("A.class"), fileEntries(jar(output, "xyz")));
        assertFalse(Files.exists(jar(output, "tests")), "the default classifier must not be used");
        assertEquals("xyz", project.getAttachedArtifacts().get(0).getClassifier());
    }

    @Test
    @InjectMojo(goal = "test-jar")
    @MojoParameter(name = "skip", value = "true")
    void skipWritesNoTestJar(TestJarMojo mojo) throws Exception {
        initProject();
        files(testClasses, "FooTest.class");
        layout(mojo, "testClassesDirectory", testClasses, output);

        mojo.execute();

        assertNoJarWritten();
    }

    @Test
    @InjectMojo(goal = "test-jar")
    @MojoParameter(name = "attach", value = "false")
    void attachFalseWritesTheTestJarWithoutAttachingIt(TestJarMojo mojo) throws Exception {
        initProject();
        files(testClasses, "Foo.class");
        layout(mojo, "testClassesDirectory", testClasses, output);

        mojo.execute();

        assertTrue(Files.isRegularFile(jar(output, "tests")));
        assertTrue(project.getAttachedArtifacts().isEmpty(), "the test jar must not be attached");
    }

    @Test
    @InjectMojo(goal = "test-jar")
    void attachDefaultsToAttachingTheTestJar(TestJarMojo mojo) throws Exception {
        initProject();
        files(testClasses, "Foo.class");
        layout(mojo, "testClassesDirectory", testClasses, output);

        mojo.execute();

        assertEquals(1, project.getAttachedArtifacts().size());
        assertEquals("test-jar", project.getAttachedArtifacts().get(0).getType());
    }

    private void assertNoJarWritten() throws IOException {
        try (Stream<Path> written = Files.list(output)) {
            List<String> jars = written.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".jar"))
                    .collect(Collectors.toList());
            assertEquals(Collections.emptyList(), jars, "no jar may be written");
        }
        assertTrue(project.getAttachedArtifacts().isEmpty(), "nothing may be attached");
    }
}
