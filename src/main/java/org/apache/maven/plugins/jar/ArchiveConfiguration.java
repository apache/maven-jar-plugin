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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for archive creation, provided through the {@code <archive>} plugin parameter.
 *
 * <p>This class replaces {@code org.apache.maven.shared.archiver.MavenArchiveConfiguration},
 * removing the dependency on {@code maven-archiver} and {@code plexus-archiver}.</p>
 *
 * <p><strong>Note:</strong> This class and its inner classes must remain {@code public} until
 * <a href="https://github.com/apache/maven/pull/13470">apache/maven#13470</a> is released,
 * as Maven's XML/DI binding requires public visibility to inject nested configuration objects.</p>
 *
 * @since 4.0.0-beta-3
 */
public class ArchiveConfiguration {

    boolean compress = true;

    boolean addMavenDescriptor = true;

    Path manifestFile;

    ManifestConfig manifest;

    Map<String, String> manifestEntries = new LinkedHashMap<>();

    List<ManifestSection> manifestSections = new ArrayList<>();

    boolean forced = true;

    Path pomPropertiesFile;

    /**
     * Lazily initialises and returns the manifest configuration.
     */
    ManifestConfig getManifest() {
        if (manifest == null) {
            manifest = new ManifestConfig();
        }
        return manifest;
    }

    /**
     * Manifest configuration, provided through the {@code <archive><manifest>} plugin parameter.
     *
     * <p><strong>Note:</strong> Must remain {@code public}; see {@link ArchiveConfiguration} for details.</p>
     */
    public static class ManifestConfig {

        String mainClass;

        boolean addClasspath;

        String classpathPrefix = "";

        boolean addDefaultEntries = true;

        boolean addBuildEnvironmentEntries;

        boolean addDefaultSpecificationEntries;

        boolean addDefaultImplementationEntries;

        String classpathLayoutType = "simple";

        String customClasspathLayout;

        boolean useUniqueVersions = true;
    }

    /**
     * An additional manifest section, provided through the {@code <archive><manifestSections>} plugin parameter.
     *
     * <p><strong>Note:</strong> Must remain {@code public}; see {@link ArchiveConfiguration} for details.</p>
     */
    public static class ManifestSection {

        String name;

        Map<String, String> manifestEntries = new LinkedHashMap<>();
    }
}
