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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.Interpolator;
import org.apache.maven.api.xml.XmlNode;

/**
 * Builds a {@link Manifest} from the Maven project and plugin configuration.
 */
final class ManifestBuilder {

    static final String CLASSPATH_LAYOUT_TYPE_SIMPLE = "simple";
    private static final String CLASSPATH_LAYOUT_TYPE_REPOSITORY = "repository";
    private static final String CLASSPATH_LAYOUT_TYPE_CUSTOM = "custom";

    // Attribute name constants not predefined in java.util.jar.Attributes.Name
    private static final Attributes.Name JAVA_VERSION = new Attributes.Name("Java-Version");
    private static final Attributes.Name BUILD_JDK_SPEC = new Attributes.Name("Build-Jdk-Spec");
    private static final Attributes.Name BUILD_JDK = new Attributes.Name("Build-Jdk");
    private static final Attributes.Name BUILT_BY = new Attributes.Name("Built-By");

    private static final String SIMPLE_LAYOUT =
            "${artifact.artifactId}-${artifact.version}${dashClassifier?}.${artifact.extension}";
    private static final String SIMPLE_LAYOUT_NONUNIQUE =
            "${artifact.artifactId}-${artifact.baseVersion}${dashClassifier?}.${artifact.extension}";
    private static final String REPOSITORY_LAYOUT =
            "${artifact.groupIdPath}/${artifact.artifactId}/${artifact.baseVersion}/${artifact.artifactId}"
                    + "-${artifact.version}${dashClassifier?}.${artifact.extension}";
    private static final String REPOSITORY_LAYOUT_NONUNIQUE =
            "${artifact.groupIdPath}/${artifact.artifactId}/${artifact.baseVersion}/${artifact.artifactId}"
                    + "-${artifact.baseVersion}${dashClassifier?}.${artifact.extension}";

    private final Session session;
    private final Project project;
    private final ArchiveConfiguration archive;

    ManifestBuilder(Session session, Project project, ArchiveConfiguration archive) {
        this.session = session;
        this.project = project;
        this.archive = archive;
    }

    /**
     * Builds the manifest.
     *
     * @return the manifest, never {@code null}
     * @throws MojoException if the manifest cannot be built
     */
    Manifest build() {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        // Manifest-Version must be present or java.util.jar.Manifest.write() silently drops all main attributes.
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");

        ArchiveConfiguration.ManifestConfig config = archive.manifest;
        if (config.addDefaultEntries) {
            addDefaultEntries(main);
        }

        if (config.addBuildEnvironmentEntries) {
            addBuildEnvironmentEntries(main);
        }

        if (config.addClasspath) {
            String classpath = buildClasspath();
            if (!classpath.isEmpty()) {
                main.put(Attributes.Name.CLASS_PATH, classpath);
            }
        }

        if (config.addDefaultSpecificationEntries) {
            addSpecificationEntries(main);
        }

        if (config.addDefaultImplementationEntries) {
            addImplementationEntries(main);
        }

        putIfAbsent(main, Attributes.Name.MAIN_CLASS, config.mainClass);

        // Merge any extra entries from <archive><manifestEntries>
        for (Map.Entry<String, String> entry : archive.manifestEntries.entrySet()) {
            String key = entry.getKey();
            String value = sanitize(entry.getValue());
            if (key.equals(Attributes.Name.CLASS_PATH.toString())) {
                // User-supplied value goes first (takes precedence), then programmatic one.
                main.merge(
                        Attributes.Name.CLASS_PATH, value, (existing, supplied) -> (String) supplied + ' ' + existing);
            } else {
                main.putValue(key, value);
            }
        }

        // Extra manifest sections from <archive><manifestSections>
        for (ArchiveConfiguration.ManifestSection section : archive.manifestSections) {
            if (section.name == null || section.name.isBlank()) {
                throw new MojoException("Each <manifestSection> must declare a non-empty <name>.");
            }
            Attributes attrs = new Attributes();
            for (Map.Entry<String, String> e : section.manifestEntries.entrySet()) {
                attrs.putValue(e.getKey(), sanitize(e.getValue()));
            }
            manifest.getEntries().put(section.name, attrs);
        }

        return manifest;
    }

