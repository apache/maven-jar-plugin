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
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Optional;
import java.util.TimeZone;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

import static org.apache.maven.plugins.jar.JarTestSupport.jar;
import static org.apache.maven.plugins.jar.JarTestSupport.layout;
import static org.apache.maven.plugins.jar.JarTestSupport.manifest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Jars with a module descriptor: the {@code mjar} archiver is chosen and writes the main class into
 * {@code module-info.class}, and the entries of the descriptor are reproducible.
 *
 * <p>The descriptor is compiled from {@code src/test/resources/unit/module-info} by the test, so the tests need a
 * JDK 9 or newer.
 */
@MojoTest
@EnabledForJreRange(min = JRE.JAVA_9)
class JarModuleInfoTest {

    private static final String FIXTURES = "src/test/resources/unit/";

    private static final String MODULE_SOURCES = FIXTURES + "module-info";

    @Inject
    private MavenProject project;

    @TempDir
    private Path tmp;

    private Path output;

    private Path classes;

    private Path compiled;

    @BeforeEach
    void setUp() throws IOException {
        output = Files.createDirectories(tmp.resolve("target"));
        classes = output.resolve("classes");
        compiled = compileModule(Files.createDirectories(tmp.resolve("compiled")));
    }

    /** The harness replaces the project model when it creates the mojo, so the project is set up afterwards. */
    private void initProject() throws IOException {
        JarTestSupport.initProject(project, tmp);
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "main-class/plugin-config.xml")
    void mainClassIsWrittenToTheModuleDescriptor(JarMojo mojo) throws Exception {
        initProject();
        copy("myproject/HelloWorld.class", classes.resolve("myproject/HelloWorld.class"));
        copy("module-info.class", classes.resolve("module-info.class"));
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        Attributes attributes = manifest(jar(output, null)).getMainAttributes();
        assertEquals("myproject.HelloWorld", attributes.getValue(Attributes.Name.MAIN_CLASS));
        assertEquals("myproject.HelloWorld", moduleMainClass(jar(output, null), "module-info.class"));
    }

    @Test
    @InjectMojo(goal = "jar", pom = FIXTURES + "main-class/plugin-config.xml")
    @MojoParameter(name = "outputTimestamp", value = "2022-06-26T13:25:58Z")
    void versionedModuleDescriptorIsReproducible(JarMojo mojo) throws Exception {
        initProject();
        copy("myproject/HelloWorld.class", classes.resolve("myproject/HelloWorld.class"));
        copy("module-info.class", classes.resolve("META-INF/versions/9/module-info.class"));
        layout(mojo, "classesDirectory", classes, output);

        mojo.execute();

        Path jar = jar(output, null);
        Attributes attributes = manifest(jar).getMainAttributes();
        assertEquals("myproject.HelloWorld", attributes.getValue(Attributes.Name.MAIN_CLASS));
        assertEquals("myproject.HelloWorld", moduleMainClass(jar, "META-INF/versions/9/module-info.class"));

        // all entries, the module descriptor included, carry the timestamp from outputTimestamp
        FileTime expectedTimestamp = FileTime.fromMillis(normalizeUtc("2022-06-26T13:25:58Z"));
        int entryCount = 0;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            for (Enumeration<JarEntry> entries = jarFile.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                assertEquals(expectedTimestamp, entry.getLastModifiedTime(), "timestamp of " + entry.getName());
                entryCount++;
            }
        }
        assertTrue(entryCount > 3, "the jar must hold the module descriptor and more");
    }

    /** Compiles the module descriptor and its package; skips the test where only a JRE is available. */
    private static Path compileModule(Path target) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "a JDK is needed to compile module-info.java");
        Path sources = Paths.get(MODULE_SOURCES);
        int result = compiler.run(
                null,
                null,
                null,
                "--release",
                "11",
                "-d",
                target.toString(),
                sources.resolve("module-info.java").toString(),
                sources.resolve("myproject/HelloWorld.java").toString());
        assertEquals(0, result, "module-info.java must compile");
        return target;
    }

    private void copy(String compiledName, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(compiled.resolve(compiledName), to);
    }

    /** {@code ModuleDescriptor.read(...).mainClass()}, by reflection because the tests are compiled for Java 8. */
    private static String moduleMainClass(Path jar, String entryName) throws Exception {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            assertNotNull(entry, entryName + " is missing from the jar");
            try (InputStream in = jarFile.getInputStream(entry)) {
                Class<?> descriptorClass = Class.forName("java.lang.module.ModuleDescriptor");
                Object descriptor =
                        descriptorClass.getMethod("read", InputStream.class).invoke(null, in);
                Optional<?> mainClass =
                        (Optional<?>) descriptorClass.getMethod("mainClass").invoke(descriptor);
                return (String) mainClass.orElse(null);
            }
        }
    }

    /** Moves the timestamp by the offset of the default time zone, as the archiver stores local time. */
    private static long normalizeUtc(String timestamp) {
        long millis = Instant.parse(timestamp).toEpochMilli();
        return millis - TimeZone.getDefault().getOffset(millis);
    }
}
