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

import javax.xml.stream.XMLStreamException;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.JavaPathType;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.DependencyCoordinatesFactory;
import org.apache.maven.api.services.DependencyCoordinatesFactoryRequest;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.ModelBuilderException;
import org.apache.maven.model.v4.MavenStaxWriter;

/**
 * A mapper from Maven model dependencies to Java module names.
 * A single instance of this class is created for a Maven project,
 * then shared by all {@link ForModule} instances (one per module to archive).
 */
final class PomDerivation {
    /**
     * Whether to expand the list of transitive dependencies in the generated <abbr>POM</abbr>.
     */
    private static final boolean EXPAND_TRANSITIVE = false;

    /**
     * Copy of {@link AbstractJarMojo#session}.
     */
    private final Session session;

    /**
     * The project model, which includes dependencies of all modules.
     */
    private final Model projectModel;

    /**
     * The factory to use for creating temporary {@link DependencyCoordinates} instances.
     */
    private final DependencyCoordinatesFactory coordinateFactory;

    /**
     * Provide module descriptors from module names.
     */
    private final ModuleFinder moduleFinder;

    /**
     * Module references from paths to the <abbr>JAR</abbr> file or root directory.
     */
    private final Map<URI, ModuleReference> fromURI;

    /**
     * Module names associated to Maven dependencies.
     * This map contains {@link DependencyCoordinates#getId()} as keys and module references as values.
     * This is used for detecting which dependencies are really used according {@code module-info.class}.
     *
     * @todo The keys should be instances of {@link DependencyCoordinates}. Unfortunately, as of Maven 4.0.0-rc-5
     *       that interface does not define the {@code equals} and {@code hashCode} contracts.
     */
    private final Map<String, ModuleReference> fromDependency;

    /**
     * Modules that are built by the project. Keys are module names.
     */
    private final Map<String, Dependency> builtModules;

    /**
     * Creates a new mapper from Maven dependency to module name.
     *
     * @param mojo the enclosing <abbr>MOJO</abbr>
     * @param moduleRoots paths to root directories of each module to archive in a module hierarchy
     * @throws IOException if an I/O error occurred while fetching dependencies
     * @throws MavenException if an error occurred while fetching dependencies for a reason other than I/O.
     */
    PomDerivation(final AbstractJarMojo mojo, final List<Path> moduleRoots) throws IOException {
        this.session = mojo.getSession();
        projectModel = mojo.getProject().getModel();
        coordinateFactory = session.getService(DependencyCoordinatesFactory.class);
        DependencyResolver resolver = session.getService(DependencyResolver.class);
        DependencyResolverResult result = resolver.resolve(DependencyResolverRequest.builder()
                .session(session)
                .project(mojo.getProject())
                .requestType(DependencyResolverRequest.RequestType.RESOLVE)
                .pathScope(mojo.getDependencyScope())
                .pathTypeFilter(Set.of(JavaPathType.MODULES, JavaPathType.CLASSES))
                .build());

        rethrow(result);
        final Map<org.apache.maven.api.Dependency, Path> dependencies = result.getDependencies();
        final Path[] allModulePaths = toRealPaths(moduleRoots, dependencies.values());
        fromURI = new HashMap<>(allModulePaths.length); // TODO: use newHashMap with JDK19.
        moduleFinder = ModuleFinder.of(allModulePaths);
        for (ModuleReference reference : moduleFinder.findAll()) {
            reference.location().ifPresent((location) -> fromURI.put(location, reference));
        }
        fromDependency = new HashMap<>(dependencies.size()); // TODO: use newHashMap with JDK19.
        for (Map.Entry<org.apache.maven.api.Dependency, Path> entry : dependencies.entrySet()) {
            Path modulePath = entry.getValue().toRealPath();
            ModuleReference reference = fromURI.get(modulePath.toUri());
            if (reference != null) {
                DependencyCoordinates coordinates = entry.getKey().toCoordinates();
                String id = coordinates.getId();
                ModuleReference old = fromDependency.putIfAbsent(id, reference);
                if (old == null) {
                    coordinates = withoutVersion(coordinates);
                    id = coordinates.getId();
                    old = fromDependency.putIfAbsent(id, reference);
                }
                if (old != null && !old.equals(reference)) {
                    mojo.getLog()
                            .warn("The \"" + id + "\" dependency is declared twice with different module names: \""
                                    + old.descriptor().name() + "\" and \""
                                    + reference.descriptor().name() + "\".");
                }
            }
        }
        builtModules = new HashMap<>(moduleRoots.size()); // TODO: use newHashMap with JDK19.
        for (Path root : moduleRoots) {
            ModuleDescriptor descriptor = fromURI.get(root.toUri()).descriptor();
            builtModules.put(
                    descriptor.name(),
                    Dependency.newBuilder()
                            .groupId(projectModel.getGroupId())
                            .artifactId(descriptor.name())
                            .version(projectModel.getVersion())
                            .type(Type.MODULAR_JAR)
                            .build());
        }
    }

