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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ArchiveConfiguration} and its nested types.
 */
class ArchiveConfigurationTest {

    // -----------------------------------------------------------------------
    // ArchiveConfiguration — defaults
    // -----------------------------------------------------------------------

    @Test
    void defaultsAreCorrect() {
        ArchiveConfiguration config = new ArchiveConfiguration();
        assertTrue(config.compress, "compress should default to true");
        assertTrue(config.addMavenDescriptor, "addMavenDescriptor should default to true");
        assertTrue(config.forced, "forced should default to true");
        assertNull(config.manifestFile, "manifestFile should default to null");
        assertNull(config.pomPropertiesFile, "pomPropertiesFile should default to null");
        assertTrue(config.manifestEntries.isEmpty(), "manifestEntries should be empty by default");
        assertTrue(config.manifestSections.isEmpty(), "manifestSections should be empty by default");
    }

    @Test
    void manifestLazyInitReturnsSameInstance() {
        ArchiveConfiguration config = new ArchiveConfiguration();
        ArchiveConfiguration.ManifestConfig first = config.getManifest();
        assertNotNull(first, "getManifest() should never return null");
        assertNotSame(new ArchiveConfiguration().getManifest(), first, "different instances for different configs");
        // second call on same config returns the same object
        assertEquals(first, config.getManifest(), "getManifest() should return the same instance on repeated calls");
    }

    // -----------------------------------------------------------------------
    // ArchiveConfiguration — manifest entries
    // -----------------------------------------------------------------------

    @Test
    void manifestEntriesDirectAccess() {
        ArchiveConfiguration config = new ArchiveConfiguration();
        assertTrue(config.manifestEntries.isEmpty());
        config.manifestEntries.put("My-Key", "my-value");
        assertFalse(config.manifestEntries.isEmpty());
        assertEquals("my-value", config.manifestEntries.get("My-Key"));
    }

    @Test
    void manifestEntriesOverwrite() {
        ArchiveConfiguration config = new ArchiveConfiguration();
        config.manifestEntries.put("Key", "v1");
        config.manifestEntries.put("Key", "v2");
        assertEquals("v2", config.manifestEntries.get("Key"));
        assertEquals(1, config.manifestEntries.size());
    }

    // -----------------------------------------------------------------------
    // ArchiveConfiguration — manifest sections
    // -----------------------------------------------------------------------

    @Test
    void manifestSectionsEmptyByDefault() {
        ArchiveConfiguration config = new ArchiveConfiguration();
        assertTrue(config.manifestSections.isEmpty());
    }

    @Test
    void manifestSectionDirectFieldAccess() {
        ArchiveConfiguration.ManifestSection section = new ArchiveConfiguration.ManifestSection();
        assertNull(section.name, "name should default to null");
        section.name = "my-section";
        assertEquals("my-section", section.name);
        assertTrue(section.manifestEntries.isEmpty());
        section.manifestEntries.put("k", "v");
        assertEquals("v", section.manifestEntries.get("k"));
    }

    // -----------------------------------------------------------------------
    // ManifestConfig — defaults
    // -----------------------------------------------------------------------

    @Test
    void manifestConfigDefaults() {
        ArchiveConfiguration.ManifestConfig manifest = new ArchiveConfiguration.ManifestConfig();
        assertNull(manifest.mainClass);
        assertFalse(manifest.addClasspath);
        assertEquals("", manifest.classpathPrefix);
        assertTrue(manifest.addDefaultEntries);
        assertFalse(manifest.addBuildEnvironmentEntries);
        assertFalse(manifest.addDefaultSpecificationEntries);
        assertFalse(manifest.addDefaultImplementationEntries);
        assertEquals("simple", manifest.classpathLayoutType);
        assertNull(manifest.customClasspathLayout);
        assertTrue(manifest.useUniqueVersions);
    }
}
