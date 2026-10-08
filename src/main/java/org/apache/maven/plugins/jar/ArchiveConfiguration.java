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
 * @since 4.0.0-beta-3
 */
public class ArchiveConfiguration {

    private boolean compress = true;

    private boolean addMavenDescriptor = true;

    private Path manifestFile;

    private ManifestConfig manifest;

    private Map<String, String> manifestEntries = new LinkedHashMap<>();

    private List<ManifestSection> manifestSections = new ArrayList<>();

    private boolean forced = true;

    private Path pomPropertiesFile;

    /**
     * Manifest configuration, provided through the {@code <archive><manifest>} plugin parameter.
     */
    public static class ManifestConfig {

        private String mainClass;

        private boolean addClasspath;

        private String classpathPrefix = "";

        private boolean addDefaultEntries = true;

        private boolean addBuildEnvironmentEntries;

        private boolean addDefaultSpecificationEntries;

        private boolean addDefaultImplementationEntries;

        private String classpathLayoutType = "simple";

        private String customClasspathLayout;

        private boolean useUniqueVersions = true;

        /** {@return the main class to set in the {@code Main-Class} manifest attribute} */
        public String getMainClass() {
            return mainClass;
        }

        /** Sets the main class. */
        public void setMainClass(String mainClass) {
            this.mainClass = mainClass;
        }

        /** {@return whether to add a {@code Class-Path} manifest entry} */
        public boolean isAddClasspath() {
            return addClasspath;
        }

        /** Sets whether to add a {@code Class-Path} manifest entry. */
        public void setAddClasspath(boolean addClasspath) {
            this.addClasspath = addClasspath;
        }

        /** {@return the prefix to prepend to each entry in {@code Class-Path}} */
        public String getClasspathPrefix() {
            return classpathPrefix;
        }

        /** Sets the classpath prefix. */
        public void setClasspathPrefix(String classpathPrefix) {
            this.classpathPrefix = classpathPrefix;
        }

        /** {@return whether to add default manifest entries ({@code Created-By}, {@code Build-Jdk-Spec})} */
        public boolean isAddDefaultEntries() {
            return addDefaultEntries;
        }

        /** Sets whether to add default entries. */
        public void setAddDefaultEntries(boolean addDefaultEntries) {
            this.addDefaultEntries = addDefaultEntries;
        }

        /** {@return whether to add build environment entries ({@code Built-By}, {@code Build-Jdk})} */
        public boolean isAddBuildEnvironmentEntries() {
            return addBuildEnvironmentEntries;
        }

        /** Sets whether to add build environment entries. */
        public void setAddBuildEnvironmentEntries(boolean addBuildEnvironmentEntries) {
            this.addBuildEnvironmentEntries = addBuildEnvironmentEntries;
        }

        /** {@return whether to add specification entries} */
        public boolean isAddDefaultSpecificationEntries() {
            return addDefaultSpecificationEntries;
        }

        /** Sets whether to add specification entries. */
        public void setAddDefaultSpecificationEntries(boolean addDefaultSpecificationEntries) {
            this.addDefaultSpecificationEntries = addDefaultSpecificationEntries;
        }

        /** {@return whether to add implementation entries} */
        public boolean isAddDefaultImplementationEntries() {
            return addDefaultImplementationEntries;
        }

        /** Sets whether to add implementation entries. */
        public void setAddDefaultImplementationEntries(boolean addDefaultImplementationEntries) {
            this.addDefaultImplementationEntries = addDefaultImplementationEntries;
        }

        /** {@return the classpath layout type: {@code simple}, {@code repository}, or {@code custom}} */
        public String getClasspathLayoutType() {
            return classpathLayoutType;
        }

        /** Sets the classpath layout type. */
        public void setClasspathLayoutType(String classpathLayoutType) {
            this.classpathLayoutType = classpathLayoutType;
        }

        /** {@return the custom classpath layout expression (used when layout type is {@code custom})} */
        public String getCustomClasspathLayout() {
            return customClasspathLayout;
        }

        /** Sets the custom classpath layout expression. */
        public void setCustomClasspathLayout(String customClasspathLayout) {
            this.customClasspathLayout = customClasspathLayout;
        }

