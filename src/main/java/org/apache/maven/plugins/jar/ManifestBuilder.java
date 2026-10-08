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
import java.util.List;
import java.util.Map;
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
 * <p>This class replaces the use of {@link org.apache.maven.shared.archiver.MavenArchiver#getManifest}
 * in this plugin, allowing removal of the {@code maven-archiver} and {@code plexus-archiver} dependencies.</p>
 */
final class ManifestBuilder {

    private static final String CLASSPATH_LAYOUT_TYPE_SIMPLE = "simple";
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

    /**
     * The {@code Created-By} value to use, e.g. {@code "Maven JAR Plugin"}.
     */
    private String createdBy;

    /**
     * Whether to add the {@code Build-Jdk-Spec} entry when
     * {@link ManifestConfiguration#isAddBuildEnvironmentEntries()} is {@code true}.
     */
    private boolean buildJdkSpecDefaultEntry = true;

    ManifestBuilder(Session session, Project project, ArchiveConfiguration archive) {
        this.session = session;
        this.project = project;
        this.archive = archive;
    }

    void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    void setBuildJdkSpecDefaultEntry(boolean buildJdkSpecDefaultEntry) {
        this.buildJdkSpecDefaultEntry = buildJdkSpecDefaultEntry;
    }

    /**
     * Builds the manifest.
     *
     * @return the manifest, never {@code null}
     * @throws MojoException if the manifest cannot be built
     */
    Manifest build() {
        ArchiveConfiguration.ManifestConfig config = archive.getManifest();
        Map<String, String> extraEntries = archive.isManifestEntriesEmpty() ? Map.of() : archive.getManifestEntries();

        Manifest manifest = buildManifest(config, extraEntries);

        // Merge any extra entries from <archive><manifestEntries>
        if (!extraEntries.isEmpty()) {
            Attributes main = manifest.getMainAttributes();
            for (Map.Entry<String, String> entry : extraEntries.entrySet()) {
                String key = entry.getKey();
                String value = sanitize(entry.getValue());
                if (key.equals(Attributes.Name.CLASS_PATH.toString()) && main.getValue(key) != null) {
                    // User-supplied value goes first (takes precedence), then programmatic one.
                    main.putValue(key, value + " " + main.getValue(key));
                } else {
                    main.putValue(key, value);
                }
            }
        }

        // Extra manifest sections from <archive><manifestSections>
        if (!archive.isManifestSectionsEmpty()) {
            for (ArchiveConfiguration.ManifestSection section : archive.getManifestSections()) {
                Attributes attrs = new Attributes();
                if (!section.isManifestEntriesEmpty()) {
                    for (Map.Entry<String, String> e :
                            section.getManifestEntries().entrySet()) {
                        attrs.putValue(e.getKey(), sanitize(e.getValue()));
                    }
                }
                manifest.getEntries().put(section.getName(), attrs);
            }
        }

        return manifest;
    }

    private Manifest buildManifest(ArchiveConfiguration.ManifestConfig config, Map<String, String> extraEntries) {
        Manifest m = new Manifest();
        Attributes main = m.getMainAttributes();
        // Manifest-Version is required; java.util.jar.Manifest does not add it automatically.
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");

        if (config.isAddDefaultEntries()) {
            addDefaultEntries(main, extraEntries);
        }

        if (config.isAddBuildEnvironmentEntries()) {
            addBuildEnvironmentEntries(main, extraEntries);
        }

        if (config.isAddClasspath()) {
            String classpath = buildClasspath(config);
            if (!classpath.isEmpty()) {
                main.putValue(Attributes.Name.CLASS_PATH.toString(), classpath);
            }
        }

        if (config.isAddDefaultSpecificationEntries()) {
            addSpecificationEntries(main, extraEntries);
        }

        if (config.isAddDefaultImplementationEntries()) {
            addImplementationEntries(main, extraEntries);
        }

        String mainClass = config.getMainClass();
        if (mainClass != null && !mainClass.isEmpty()) {
            putIfAbsent(main, extraEntries, Attributes.Name.MAIN_CLASS.toString(), mainClass);
        }

        return m;
    }

    private void addDefaultEntries(Attributes main, Map<String, String> extra) {
        String by = createdBy;
        if (by == null) {
            by = "Maven JAR Plugin";
        }
        putIfAbsent(main, extra, "Created-By", by);

        String jdk = discoverJavaRelease(project.getModel());
        if (jdk != null) {
            putIfAbsent(main, extra, "Build-Jdk-Spec", jdk);
        }
    }

    private void addBuildEnvironmentEntries(Attributes main, Map<String, String> extra) {
        if (buildJdkSpecDefaultEntry) {
            String javaVersion = System.getProperty("java.version");
            if (javaVersion != null) {
                putIfAbsent(main, extra, "Build-Jdk", javaVersion);
            }
        }
        String user = System.getProperty("user.name");
        if (user != null) {
            putIfAbsent(main, extra, "Built-By", user);
        }
    }

    private void addSpecificationEntries(Attributes main, Map<String, String> extra) {
        String title = project.getModel().getName();
        if (title != null && !title.isEmpty()) {
            putIfAbsent(main, extra, Attributes.Name.SPECIFICATION_TITLE.toString(), title);
        }
        String version = specificationVersion();
        if (version != null) {
            putIfAbsent(main, extra, Attributes.Name.SPECIFICATION_VERSION.toString(), version);
        }
        String vendor = project.getModel().getOrganization() != null
                ? project.getModel().getOrganization().getName()
                : null;
        if (vendor != null) {
            putIfAbsent(main, extra, Attributes.Name.SPECIFICATION_VENDOR.toString(), vendor);
        }
    }

    private void addImplementationEntries(Attributes main, Map<String, String> extra) {
        String title = project.getModel().getName();
        if (title != null && !title.isEmpty()) {
            putIfAbsent(main, extra, Attributes.Name.IMPLEMENTATION_TITLE.toString(), title);
        }
        putIfAbsent(main, extra, Attributes.Name.IMPLEMENTATION_VERSION.toString(), project.getVersion());
        String vendor = project.getModel().getOrganization() != null
                ? project.getModel().getOrganization().getName()
                : null;
        if (vendor != null) {
            putIfAbsent(main, extra, Attributes.Name.IMPLEMENTATION_VENDOR.toString(), vendor);
        }
        putIfAbsent(main, extra, "Implementation-Vendor-Id", project.getGroupId());
        String url = project.getModel().getUrl();
        if (url != null && !url.isEmpty()) {
            putIfAbsent(main, extra, "Implementation-URL", url);
        }
    }

    /**
     * Resolves the runtime dependencies and builds the {@code Class-Path} manifest value.
     */
    private String buildClasspath(ArchiveConfiguration.ManifestConfig config) {
        DependencyResolverResult result =
                session.getService(DependencyResolver.class).resolve(session, project, PathScope.MAIN_RUNTIME);

        Interpolator interpolator = session.getService(Interpolator.class);
        String prefix = config.getClasspathPrefix();
        String layoutType = config.getClasspathLayoutType();
        String customLayout = config.getCustomClasspathLayout();
        boolean uniqueVersions = config.isUseUniqueVersions();

        var classpath = new StringBuilder();
        for (Map.Entry<Dependency, Path> entry : result.getDependencies().entrySet()) {
            Path artifactFile = entry.getValue();
            if (artifactFile == null || !java.nio.file.Files.isRegularFile(artifactFile.toAbsolutePath())) {
                continue;
            }
            Dependency dep = entry.getKey();
            if (!classpath.isEmpty()) {
                classpath.append(' ');
            }
            if (prefix != null) {
                classpath.append(prefix);
            }
            String layout = resolveLayout(layoutType, customLayout, uniqueVersions);
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
        if (layoutType == null || CLASSPATH_LAYOUT_TYPE_SIMPLE.equals(layoutType)) {
            return uniqueVersions ? SIMPLE_LAYOUT : SIMPLE_LAYOUT_NONUNIQUE;
        } else if (CLASSPATH_LAYOUT_TYPE_REPOSITORY.equals(layoutType)) {
            return uniqueVersions ? REPOSITORY_LAYOUT : REPOSITORY_LAYOUT_NONUNIQUE;
        } else if (CLASSPATH_LAYOUT_TYPE_CUSTOM.equals(layoutType)) {
            if (customLayout == null) {
                throw new MojoException(
                        "Classpath layout type 'custom' requires a <customClasspathLayout> value.");
            }
            return customLayout;
        } else {
            throw new MojoException("Unknown classpath layout type: '" + layoutType + "'.");
        }
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
        if (!extra.containsKey(key) && attrs.getValue(key) == null) {
            attrs.putValue(key, sanitize(value));
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

    /**
     * Returns the list of values from the given array, or an empty list if the array is null.
     */
    static List<String> asList(String[] elements) {
        return elements == null ? List.of() : new ArrayList<>(List.of(elements));
    }

    // -----------------------------------------------------------------------
    // Inlined from org.apache.maven.shared.archiver.BuildHelper
    // -----------------------------------------------------------------------

    private static final java.util.Set<String> LEGACY_JDK_VERSIONS = java.util.Set.of("1.5", "1.6", "1.7", "1.8");

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
                Plugin p = plugins.get(pluginGa);
                if (p != null) {
                    return p;
                }
            }
            var mgmt = build.getPluginManagement();
            if (mgmt != null) {
                var mgmtPlugins = mgmt.getPluginsAsMap();
                if (mgmtPlugins != null) {
                    return mgmtPlugins.get(pluginGa);
                }
            }
        }
        return null;
    }

    private static String getPluginParameter(Model model, Plugin plugin, String parameter, String property) {
        if (plugin != null) {
            XmlNode conf = plugin.getConfiguration();
            if (conf != null) {
                XmlNode child = conf.child(parameter);
                if (child != null) {
                    return child.value();
                }
            }
        }
        return model.getProperties().get(property);
    }
}
