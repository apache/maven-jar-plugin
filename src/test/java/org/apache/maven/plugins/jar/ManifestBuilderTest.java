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

import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.apache.maven.api.plugin.MojoException;
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
    void buildJdkSpecPresentByDefault() {
        // buildJdkSpecDefaultEntry is true unless overridden
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
}
