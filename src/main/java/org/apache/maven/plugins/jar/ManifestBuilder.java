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
 * Builds a {@link Manifest} from the Maven project and plugin configuration,
 * without depending on {@code maven-archiver} or {@code plexus-archiver}.
 *
 * <p>This class replaces the use of {@code MavenArchiver#getManifest}
 * in this plugin, allowing removal of the {@code maven-archiver} and {@code plexus-archiver} dependencies.</p>
 */
final class ManifestBuilder {

    static final String CLASSPATH_LAYOUT_TYPE_SIMPLE = "simple";
    private static final String CLASSPATH_LAYOUT_TYPE_REPOSITORY = "repository";
    private static final String CLASSPATH_LAYOUT_TYPE_CUSTOM = "custom";

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
        ArchiveConfiguration.ManifestConfig config = archive.manifest;
        Map<String, String> extraEntries = archive.manifestEntries;

        Manifest manifest = buildManifest(config, extraEntries);

        // Merge any extra entries from <archive><manifestEntries>
        Attributes main = manifest.getMainAttributes();
        for (Map.Entry<String, String> entry : extraEntries.entrySet()) {
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

    private Manifest buildManifest(ArchiveConfiguration.ManifestConfig config, Map<String, String> extraEntries) {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        // Manifest-Version must be present or java.util.jar.Manifest.write() silently drops all main attributes.
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");

        if (config.addDefaultEntries) {
            addDefaultEntries(main, extraEntries);
        }

        if (config.addBuildEnvironmentEntries) {
            addBuildEnvironmentEntries(main, extraEntries);
        }

        if (config.addClasspath) {
            String classpath = buildClasspath(config);
            if (!classpath.isEmpty()) {
                main.putValue(Attributes.Name.CLASS_PATH.toString(), classpath);
            }
        }

        if (config.addDefaultSpecificationEntries) {
            addSpecificationEntries(main, extraEntries);
        }

        if (config.addDefaultImplementationEntries) {
            addImplementationEntries(main, extraEntries);
        }

        String mainClass = config.mainClass;
        if (mainClass != null && !mainClass.isEmpty()) {
            putIfAbsent(main, extraEntries, Attributes.Name.MAIN_CLASS.toString(), mainClass);
        }

        return manifest;
    }

    private void addDefaultEntries(Attributes main, Map<String, String> extra) {
        // Java-Version reflects the compiler target (e.g. "11"), as set by maven-compiler-plugin.
        String jdk = discoverJavaRelease(project.getModel());
        if (jdk != null) {
            putIfAbsent(main, extra, "Java-Version", jdk);
        }
    }

    private void addBuildEnvironmentEntries(Attributes main, Map<String, String> extra) {
        // Build-Jdk-Spec reflects the runtime JDK specification version.
        String specVersion = System.getProperty("java.specification.version");
        if (specVersion != null) {
            putIfAbsent(main, extra, "Build-Jdk-Spec", specVersion);
        }
        String javaVersion = System.getProperty("java.version");
        if (javaVersion != null) {
            putIfAbsent(main, extra, "Build-Jdk", javaVersion);
        }
        String user = System.getProperty("user.name");
        if (user != null) {
            putIfAbsent(main, extra, "Built-By", user);
        }
    }

    private void addSpecificationEntries(Attributes main, Map<String, String> extra) {
        putIfAbsentNonEmpty(
                main,
                extra,
                Attributes.Name.SPECIFICATION_TITLE.toString(),
                project.getModel().getName());
        String version = specificationVersion();
        if (version != null) {
            putIfAbsent(main, extra, Attributes.Name.SPECIFICATION_VERSION.toString(), version);
        }
        var organization = project.getModel().getOrganization();
        if (organization != null) {
            putIfAbsentNonEmpty(main, extra, Attributes.Name.SPECIFICATION_VENDOR.toString(), organization.getName());
        }
    }

    private void addImplementationEntries(Attributes main, Map<String, String> extra) {
        putIfAbsentNonEmpty(
                main,
                extra,
                Attributes.Name.IMPLEMENTATION_TITLE.toString(),
                project.getModel().getName());
        putIfAbsent(main, extra, Attributes.Name.IMPLEMENTATION_VERSION.toString(), project.getVersion());
        var organization = project.getModel().getOrganization();
        if (organization != null) {
            putIfAbsentNonEmpty(main, extra, Attributes.Name.IMPLEMENTATION_VENDOR.toString(), organization.getName());
        }
        putIfAbsent(main, extra, "Implementation-Vendor-Id", project.getGroupId());
        putIfAbsentNonEmpty(
                main, extra, "Implementation-URL", project.getModel().getUrl());
    }

    /**
     * Resolves the runtime dependencies and builds the {@code Class-Path} manifest value.
     */
    private String buildClasspath(ArchiveConfiguration.ManifestConfig config) {
        DependencyResolverResult result =
                session.getService(DependencyResolver.class).resolve(session, project, PathScope.MAIN_RUNTIME);

        Interpolator interpolator = session.getService(Interpolator.class);
        String prefix = config.classpathPrefix;
        String layoutType = config.classpathLayoutType;
        String customLayout = config.customClasspathLayout;
        boolean uniqueVersions = config.useUniqueVersions;

        var classpath = new StringBuilder();
        String layout = resolveLayout(layoutType, customLayout, uniqueVersions);
        for (Map.Entry<Dependency, Path> entry : result.getDependencies().entrySet()) {
            Path artifactFile = entry.getValue();
            if (artifactFile == null || !Files.isRegularFile(artifactFile.toAbsolutePath())) {
                continue;
            }
            Dependency dep = entry.getKey();
            if (!classpath.isEmpty()) {
                classpath.append(' ');
            }
            if (!prefix.isEmpty()) {
                classpath.append(prefix);
            }
            String interpolated = interpolator.interpolate(layout, key -> resolveArtifactExpression(dep, key));
            if (interpolated != null) {
                classpath.append(interpolated);
            } else {
                classpath.append(artifactFile.getFileName().toString());
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
     * (either in the attributes or in the user-supplied extra entries map).
     */
    private static void putIfAbsent(Attributes attrs, Map<String, String> extra, String key, String value) {
        if (!extra.containsKey(key)) {
            attrs.merge(new Attributes.Name(key), sanitize(value), (existing, v) -> existing);
        }
    }

    /**
     * Like {@link #putIfAbsent}, but also skips null or empty values.
     */
    private static void putIfAbsentNonEmpty(Attributes attrs, Map<String, String> extra, String key, String value) {
        if (value != null && !value.isEmpty()) {
            putIfAbsent(attrs, extra, key, value);
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
        String v = project.getVersion();
        if (v == null) {
            return null;
        }
        // Keep only "major.minor" (digits and dots up to the second dot or first non-digit).
        var sb = new StringBuilder();
        int dots = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '.') {
                if (++dots == 2) {
                    break;
                }
                sb.append(c);
            } else if (Character.isDigit(c)) {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.isEmpty() ? null : sb.toString();
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
