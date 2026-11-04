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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.apache.maven.api.plugin.testing.MojoExtension;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.project.MavenProject;

/**
 * Shared set-up and jar inspection for the tests that run the real {@link JarMojo} and {@link TestJarMojo}
 * against a temporary directory.
 */
final class JarTestSupport {

    static final String GROUP_ID = "org.apache.maven.plugins";

    static final String ARTIFACT_ID = "jar-it";

    static final String VERSION = "99.0";

    static final String FINAL_NAME = ARTIFACT_ID + "-" + VERSION;

    private JarTestSupport() {}

    /**
     * Gives the spied project the coordinates, POM file and main artifact that Maven would have set
     * before the {@code package} phase, and returns the directory the jar is written to.
     */
    static Path initProject(MavenProject project, Path root) throws IOException {
        return initProject(project, root, VERSION);
    }

    /**
     * Like {@link #initProject(MavenProject, Path)}, with a project version other than {@link #VERSION}. The
     * version is taken from the main artifact, and the jar is still named after {@link #FINAL_NAME}.
     */
    static Path initProject(MavenProject project, Path root, String version) throws IOException {
        project.setGroupId(GROUP_ID);
        project.setArtifactId(ARTIFACT_ID);
        project.setVersion(version);
        Path pom = root.resolve("pom.xml");
        Files.write(pom, "<project/>".getBytes(StandardCharsets.UTF_8));
        project.setFile(pom.toFile());
        project.setArtifact(new DefaultArtifact(
                GROUP_ID, ARTIFACT_ID, version, "compile", "jar", null, new DefaultArtifactHandler("jar")));
        return Files.createDirectories(root.resolve("target"));
    }

    /**
     * Points the mojo at the given content directory and output directory, and names the jar
     * {@link #FINAL_NAME}.
     *
     * @param contentField {@code classesDirectory} for {@link JarMojo}, {@code testClassesDirectory} for
     *     {@link TestJarMojo}
     */
    static void layout(AbstractJarMojo mojo, String contentField, Path content, Path outputDirectory)
            throws IllegalAccessException {
        MojoExtension.setVariableValueToObject(mojo, contentField, content.toFile());
        MojoExtension.setVariableValueToObject(mojo, "outputDirectory", outputDirectory.toFile());
        MojoExtension.setVariableValueToObject(mojo, "finalName", FINAL_NAME);
    }

    /** Creates the named empty files below {@code dir}, together with their parent directories. */
    static Path files(Path dir, String... names) throws IOException {
        Files.createDirectories(dir);
        for (String name : names) {
            Path file = dir.resolve(name);
            Files.createDirectories(file.getParent());
            Files.write(file, new byte[0]);
        }
        return dir;
    }

    /** The file entries of the jar, without directory entries. */
    static Set<String> fileEntries(Path jar) throws IOException {
        Set<String> names = new HashSet<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = jarFile.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    /** The entries Maven adds to every jar: the manifest and the descriptor of the project. */
    static Set<String> mavenEntries() {
        String base = "META-INF/maven/" + GROUP_ID + "/" + ARTIFACT_ID + "/";
        Set<String> names = new HashSet<>();
        Collections.addAll(names, "META-INF/MANIFEST.MF", base + "pom.xml", base + "pom.properties");
        return names;
    }

    /** The given names plus {@link #mavenEntries()}. */
    static Set<String> withMavenEntries(String... names) {
        Set<String> all = mavenEntries();
        Collections.addAll(all, names);
        return all;
    }

    static Manifest manifest(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return jarFile.getManifest();
        }
    }

    static Path jar(Path outputDirectory, String classifier) {
        return outputDirectory.resolve(FINAL_NAME + (classifier == null ? "" : "-" + classifier) + ".jar");
    }
}
