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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Service;
import org.apache.maven.api.Session;
import org.apache.maven.api.Version;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.Interpolator;
import org.apache.maven.testing.plugin.stubs.ArtifactStub;
import org.apache.maven.testing.plugin.stubs.ProjectStub;
import org.apache.maven.testing.plugin.stubs.SessionStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ManifestBuilder}.
 */
class ManifestBuilderTest {

    private SessionStub session;
    private ProjectStub project;

    @BeforeEach
    void setUp() {
        session = new SessionStub();
        project = new ProjectStub();
        project.setGroupId("com.example");
        project.setArtifactId("my-artifact");
        project.setVersion("1.2.3");
    }

    // -----------------------------------------------------------------------
    // Build-Jdk-Spec: present/absent based on addBuildEnvironmentEntries
    // -----------------------------------------------------------------------

    @Test
    void buildJdkSpecPresentWhenFieldDefaultIsTrue() {
        // Tests the field-level default of ManifestBuilder (buildJdkSpecDefaultEntry = true).
        // Note: in production, AbstractJarMojo always overrides this with
        // archive.getManifest().addBuildEnvironmentEntries (default: false), so
        // Build-Jdk-Spec is NOT present by default in production usage.
        ArchiveConfiguration archive = new ArchiveConfiguration();
        archive.getManifest().addBuildEnvironmentEntries = false;

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        Manifest manifest = builder.build();

        // Build-Jdk-Spec is added from System property when buildJdkSpecDefaultEntry is true
        String specVersion = System.getProperty("java.specification.version");
        if (specVersion != null) {
            assertEquals(specVersion, manifest.getMainAttributes().getValue("Build-Jdk-Spec"));
        }
    }

    @Test
    void buildJdkSpecAbsentWhenDisabled() {
        ArchiveConfiguration archive = new ArchiveConfiguration();
        archive.getManifest().addBuildEnvironmentEntries = false;

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        builder.setBuildJdkSpecDefaultEntry(false);
        Manifest manifest = builder.build();

        assertNull(
                manifest.getMainAttributes().getValue("Build-Jdk-Spec"),
                "Build-Jdk-Spec should be absent when buildJdkSpecDefaultEntry is false");
    }

    @Test
    void buildJdkEntryPresentWhenBuildEnvironmentEntriesEnabled() {
        ArchiveConfiguration archive = new ArchiveConfiguration();
        archive.getManifest().addBuildEnvironmentEntries = true;

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        Manifest manifest = builder.build();

        String javaVersion = System.getProperty("java.version");
        if (javaVersion != null) {
            assertEquals(
                    javaVersion,
                    manifest.getMainAttributes().getValue("Build-Jdk"),
                    "Build-Jdk should be present when addBuildEnvironmentEntries is true");
        }
    }

    // -----------------------------------------------------------------------
    // Extra manifest entries precedence
    // -----------------------------------------------------------------------

    @Test
    void extraEntryOverridesComputedValue() {
        ArchiveConfiguration archive = new ArchiveConfiguration();
        archive.manifestEntries.put("Build-Jdk-Spec", "99");

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        Manifest manifest = builder.build();

        assertEquals(
                "99",
                manifest.getMainAttributes().getValue("Build-Jdk-Spec"),
                "User-supplied extra entry should take precedence over computed value");
    }

    @Test
    void extraEntryIsAddedToMainAttributes() {
        ArchiveConfiguration archive = new ArchiveConfiguration();
        archive.manifestEntries.put("My-Custom-Entry", "hello-world");

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        Manifest manifest = builder.build();

        assertEquals(
                "hello-world",
                manifest.getMainAttributes().getValue("My-Custom-Entry"),
                "Extra entry should be present in the manifest main attributes");
    }

    @Test
    void extraEntryNewlineIsSanitized() {
        ArchiveConfiguration archive = new ArchiveConfiguration();
        archive.manifestEntries.put("My-Entry", "line1\nline2");

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        Manifest manifest = builder.build();

        assertEquals(
                "line1 line2",
                manifest.getMainAttributes().getValue("My-Entry"),
                "Newlines in extra entries should be replaced with spaces");
    }