        /** {@return whether to use unique snapshot versions in the classpath} */
        public boolean isUseUniqueVersions() {
            return useUniqueVersions;
        }

        /** Sets whether to use unique snapshot versions. */
        public void setUseUniqueVersions(boolean useUniqueVersions) {
            this.useUniqueVersions = useUniqueVersions;
        }
    }

    /**
     * An additional manifest section, provided through the {@code <archive><manifestSections>} plugin parameter.
     */
    public static class ManifestSection {

        private String name;

        private Map<String, String> manifestEntries = new LinkedHashMap<>();

        /** {@return the section name} */
        public String getName() {
            return name;
        }

        /** Sets the section name. */
        public void setName(String name) {
            this.name = name;
        }

        /** {@return the entries in this section} */
        public Map<String, String> getManifestEntries() {
            return manifestEntries;
        }

        /** Sets the entries in this section. */
        public void setManifestEntries(Map<String, String> manifestEntries) {
            this.manifestEntries = manifestEntries;
        }

        /** Adds a single entry to this section. */
        public void addManifestEntry(String key, String value) {
            manifestEntries.put(key, value);
        }

        /** {@return whether this section has no entries} */
        public boolean isManifestEntriesEmpty() {
            return manifestEntries.isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** {@return whether to compress the archive} */
    public boolean isCompress() {
        return compress;
    }

    /** Sets whether to compress the archive. */
    public void setCompress(boolean compress) {
        this.compress = compress;
    }

    /** {@return whether to add the Maven descriptor ({@code pom.xml} and {@code pom.properties})} */
    public boolean isAddMavenDescriptor() {
        return addMavenDescriptor;
    }

    /** Sets whether to add the Maven descriptor. */
    public void setAddMavenDescriptor(boolean addMavenDescriptor) {
        this.addMavenDescriptor = addMavenDescriptor;
    }

    /** {@return the path to an external manifest file, or {@code null} if none} */
    public Path getManifestFile() {
        return manifestFile;
    }

    /** Sets the external manifest file. */
    public void setManifestFile(Path manifestFile) {
        this.manifestFile = manifestFile;
    }

    /**
     * {@return the manifest configuration}
     * Lazily initialised with defaults.
     */
    public ManifestConfig getManifest() {
        if (manifest == null) {
            manifest = new ManifestConfig();
        }
        return manifest;
    }

    /** Sets the manifest configuration. */
    public void setManifest(ManifestConfig manifest) {
        this.manifest = manifest;
    }

    /** {@return whether the extra manifest entries map is empty} */
    public boolean isManifestEntriesEmpty() {
        return manifestEntries.isEmpty();
    }

    /** {@return the extra manifest entries to add to the main section} */
    public Map<String, String> getManifestEntries() {
        return manifestEntries;
    }

    /** Sets the extra manifest entries. */
    public void setManifestEntries(Map<String, String> manifestEntries) {
        this.manifestEntries = manifestEntries;
    }

    /** Adds a single extra manifest entry. */
    public void addManifestEntry(String key, String value) {
        manifestEntries.put(key, value);
    }

    /** {@return whether the manifest sections list is empty} */
    public boolean isManifestSectionsEmpty() {
        return manifestSections.isEmpty();
    }

    /** {@return the extra manifest sections} */
    public List<ManifestSection> getManifestSections() {
        return manifestSections;
    }

    /** Sets the extra manifest sections. */
    public void setManifestSections(List<ManifestSection> manifestSections) {
        this.manifestSections = manifestSections;
    }

    /**
     * {@return whether archive recreation is forced (default: {@code true})}
     * When {@code false}, the archiver may skip recreation if the output is up-to-date.
     */
    public boolean isForced() {
        return forced;
    }

    /** Sets whether archive recreation is forced. */
    public void setForced(boolean forced) {
        this.forced = forced;
    }

    /** {@return the path to a custom {@code pom.properties} file, or {@code null} for the default} */
    public Path getPomPropertiesFile() {
        return pomPropertiesFile;
    }

    /** Sets the path to a custom {@code pom.properties} file. */
    public void setPomPropertiesFile(Path pomPropertiesFile) {
        this.pomPropertiesFile = pomPropertiesFile;
    }
}