    private void addDefaultEntries(Attributes main) {
        // Java-Version reflects the compiler target (e.g. "11"), as set by maven-compiler-plugin.
        putIfAbsent(main, JAVA_VERSION, discoverJavaRelease(project.getModel()));
    }

    private void addBuildEnvironmentEntries(Attributes main) {
        // Build-Jdk-Spec reflects the runtime JDK specification version.
        putIfAbsent(main, BUILD_JDK_SPEC, System.getProperty("java.specification.version"));
        putIfAbsent(main, BUILD_JDK, System.getProperty("java.version"));
        putIfAbsent(main, BUILT_BY, System.getProperty("user.name"));
    }

    private void addSpecificationEntries(Attributes main) {
        putIfAbsent(
                main, Attributes.Name.SPECIFICATION_TITLE, project.getModel().getName());
        putIfAbsent(main, Attributes.Name.SPECIFICATION_VERSION, specificationVersion());
        var organization = project.getModel().getOrganization();
        if (organization != null) {
            putIfAbsent(main, Attributes.Name.SPECIFICATION_VENDOR, organization.getName());
        }
    }

    private void addImplementationEntries(Attributes main) {
        putIfAbsent(
                main, Attributes.Name.IMPLEMENTATION_TITLE, project.getModel().getName());
        putIfAbsent(main, Attributes.Name.IMPLEMENTATION_VERSION, project.getVersion());
        var organization = project.getModel().getOrganization();
        if (organization != null) {
            putIfAbsent(main, Attributes.Name.IMPLEMENTATION_VENDOR, organization.getName());
        }
        putIfAbsent(main, Attributes.Name.IMPLEMENTATION_VENDOR_ID, project.getGroupId());
        putIfAbsent(main, Attributes.Name.IMPLEMENTATION_URL, project.getModel().getUrl());
    }

    /**
     * Resolves the runtime dependencies and builds the {@code Class-Path} manifest value.
     */
    private String buildClasspath() {
        DependencyResolverResult result =
                session.getService(DependencyResolver.class).resolve(session, project, PathScope.MAIN_RUNTIME);

        Interpolator interpolator = session.getService(Interpolator.class);
        ArchiveConfiguration.ManifestConfig config = archive.manifest;
        String prefix = config.classpathPrefix;
        String layoutType = config.classpathLayoutType;
        String customLayout = config.customClasspathLayout;
        boolean uniqueVersions = config.useUniqueVersions;

        var classpath = new StringBuilder();
        String layout = resolveLayout(layoutType, customLayout, uniqueVersions);
        for (Map.Entry<Dependency, Path> entry : result.getDependencies().entrySet()) {
            Path artifactFile = entry.getValue();
            if (artifactFile == null || !Files.isRegularFile(artifactFile)) {
                continue;
            }
            Dependency dep = entry.getKey();
            if (!classpath.isEmpty()) {
                classpath.append(' ');
            }
            if (prefix != null) {
                classpath.append(prefix);
            }
            String interpolated = interpolator.interpolate(layout, key -> resolveArtifactExpression(dep, key));
            if (interpolated != null) {
                classpath.append(interpolated);
            } else {
                classpath.append(artifactFile.getFileName());
            }
        }
        return classpath.toString();
    }

    /**
     * Returns the layout template to use based on the configuration.
     */
    private static String resolveLayout(String layoutType, String customLayout, boolean uniqueVersions) {
        return switch (layoutType == null ? CLASSPATH_LAYOUT_TYPE_SIMPLE : layoutType) {
            case CLASSPATH_LAYOUT_TYPE_SIMPLE -> uniqueVersions ? SIMPLE_LAYOUT : SIMPLE_LAYOUT_NONUNIQUE;
            case CLASSPATH_LAYOUT_TYPE_REPOSITORY -> uniqueVersions ? REPOSITORY_LAYOUT : REPOSITORY_LAYOUT_NONUNIQUE;
            case CLASSPATH_LAYOUT_TYPE_CUSTOM -> {
                if (customLayout == null) {
                    throw new MojoException("Classpath layout type 'custom' requires a <customClasspathLayout> value.");
                }
                yield customLayout;
            }
            default -> throw new MojoException("Unknown classpath layout type: '" + layoutType + "'.");
        };
    }

