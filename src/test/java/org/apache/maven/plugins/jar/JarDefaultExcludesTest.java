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
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoExtension;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code addDefaultExcludes}: whether files such as {@code .DS_Store} and editor backups are kept out of the jar.
 *
 * <p>The files are the ones the default excludes of the archiver still list; {@code .cvsignore} and {@code .gitignore}
 * are not among them any more, so they cannot show the difference.
 */
@MojoTest
class JarDefaultExcludesTest {

    private static final String FINAL_NAME = "default-excludes-1.0-SNAPSHOT";

    @Inject
    private MavenProject project;

    @TempDir
    private Path tmp;

    @Test
    @InjectMojo(goal = "jar")
    void defaultExcludesKeepVersionControlAndBackupFilesOutOfTheJar(JarMojo mojo) throws Exception {
        Set<String> entries = packageWithIgnoredFiles(mojo);

        assertTrue(entries.contains("Foo.class"), "regular files must stay");
        assertEquals(
                new HashSet<>(), ignoredFiles(entries), "files matched by the default excludes must not be in the jar");
    }

    @Test
    @InjectMojo(goal = "jar")
    @MojoParameter(name = "addDefaultExcludes", value = "false")
    void disablingDefaultExcludesKeepsThoseFiles(JarMojo mojo) throws Exception {
        Set<String> entries = packageWithIgnoredFiles(mojo);

        assertTrue(entries.contains("Foo.class"), "regular files must stay");
        Set<String> expected = new HashSet<>();
        expected.add(".DS_Store");
        expected.add(".svn/entries");
        expected.add("Foo.java~");
        assertEquals(expected, ignoredFiles(entries));
    }

    private Set<String> packageWithIgnoredFiles(JarMojo mojo) throws Exception {
        project.setGroupId("org.apache.maven.plugins");
        project.setArtifactId("default-excludes");
        project.setVersion("1.0-SNAPSHOT");
        Path pom = Files.write(tmp.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
        project.setFile(pom.toFile());
        project.setArtifact(new DefaultArtifact(
                "org.apache.maven.plugins",
                "default-excludes",
                "1.0-SNAPSHOT",
                "compile",
                "jar",
                null,
                new DefaultArtifactHandler("jar")));

        Path classes = tmp.resolve("target/classes");
        Files.createDirectories(classes.resolve(".svn"));
        for (String name : new String[] {"Foo.class", ".DS_Store", ".svn/entries", "Foo.java~"}) {
            Files.write(classes.resolve(name), new byte[0]);
        }
        MojoExtension.setVariableValueToObject(mojo, "classesDirectory", classes.toFile());
        MojoExtension.setVariableValueToObject(
                mojo, "outputDirectory", tmp.resolve("target").toFile());
        MojoExtension.setVariableValueToObject(mojo, "finalName", FINAL_NAME);

        mojo.execute();

        Set<String> entries = new HashSet<>();
        try (JarFile jar =
                new JarFile(tmp.resolve("target/" + FINAL_NAME + ".jar").toFile())) {
            for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory()) {
                    entries.add(entry.getName());
                }
            }
        }
        return entries;
    }

    /** The entries that the default excludes are meant to drop. */
    private static Set<String> ignoredFiles(Set<String> entries) {
        Set<String> ignored = new HashSet<>(entries);
        ignored.retainAll(new HashSet<>(Arrays.asList(".DS_Store", ".svn/entries", "Foo.java~")));
        return ignored;
    }
}