    /**
     * Rebuilds the given dependency coordinates without version.
     *
     * Note: I'm not sure if it is necessary. This is done in case version numbers are not resolved
     * in the dependencies of the model returned by {@code Project.getModel()}, or are not resolved
     * in the same way as what we get from {@link DependencyResolver}.
     */
    private DependencyCoordinates withoutVersion(DependencyCoordinates coordinates) {
        return coordinateFactory.create(DependencyCoordinatesFactoryRequest.builder()
                .session(session)
                .groupId(coordinates.getGroupId())
                .artifactId(coordinates.getArtifactId())
                .extension(coordinates.getExtension())
                .classifier(coordinates.getClassifier())
                .build());
    }

    /**
     * If the resolver failed, propagates its exception.
     *
     * @param result the resolver result
     * @throws IOException if the result contains an I/O error
     */
    private static void rethrow(DependencyResolverResult result) throws IOException {
        Exception exception = null;
        for (Exception cause : result.getExceptions()) {
            if (cause instanceof UncheckedIOException e) {
                cause = e.getCause();
            }
            if (exception != null) {
                exception.addSuppressed(cause);
            } else if (cause instanceof RuntimeException || cause instanceof IOException) {
                exception = cause;
            } else {
                exception = new MojoException("Cannot collect the runtime dependencies.", cause);
            }
        }
        if (exception != null) {
            if (exception instanceof IOException e) {
                throw e;
            } else {
                throw (RuntimeException) exception; // A ClassCastException here would be a bug in above loop.
            }
        }
    }

    /**
     * Returns the real paths of the given collections, in iteration order and without duplicated values.
     */
    private static Path[] toRealPaths(Collection<Path> moduleRoots, Collection<Path> dependencies) throws IOException {
        // TODO: use newLinkedHashSet(int) after we are allowed to compile for JDK19.
        final var paths = new LinkedHashSet<Path>(moduleRoots.size() + dependencies.size());
        for (Path path : moduleRoots) {
            paths.add(path.toRealPath());
        }
        for (Path path : dependencies) {
            paths.add(path.toRealPath());
        }
        return paths.toArray(Path[]::new);
    }