    /**
     * Resolves a single interpolation key for a dependency in the classpath layout.
     * Supports the {@code artifact.*} prefix family used by maven-archiver.
     */
    private static String resolveArtifactExpression(Dependency dep, String key) {
        // Strip "artifact." prefix
        String k = key.startsWith("artifact.") ? key.substring("artifact.".length()) : key;
        return switch (k) {
            case "artifactId" -> dep.getArtifactId();
            case "groupId" -> dep.getGroupId();
            case "groupIdPath" -> dep.getGroupId().replace('.', '/');
            case "version" -> dep.getVersion().toString();
            case "baseVersion" -> dep.getBaseVersion().toString();
            case "extension" -> dep.getType().getExtension();
            case "classifier" -> dep.getClassifier() != null ? dep.getClassifier() : "";
            case "dashClassifier", "dashClassifier?" -> {
                String c = dep.getClassifier();
                yield (c != null && !c.isEmpty()) ? "-" + c : "";
            }
            case "type" -> dep.getType().id();
            default -> null;
        };
    }

    /**
     * Puts a value in the manifest attributes only if the key is not already present
     * in the attributes or in the user-supplied extra entries ({@link ArchiveConfiguration#manifestEntries}),
     * and only if the value is non-null and non-blank.
     */
    private void putIfAbsent(Attributes attrs, Attributes.Name key, String value) {
        if (value != null && !value.isBlank() && !archive.manifestEntries.containsKey(key.toString())) {
            attrs.merge(key, sanitize(value), (existing, v) -> existing);
        }
    }

    /**
     * Sanitizes a manifest attribute value by replacing line terminators with spaces,
     * matching the behaviour of maven-archiver.
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * Returns the specification version from the project version, keeping only the {@code major.minor} part.
     */
    private String specificationVersion() {
        String projectVersion = project.getVersion();
        if (projectVersion == null) {
            return null;
        }
        // Keep only "major.minor" (digits and dots up to the second dot or first non-digit).
        var specificationVersion = new StringBuilder();
        int dots = 0;
        for (int i = 0; i < projectVersion.length(); i++) {
            char c = projectVersion.charAt(i);
            if (c == '.') {
                dots++;
                if (dots == 2) {
                    break;
                }
                specificationVersion.append(c);
            } else if (Character.isDigit(c)) {
                specificationVersion.append(c);
            } else {
                break;
            }
        }
        return specificationVersion.isEmpty() ? null : specificationVersion.toString();
    }

    // -----------------------------------------------------------------------
    // Inlined from org.apache.maven.shared.archiver.BuildHelper
    // -----------------------------------------------------------------------

    private static final Set<String> LEGACY_JDK_VERSIONS = Set.of("1.5", "1.6", "1.7", "1.8");

    /**
     * Tries to determine the target Java release from the compiler plugin configuration.
     * Inlined from {@code org.apache.maven.shared.archiver.BuildHelper#discoverJavaRelease}.
     */
    private static String discoverJavaRelease(Model model) {
        Plugin compiler = getPlugin(model, "org.apache.maven.plugins:maven-compiler-plugin");
        String jdk = getPluginParameter(model, compiler, "release", "maven.compiler.release");
        if (jdk == null) {
            jdk = getPluginParameter(model, compiler, "target", "maven.compiler.target");
        }
        // Normalize 1.5 → 5, 1.6 → 6, etc.
        if (jdk != null && LEGACY_JDK_VERSIONS.contains(jdk)) {
            return jdk.substring(2);
        }
        return jdk;
    }

    private static Plugin getPlugin(Model model, String pluginGa) {
        var build = model.getBuild();
        if (build != null) {
            var plugins = build.getPluginsAsMap();
            if (plugins != null) {
                Plugin plugin = plugins.get(pluginGa);
                if (plugin != null) {
                    return plugin;
                }
            }
            var pluginManagement = build.getPluginManagement();
            if (pluginManagement != null) {
                var mgmtPlugins = pluginManagement.getPluginsAsMap();
                if (mgmtPlugins != null) {
                    return mgmtPlugins.get(pluginGa);
                }
            }
        }
        return null;
    }

    private static String getPluginParameter(Model model, Plugin plugin, String parameter, String property) {
        if (plugin != null) {
            XmlNode configuration = plugin.getConfiguration();
            if (configuration != null) {
                XmlNode child = configuration.child(parameter);
                if (child != null) {
                    return child.value();
                }
            }
        }
        return model.getProperties().get(property);
    }
}