    // -----------------------------------------------------------------------
    // Manifest section with null/empty name — NPE guard
    // -----------------------------------------------------------------------

    @Test
    void manifestSectionWithNullNameThrows() {
        ArchiveConfiguration archive = new ArchiveConfiguration();
        ArchiveConfiguration.ManifestSection section = new ArchiveConfiguration.ManifestSection();
        section.name = null; // explicitly null — the default
        section.manifestEntries.put("key", "value");
        archive.manifestSections.add(section);

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);

        MojoException ex = assertThrows(
                MojoException.class, builder::build, "A manifest section with null name should throw MojoException");
        assertNotNull(ex.getMessage());
    }

    @Test
    void manifestSectionWithEmptyNameThrows() {
        ArchiveConfiguration archive = new ArchiveConfiguration();
        ArchiveConfiguration.ManifestSection section = new ArchiveConfiguration.ManifestSection();
        section.name = "";
        archive.manifestSections.add(section);

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);

        MojoException ex = assertThrows(
                MojoException.class, builder::build, "A manifest section with empty name should throw MojoException");
        assertNotNull(ex.getMessage());
    }

    @Test
    void manifestSectionWithValidNameSucceeds() {
        ArchiveConfiguration archive = new ArchiveConfiguration();
        ArchiveConfiguration.ManifestSection section = new ArchiveConfiguration.ManifestSection();
        section.name = "com/example/MyClass.class";
        section.manifestEntries.put("Sealed", "true");
        archive.manifestSections.add(section);

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        Manifest manifest = builder.build();

        Attributes sectionAttrs = manifest.getEntries().get("com/example/MyClass.class");
        assertNotNull(sectionAttrs, "Manifest should contain the named section");
        assertEquals("true", sectionAttrs.getValue("Sealed"));
    }

    // -----------------------------------------------------------------------
    // Manifest-Version is always present
    // -----------------------------------------------------------------------

    @Test
    void manifestVersionIsAlwaysPresent() {
        ManifestBuilder builder = new ManifestBuilder(session, project, new ArchiveConfiguration());
        Manifest manifest = builder.build();

        assertEquals(
                "1.0",
                manifest.getMainAttributes().getValue(Attributes.Name.MANIFEST_VERSION),
                "Manifest-Version must always be 1.0");
    }

    // -----------------------------------------------------------------------
    // createdBy
    // -----------------------------------------------------------------------

    @Test
    void createdByIsSetWhenProvided() {
        ArchiveConfiguration archive = new ArchiveConfiguration();
        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        builder.setCreatedBy("Maven JAR Plugin 4.0.0");
        Manifest manifest = builder.build();

        assertEquals("Maven JAR Plugin 4.0.0", manifest.getMainAttributes().getValue("Created-By"));
    }

    @Test
    void createdByDefaultsToMavenJarPlugin() {
        ManifestBuilder builder = new ManifestBuilder(session, project, new ArchiveConfiguration());
        Manifest manifest = builder.build();

        assertEquals(
                "Maven JAR Plugin",
                manifest.getMainAttributes().getValue("Created-By"),
                "Created-By should default to 'Maven JAR Plugin' when not explicitly set");
    }

    // -----------------------------------------------------------------------
    // Class-Path: buildClasspath() — simple layout, repository layout, prefix
    // -----------------------------------------------------------------------

    /**
     * SessionStub subclass that vends configurable DependencyResolver and Interpolator stubs.
     */
    private static class SessionWithServices extends SessionStub {

        private final Map<Dependency, Path> dependencies;

        SessionWithServices(Map<Dependency, Path> dependencies) {
            this.dependencies = dependencies;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Service> T getService(Class<T> clazz) {
            if (clazz == DependencyResolver.class) {
                return (T) (DependencyResolver) new DependencyResolver() {
                    @Override
                    public java.util.List<org.apache.maven.api.Node> flatten(
                            Session session, org.apache.maven.api.Node node, PathScope pathScope)
                            throws org.apache.maven.api.services.DependencyResolverException {
                        return java.util.List.of();
                    }

                    @Override
                    public DependencyResolverResult resolve(DependencyResolverRequest request)
                            throws org.apache.maven.api.services.DependencyResolverException,
                                    org.apache.maven.api.services.ArtifactResolverException {
                        return buildResult();
                    }

                    @Override
                    public DependencyResolverResult resolve(Session session, Project project, PathScope scope) {
                        return buildResult();
                    }

                    private DependencyResolverResult buildResult() {
                        return new DependencyResolverResult() {
                            @Override
                            public DependencyResolverRequest getRequest() {
                                return null;
                            }

                            @Override
                            public java.util.List<Exception> getExceptions() {
                                return java.util.List.of();
                            }

                            @Override
                            public org.apache.maven.api.Node getRoot() {
                                return null;
                            }

                            @Override
                            public java.util.List<org.apache.maven.api.Node> getNodes() {
                                return java.util.List.of();
                            }

                            @Override
                            public java.util.List<Path> getPaths() {
                                return java.util.List.copyOf(dependencies.values());
                            }

                            @Override
                            public java.util.Map<org.apache.maven.api.PathType, java.util.List<Path>>
                                    getDispatchedPaths() {
                                return java.util.Map.of();
                            }

                            @Override
                            public Map<Dependency, Path> getDependencies() {
                                return java.util.Collections.unmodifiableMap(dependencies);
                            }

                            @Override
                            public java.util.Optional<String> getModuleName(Path path) {
                                return java.util.Optional.empty();
                            }

                            @Override
                            public java.util.Optional<java.lang.module.ModuleDescriptor> getModuleDescriptor(
                                    Path path) {
                                return java.util.Optional.empty();
                            }

                            @Override
                            public java.util.Optional<String> warningForFilenameBasedAutomodules() {
                                return java.util.Optional.empty();
                            }
                        };
                    }
                };
            }
            if (clazz == Interpolator.class) {
                return (T) (Interpolator) new Interpolator() {
                    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

                    @Override
                    public String interpolate(
                            String val,
                            UnaryOperator<String> callback,
                            BinaryOperator<String> postprocessor,
                            boolean defaultsToEmpty) {
                        if (val == null) {
                            return null;
                        }
                        Matcher m = PLACEHOLDER.matcher(val);
                        StringBuilder sb = new StringBuilder();
                        while (m.find()) {
                            String key = m.group(1);
                            String resolved = callback != null ? callback.apply(key) : null;
                            m.appendReplacement(sb, Matcher.quoteReplacement(resolved != null ? resolved : ""));
                        }
                        m.appendTail(sb);
                        return sb.toString();
                    }

                    @Override
                    public void interpolate(
                            Map<String, String> map,
                            UnaryOperator<String> callback,
                            BinaryOperator<String> postprocessor,
                            boolean defaultsToEmpty) {
                        map.replaceAll((k, v) -> interpolate(v, callback, postprocessor, defaultsToEmpty));
                    }
                };
            }
            return null;
        }
    }

    /** Minimal {@link Dependency} stub for classpath tests. */
    private static Dependency dep(String groupId, String artifactId, String version, String extension) {
        ArtifactStub a = new ArtifactStub();
        a.setGroupId(groupId);
        a.setArtifactId(artifactId);
        a.setVersion(version);
        a.setBaseVersion(version);
        a.setExtension(extension);
        // Dependency wraps the ArtifactStub; Type stub returns the extension string.
        org.apache.maven.api.Type typeStub = new org.apache.maven.api.Type() {
            @Override
            public String id() {
                return extension;
            }

            @Override
            public String getExtension() {
                return extension;
            }

            @Override
            public String getClassifier() {
                return "";
            }

            @Override
            public org.apache.maven.api.Language getLanguage() {
                return null;
            }

            @Override
            public boolean isIncludesDependencies() {
                return false;
            }

            @Override
            public java.util.Set<org.apache.maven.api.PathType> getPathTypes() {
                return java.util.Set.of();
            }
        };
        return new Dependency() {
            @Override
            public String getGroupId() {
                return a.getGroupId();
            }

            @Override
            public String getArtifactId() {
                return a.getArtifactId();
            }

            @Override
            public Version getVersion() {
                return a.getVersion();
            }

            @Override
            public Version getBaseVersion() {
                return a.getBaseVersion();
            }

            @Override
            public String getClassifier() {
                return a.getClassifier();
            }

            @Override
            public String getExtension() {
                return a.getExtension();
            }

            @Override
            public boolean isSnapshot() {
                return false;
            }

            @Override
            public org.apache.maven.api.Type getType() {
                return typeStub;
            }

            @Override
            public org.apache.maven.api.DependencyScope getScope() {
                return null;
            }

            @Override
            public boolean isOptional() {
                return false;
            }

            @Override
            public org.apache.maven.api.DependencyCoordinates toCoordinates() {
                return null;
            }
        };
    }

    @Test
    void classpathSimpleLayout() throws Exception {
        Dependency dep = dep("com.example", "lib-a", "1.0", "jar");
        // Use a real file-system path that is a regular file — create a temp jar
        java.io.File tmp = java.io.File.createTempFile("lib-a-1.0", ".jar");
        tmp.deleteOnExit();
        Path jarPath = tmp.toPath();

        Map<Dependency, Path> deps = new LinkedHashMap<>();
        deps.put(dep, jarPath);

        SessionWithServices session = new SessionWithServices(deps);
        ProjectStub project = new ProjectStub();

        ArchiveConfiguration archive = new ArchiveConfiguration();
        archive.getManifest().addClasspath = true;
        archive.getManifest().classpathLayoutType = "simple";
        archive.getManifest().useUniqueVersions = true;

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        builder.setBuildJdkSpecDefaultEntry(false);
        Manifest manifest = builder.build();

        String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertNotNull(classPath, "Class-Path should be present when addClasspath=true");
        assertEquals("lib-a-1.0.jar", classPath);
    }

    @Test
    void classpathRepositoryLayout() throws Exception {
        Dependency dep = dep("com.example", "lib-b", "2.3", "jar");
        java.io.File tmp = java.io.File.createTempFile("lib-b-2.3", ".jar");
        tmp.deleteOnExit();
        Path jarPath = tmp.toPath();

        Map<Dependency, Path> deps = new LinkedHashMap<>();
        deps.put(dep, jarPath);

        SessionWithServices session = new SessionWithServices(deps);
        ProjectStub project = new ProjectStub();

        ArchiveConfiguration archive = new ArchiveConfiguration();
        archive.getManifest().addClasspath = true;
        archive.getManifest().classpathLayoutType = "repository";
        archive.getManifest().useUniqueVersions = true;

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        builder.setBuildJdkSpecDefaultEntry(false);
        Manifest manifest = builder.build();

        String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertNotNull(classPath, "Class-Path should be present");
        assertEquals("com/example/lib-b/2.3/lib-b-2.3.jar", classPath);
    }

    @Test
    void classpathWithPrefix() throws Exception {
        Dependency dep = dep("com.example", "lib-c", "3.0", "jar");
        java.io.File tmp = java.io.File.createTempFile("lib-c-3.0", ".jar");
        tmp.deleteOnExit();
        Path jarPath = tmp.toPath();

        Map<Dependency, Path> deps = new LinkedHashMap<>();
        deps.put(dep, jarPath);

        SessionWithServices session = new SessionWithServices(deps);
        ProjectStub project = new ProjectStub();

        ArchiveConfiguration archive = new ArchiveConfiguration();
        archive.getManifest().addClasspath = true;
        archive.getManifest().classpathLayoutType = "simple";
        archive.getManifest().useUniqueVersions = true;
        archive.getManifest().classpathPrefix = "lib/";

        ManifestBuilder builder = new ManifestBuilder(session, project, archive);
        builder.setBuildJdkSpecDefaultEntry(false);
        Manifest manifest = builder.build();

        String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertNotNull(classPath, "Class-Path should be present");
        assertEquals("lib/lib-c-3.0.jar", classPath);
    }
}