    /**
     * Returns the module descriptor for the {@code module-info.class} at the given path.
     *
     * @param moduleInfo path to a {@code module-info.class} file
     * @return module descriptor for the specified file
     * @throws IOException if an error occurred while reading the file
     */
    private ModuleDescriptor findModuleDescriptor(Path moduleInfo) throws IOException {
        Path directory = moduleInfo.toRealPath().getParent();
        ModuleReference reference = fromURI.get(directory.toUri());
        if (reference != null) {
            return reference.descriptor();
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(moduleInfo))) {
            return ModuleDescriptor.read(in);
        }
    }

    /**
     * Returns the module descriptor for the specified Maven dependency.
     *
     * @param dependency dependency for which to get the module descriptor
     * @return Java module descriptor for the given Maven dependency
     */
    private Optional<ModuleDescriptor> findModuleDescriptor(Dependency dependency) {
        DependencyCoordinates coordinates = coordinateFactory.create(session, dependency);
        ModuleReference reference = fromDependency.get(coordinates.getId());
        if (reference == null) {
            coordinates = withoutVersion(coordinates);
            reference = fromDependency.get(coordinates.getId());
            if (reference == null) {
                return Optional.empty();
            }
        }
        return Optional.of(reference.descriptor());
    }

    /**
     * Returns the module descriptor for the specified module name.
     *
     * @param moduleName name of the module for which to get the descriptor
     * @return module descriptor for the specified module name
     */
    private Optional<ModuleDescriptor> findModuleDescriptor(String moduleName) {
        return moduleFinder.find(moduleName).map(ModuleReference::descriptor);
    }

    /**
     * Derives a <abbr>POM</abbr> as the intersection of the consumer <abbr>POM</abbr>
     * and the dependencies required by {@code module-info}.
     */
    final class ForModule {
        /**
         * Value of the {@code <name>} element in the derived <abbr>POM</abbr>, or {@code null} if none.
         */
        private String name;

        /**
         * Name of the module for which to derive a <abbr>POM</abbr> file.
         */
        private final String moduleName;

        /**
         * Whether a dependency is optional or required only at runtime.
         */
        private enum Modifier {
            OPTIONAL,
            RUNTIME
        }

        /**
         * The required dependencies as Java module names, including transitive dependencies.
         * Values tell whether the dependency is optional or should have runtime scope.
         */
        private final Map<String, EnumSet<Modifier>> requires;

        /**
         * Path to the <abbr>POM</abbr> file written by this class.
         */
        final Path pomFile;

        /**
         * Creates a new <abbr>POM</abbr> generator for the given archive.
         *
         * @param archive the archive for which to generate a <abbr>POM</abbr>
         * @param manifest manifest to use for deriving project name, or {@code null} if none
         * @throws IOException if an error occurred while reading the {@code module-info.class} file
         */
        ForModule(final Archive archive, final Manifest manifest) throws IOException {
            moduleName = archive.moduleName;
            pomFile = derivePathToPOM(archive.jarFile);
            requires = new LinkedHashMap<>();
            for (Path file : archive.moduleInfoFiles()) {
                addDependencies(findModuleDescriptor(file), EnumSet.noneOf(Modifier.class));
            }
            if (manifest != null) {
                name = (String) manifest.getMainAttributes().get(Attributes.Name.IMPLEMENTATION_TITLE);
                if (name == null) {
                    name = (String) manifest.getMainAttributes().get(Attributes.Name.SPECIFICATION_TITLE);
                }
            }
        }

        /**
         * Add the dependencies of the given module, including transitive dependencies.
         * If the same dependency is added twice with different optional flags,
         * the {@code false} value (i.e., mandatory dependency) has precedence.
         *
         * @param descriptor description of the module for which to add dependencies
         * @param parentModifiers modifiers of the parent module for which this method adds dependencies
         */
        private void addDependencies(final ModuleDescriptor descriptor, final EnumSet<Modifier> parentModifiers) {
            for (ModuleDescriptor.Requires r : descriptor.requires()) {
                final EnumSet<Modifier> modifiers = parentModifiers.clone();
                if (r.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)) {
                    modifiers.add(Modifier.OPTIONAL);
                }
                if (!r.modifiers().contains(ModuleDescriptor.Requires.Modifier.TRANSITIVE)) {
                    modifiers.add(Modifier.RUNTIME);
                }
                EnumSet<Modifier> current = requires.computeIfAbsent(r.name(), (key) -> modifiers);
                if (EXPAND_TRANSITIVE && (current == modifiers || current.retainAll(modifiers))) {
                    // Transitive dependencies if not already added or if it needs to update modifiers.
                    findModuleDescriptor(r.name()).ifPresent((td) -> addDependencies(td, modifiers));
                }
            }
        }

        /**
         * Derives the path to the <abbr>POM</abbr> file to generate.
         *
         * @param jarFile path to the <abbr>JAR</abbr> file (the file does not need to exist)
         * @return path to the <abbr>POM</abbr> file to generate
         */
        private static Path derivePathToPOM(final Path jarFile) {
            String filename = jarFile.getFileName().toString();
            filename = filename.substring(0, filename.lastIndexOf('.') + 1) + "pom";
            return jarFile.resolveSibling(filename);
        }

        /**
         * Derives a <abbr>POM</abbr> file for the archive specified ad construction time.
         *
         * @throws IOException if an error occurred while writing the <abbr>POM</abbr> file
         */
        void writeModulePOM() throws IOException {
            try {
                Model moduleModel = derive();
                try (BufferedWriter out = Files.newBufferedWriter(pomFile)) {
                    var sw = new MavenStaxWriter();
                    sw.setAddLocationInformation(false);
                    sw.write(out, moduleModel);
                    out.newLine();
                }
            } catch (ModelBuilderException | XMLStreamException e) {
                throw new MojoException("Cannot derive a POM file for the \"" + moduleName + "\" module.", e);
            }
        }

        /**
         * Derives the module <abbr>POM</abbr> file as the intersection of the project <abbr>POM</abbr> and the archive.
         *
         * @return intersection of {@link #projectModel} and {@code module-info.class}
         * @throws ModelBuilderException if an error occurred while building the model
         */
        private Model derive() throws ModelBuilderException {
            Model.Builder builder = Model.newBuilder(projectModel, true).artifactId(moduleName);
            /*
             * Remove the list of sub-projects (also known as "modules" in Maven 3).
             * They are not relevant to the specific JAR file that we are creating.
             */
            builder = builder.root(false).modules(null).subprojects(null);
            /*
             * Remove all build information. The <sources> element contains information about many modules,
             * not only the specific module that we are archiving. The <plugins> element is for building the
             * project and is usually not of interest for consumers.
             */
            builder = builder.build(null).reporting(null);
            /*
             * Filter the dependencies by keeping only the one declared in a `requires` statement of the
             * `module-info.class` of the module that we are archiving. Also adjust the `<optional>` and
             * `<scope>` values. The dependencies that we found are removed from the `requires` map as a
             * way to make sure that we do not add them twice. In principle, the map should become empty
             * at the end of this loop.
             */
            final List<Dependency> dependencies = projectModel.getDependencies();
            if (dependencies != null) {
                final var filteredDependencies = new ArrayList<Dependency>(dependencies.size());
                for (var iterator = requires.entrySet().iterator(); iterator.hasNext(); ) {
                    Map.Entry<String, EnumSet<Modifier>> entry = iterator.next();
                    Dependency dependency = builtModules.get(entry.getKey());
                    if (dependency != null) {
                        filteredDependencies.add(amend(dependency, entry.getValue()));
                        iterator.remove();
                    }
                }
                for (Dependency dependency : dependencies) {
                    String dependencyModuleName = findModuleDescriptor(dependency)
                            .map(ModuleDescriptor::name)
                            .orElse(null);
                    /*
                     * If `dependencyModuleName` is null, then the dependency scope is "test" or some other scope
                     * that resolver has chosen to exclude. Note that this is true even for JAR on the classpath,
                     * because we stored the automatic module name in `PomDerivation`. Next, if `modifiers` is null,
                     * then the dependency has compile or runtime scope but is not used by the module to archive.
                     */
                    if (dependencyModuleName != null) {
                        EnumSet<Modifier> modifiers = requires.remove(dependencyModuleName);
                        if (modifiers != null) {
                            filteredDependencies.add(amend(dependency, modifiers));
                        }
                    }
                }
                builder.dependencies(filteredDependencies);
            }
            /*
             * Replace the `<name>` element by the equivalent value defined in MANIFEST.MF.
             * We do this replacement because the `<name>` of the project model applies to all modules,
             * while the MANIFEST.MF has more chances to be specific to the module that we are archiving.
             */
            if (name != null) {
                builder = builder.name(name);
            }
            return builder.preserveModelVersion(false).modelVersion("4.0.0").build();
        }

        /**
         * Modifies the optional and scope elements of the given dependency according the given modifiers.
         *
         * @param dependency the dependency to amend
         * @param modifiers the modifiers to apply
         * @return the amended dependency
         */
        private static Dependency amend(Dependency dependency, EnumSet<Modifier> modifiers) {
            String scope = modifiers.contains(Modifier.RUNTIME) ? "runtime" : null;
            if (!Objects.equals(scope, dependency.getScope())) {
                dependency = dependency.withScope(scope);
            }
            boolean optional = modifiers.contains(Modifier.OPTIONAL);
            if (Boolean.parseBoolean(dependency.getOptional()) != optional) {
                dependency = dependency.withOptional(Boolean.toString(optional));
            }
            return dependency;
        }
    }
}
