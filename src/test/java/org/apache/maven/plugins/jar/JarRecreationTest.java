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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code forceCreation}: whether packaging again rewrites a jar that is already up to date.
 *
 * <p>After the first run the test replaces the jar with stale bytes and dates it one hour ahead, so it counts as up
 * to date. The content after the second run tells whether the jar was rebuilt. The modification time cannot: an
 * identical jar is not written again, even when the creation is forced.
 */
@MojoTest
class JarRecreationTest {

    private static final String FINAL_NAME = "recreation-1.0-SNAPSHOT";

    private static final byte[] STALE = "stale".getBytes(StandardCharsets.UTF_8);

    private static final FileTime AHEAD_OF_SOURCES =
            FileTime.from(Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS));

    @Inject
    private MavenProject project;

    @TempDir
    private Path tmp;

    @Test
    @InjectMojo(goal = "jar")
    @MojoParameter(name = "forceCreation", value = "true")
    @MojoParameter(name = "outputTimestamp", value = "1980-02-01T00:00:00Z")
    void forceCreationRewritesAnUpToDateJar(JarMojo mojo) throws Exception {
        Path jar = packageOverStaleJar(mojo);

        assertFalse(Arrays.equals(STALE, Files.readAllBytes(jar)), "the stale jar was kept");
        assertEquals(Collections.singleton("myproject/HelloWorld.class"), classEntries(jar));
    }

    @Test
    @InjectMojo(goal = "jar")
    @MojoParameter(name = "outputTimestamp", value = "1980-02-01T00:00:00Z")
    void upToDateJarIsKeptWithoutForceCreation(JarMojo mojo) throws Exception {
        Path jar = packageOverStaleJar(mojo);

        assertArrayEquals(STALE, Files.readAllBytes(jar), "the up to date jar was rewritten");
    }

    /** Runs the mojo, replaces the jar with stale bytes dated ahead of its sources, runs the mojo again. */
    private Path packageOverStaleJar(JarMojo mojo) throws Exception {
        project.setGroupId("org.apache.maven.plugins");
        project.setArtifactId("recreation");
        project.setVersion("1.0-SNAPSHOT");
        Path pom = Files.write(tmp.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
        project.setFile(pom.toFile());
        DefaultArtifact artifact = new DefaultArtifact(
                "org.apache.maven.plugins",
                "recreation",
                "1.0-SNAPSHOT",
                "compile",
                "jar",
                null,
                new DefaultArtifactHandler("jar"));
        project.setArtifact(artifact);

        Path classes = tmp.resolve("target/classes/myproject");
        Files.createDirectories(classes);
        Files.write(classes.resolve("HelloWorld.class"), new byte[0]);
        Path output = tmp.resolve("target");
        MojoExtension.setVariableValueToObject(
                mojo, "classesDirectory", output.resolve("classes").toFile());
        MojoExtension.setVariableValueToObject(mojo, "outputDirectory", output.toFile());
        MojoExtension.setVariableValueToObject(mojo, "finalName", FINAL_NAME);

        mojo.execute();

        Path jar = output.resolve(FINAL_NAME + ".jar");
        assertTrue(Files.isRegularFile(jar), "the first run must write the jar");
        Files.write(jar, STALE);
        Files.setLastModifiedTime(jar, AHEAD_OF_SOURCES);
        // the first run set the jar as the main artifact, and the mojo refuses to replace it
        artifact.setFile(null);

        mojo.execute();

        return jar;
    }

    private static Set<String> classEntries(Path jar) throws IOException {
        Set<String> names = new HashSet<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = jarFile.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".class")) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
