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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.services.PathMatcherFactory;

/**
 * Dispatch the files from the output directory into the <abbr>JAR</abbr> files to create.
 * Instead of just archiving as-is the content of the output directory, this class separates
 * the following subdirectories to the options listed below:
 *
 * <ul>
 *   <li>The {@code META-INF/MANIFEST.MF} file will be given to the {@code --manifest} option.</li>
 *   <li>Files in the following directories will be given to the {@code --release} option:
 *     <ul>
 *       <li>{@code META-INF/versions/}</li>
 *       <li>{@code META-INF/versions-modular/<module>/}</li>
 *       <li>{@code <module>/META-INF/versions/}</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * The reason for using the options is that they allow the {@code jar} tool to perform additional verifications.
 * For example, when using the {@code --release} option, {@code jar} verifies the <abbr>API</abbr> compatibility.
 */
final class FileCollector extends SimpleFileVisitor<Path> {
    /**
     * The file to check for deciding whether the <abbr>JAR</abbr> is modular.
     */
    static final String MODULE_DESCRIPTOR_FILE_NAME = "module-info.class";

    /**
     * The {@value} directory.
     * This is part of <abbr>JAR</abbr> file specification.
     */
    private static final String VERSIONS = "versions";

    /**
     * The {@value} directory.
     * This is Maven-specific.
     */
    private static final String VERSIONS_MODULAR = "versions-modular";

    /**
     * Context (logger, configuration) in which the <abbr>JAR</abbr> file are created.
     */
    private final ToolExecutor context;

    /**
     * Whether to detect multi-release <abbr>JAR</abbr> files.
     * The default value is {@code true}.
     *
     * @see AbstractJarMojo#detectMultiReleaseJar
     */
    private final boolean detectMultiReleaseJar;

    /**
     * The root directory to traverse. It will be used for temporarily moving excluded files.
     */
    private final Path rootDirectory;

    /**
     * Combination of includes and excludes path matcher applied on files.
     */
    @Nonnull
    private final PathMatcher fileMatcher;

    /**
     * Combination of includes and excludes path matcher applied on directories.
     */
    @Nonnull
    private final PathMatcher directoryMatcher;

    /**
     * Files to exclude. These files will be moved to a temporary location
     * for allowing {@link ToolExecutor} to specify whole directories to the {@code jar} tool.
     * Specifying whole directories is preferable to enumerating the files because otherwise,
     * the generated <abbr>JAR</abbr> file contains only entries for the files and is missing
     * entries for the directories.
     *
     * <p>This field is {@code null} if it is not possible to have any excluded file
     * (because there is no include/exclude filters).</p>
     */
    @Nullable
    private final List<Path> excludedFiles;

    /**
     * Directories to exclude. This field serves the same purpose as {@link #excludedFiles},
     * but where the sources a directories instead of files.
     */
    @Nullable
    private final List<Path> excludedDirectories;

    /**
     * Files found in the output directory when package hierarchy is used.
     * At most one of {@code packageHierarchy} and {@link #moduleHierarchy} can be non-empty.
     */
    @Nonnull
    private final Archive packageHierarchy;

    /**
     * Files found in the output directory when module hierarchy is used. Keys are module names.
     * At most one of {@link #packageHierarchy} and {@code moduleHierarchy} can be non-empty.
     */
    @Nonnull
    private final Map<String, Archive> moduleHierarchy;

    /**
     * The current module being archived. This field is updated every times that {@code FileCollector}
     * visits a new module directory.
     */
    @Nonnull
    private Archive currentModule;

    /**
     * The module and target Java release currently being scanned. This field is updated every times that
     * {@code FileCollector} visits a new module directory or in a new target Java release for a given module.
     */
    @Nonnull
    private Archive.FileSet currentFilesToArchive;

    /**
     * The current target Java release, or {@code null} if none.
     */
    @Nullable
    private Runtime.Version currentTargetVersion;

    /**
     * Identification of the kinds of directories being traversed.
     * The length of this list is the depth in the directory hierarchy.
     * The last element identifies the type of the current directory.
     */
    private final Deque<DirectoryRole> directoryRoles;

    /**
     * Whether to check when a file is the {@code MANIFEST.MF} file.
     * This is allowed only when scanning the content of a {@code META-INF} directory.
     */
    private boolean checkForManifest;

    /**
     * Creates a new file collector.
     *
     * @param mojo the <abbr>MOJO</abbr> from which to get the configuration
     * @param context context (logger, configuration) in which the <abbr>JAR</abbr> file are created
     * @param directory the base directory of the files to archive
     */
    FileCollector(AbstractJarMojo mojo, ToolExecutor context, Path directory, PathMatcherFactory matcherFactory) {
        this.context = context;
        rootDirectory = directory;
        detectMultiReleaseJar = mojo.detectMultiReleaseJar;
        directoryRoles = new ArrayDeque<>();
        fileMatcher = matcherFactory.createPathMatcher(directory, mojo.getIncludes(), mojo.getExcludes(), false);
        directoryMatcher = matcherFactory.deriveDirectoryMatcher(fileMatcher);
        if (matcherFactory.isIncludesAll(fileMatcher) && matcherFactory.isIncludesAll(directoryMatcher)) {
            excludedFiles = null;
            excludedDirectories = null;
        } else {
            excludedFiles = new ArrayList<>();
            excludedDirectories = new ArrayList<>();
        }
        packageHierarchy = context.newArchive(null, null, directory);
        moduleHierarchy = new LinkedHashMap<>();
        resetToPackageHierarchy();
    }

    /**
     * Resets this {@code FileCollector} to the state where a package hierarchy is presumed.
     */
    private void resetToPackageHierarchy() {
        currentModule = packageHierarchy;
        currentFilesToArchive = currentModule.baseRelease();
    }

    /**
     * Declares that the given directory is the base directory of a module.
     * For an output generated by {@code javac} from a module source hierarchy,
     * the directory name is guaranteed to be the module name.
     *
     * @param directory a {@code "<module>"} or {@code "META-INF/versions-modular/<module>"} directory
     */
    private void preVisitVersionDirectory(final Path directory) {
        String moduleName = directory.getFileName().toString();
        currentModule = moduleHierarchy.computeIfAbsent(
                moduleName, (name) -> context.newArchive(name, currentTargetVersion, directory));
        currentFilesToArchive = currentModule.newTargetRelease(directory, currentTargetVersion);
    }

    /**
     * Declares that the given directory is the base directory of a target Java version.
     * The {@code useDirectly} argument tells whether the content of this directory will be specified directly
     * as the content to add in the <abbr>JAR</abbr> file. This argument should be {@code false} when there is
     * another directory level (the module names) to process before to add content.
     *
     * @param directory a {@code "META-INF/versions/<n>"} or {@code "META-INF/versions-modular/<n>"} directory
     * @param useDirectly whether the directory is {@code "META-INF/versions/<n>"}
     * @return whether to skip the directory because of invalid version number
     */
    private boolean preVisitVersionDirectory(final Path directory, final boolean useDirectly) {
        try {
            currentTargetVersion = Runtime.Version.parse(directory.getFileName().toString());
        } catch (IllegalArgumentException e) {
            context.warnInvalidVersion(directory, e);
            return true;
        }
        if (useDirectly) {
            currentFilesToArchive = currentModule.newTargetRelease(directory, currentTargetVersion);
        }
        return false;
    }

    /**
     * Determines if the given directory should be scanned for files to archive.
     * This method may also update {@link #currentFilesToArchive} if it detects
     * that we are visiting the content of a new module or a new target Java release.
     *
     * @param directory the directory which will be traversed
     * @param attributes the directory's basic attributes
     */
    @Override
    @SuppressWarnings("checkstyle:MissingSwitchDefault")
    public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes)
            throws IOException {
        DirectoryRole role;
        if (directoryRoles.isEmpty()) {
            role = DirectoryRole.ROOT;
        } else {
            if (!directoryMatcher.matches(directory)) {
                excludedDirectories.add(directory); // Cannot be null if excluded directories may exist.
                return FileVisitResult.SKIP_SUBTREE;
            }
            checkForManifest = false;
            role = directoryRoles.getLast();
            switch (role) {
                case ROOT:
                    /*
                     * Visiting any subdirectory of `target/classes` (or other directory to archive).
                     * We need to handle `META-INF` and modules in a special way, and archive the rest.
                     */
                    if (directory.endsWith(MetadataFiles.META_INF)) {
                        role = DirectoryRole.META_INF;
                        checkForManifest = true;
                    } else if (Files.isRegularFile(directory.resolve(MODULE_DESCRIPTOR_FILE_NAME))) {
                        role = DirectoryRole.NAMED_MODULE;
                        preVisitVersionDirectory(directory);
                    } else {
                        role = DirectoryRole.RESOURCES;
                    }
                    break;

                case META_INF:
                    /*
                     * Visiting a subdirectory of `META-INF` or `<module>/META-INF`. We will need to handle
                     * `MANIFEST.MF`, `versions` and `versions-modular` in a special way, and archive the rest.
                     */
                    if (detectMultiReleaseJar && directory.endsWith(VERSIONS)) {
                        role = DirectoryRole.VERSIONS;
                    } else if (directory.endsWith(VERSIONS_MODULAR)) {
                        if (!detectMultiReleaseJar) {
                            // Used asked for no multi-release JAR.
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        role = DirectoryRole.VERSIONS_MODULAR;
                    } else {
                        role = DirectoryRole.RESOURCES;
                    }
                    break;

                case VERSIONS:
                    /*
                     * Visiting a `META-INF/versions/<n>/` directory for a specific target Java release.
                     * Can also be a `<module>/META-INF/versions/<n>/` directory, even if the latter is not
                     * the layout generated by Maven Compiler Plugin.
                     */
                    if (preVisitVersionDirectory(directory, true)) {
                        // An error occurred while parsing the version number.
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    role = DirectoryRole.RESOURCES;
                    break;

                case VERSIONS_MODULAR:
                    /*
                     * Visiting a `META-INF/versions-modular/<n>/` directory for a specific target Java release.
                     * That directory contains all modules for the version.
                     */
                    resetToPackageHierarchy(); // No module in particular yet.
                    if (preVisitVersionDirectory(directory, false)) {
                        // An error occurred while parsing the version number.
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    role = DirectoryRole.MODULES;
                    break;

                case MODULES:
                    /*
                     * Visiting a `META-INF/versions-modular/<n>/<module>` directory.
                     */
                    preVisitVersionDirectory(directory);
                    role = DirectoryRole.NAMED_MODULE;
                    break;

                case NAMED_MODULE:
                    /*
                     * Visiting a `<module>` or `META-INF/versions-modular/<n>/<module>` subdirectory.
                     * A module could have its own `META-INF` subdirectory, so we need to check again.
                     */
                    if (directory.endsWith(MetadataFiles.META_INF)) {
                        role = DirectoryRole.META_INF;
                        checkForManifest = true;
                    } else {
                        role = DirectoryRole.RESOURCES;
                    }
                    break;
            }
        }
        /*
         * Do not move this condition inside the `switch` block because `role` may have been modified.
         * The `role` value is now the role of `directory`, not the role of parent directory.
         */
        if (role == DirectoryRole.RESOURCES) {
            currentFilesToArchive.add(directory, attributes, true);
            if (excludedFiles == null) {
                /*
                 * Since we are skipping the whole directory, `postVisitDirectory(…)` will not be invoked.
                 * We must reset `currentFilesToArchive` and `currentTargetVersion` by an explicit call.
                 * This is important mostly after we added a whole `META-INF/versions/<n>` directory.
                 * Otherwise, since directory iteration order is unspecified, base files visited afterwards
                 * would be added to this version's file set instead of the base release.
                 */
                resetToParentDirectoryState();
                return FileVisitResult.SKIP_SUBTREE;
            }
        }
        directoryRoles.addLast(role);
        return FileVisitResult.CONTINUE;
    }

    /**
     * Updates the {@code FileCollector} state after we finished scanning the contents of a directory.
     * The fields to update depend on which directory has been visited (module, version, etc.).
     *
     * @param directory the directory which has been traversed
     * @param error the error that occurred while traversing the directory, or {@code null} if none
     */
    @Override
    public FileVisitResult postVisitDirectory(final Path directory, final IOException error) throws IOException {
        if (error != null) {
            throw error;
        }
        switch (directoryRoles.removeLast()) {
            case ROOT:
                break;

            case NAMED_MODULE:
                // Exited the directory of a single module.
                resetToPackageHierarchy();
                break;

            default:
                resetToParentDirectoryState();
                break;
        }
        return FileVisitResult.CONTINUE;
    }

    /**
     * Updates {@code FileCollector} to a state suitable for the parent of the directory that we finished to scan.
     * Contrary to {@link #postVisitDirectory(Path, IOException)}, this method expects the last element of
     * {@link #directoryRoles} to describe the parent directory, not the directory that we finished to visit.
     */
    @SuppressWarnings("checkstyle:MissingSwitchDefault")
    private void resetToParentDirectoryState() {
        switch (directoryRoles.getLast()) {
            case VERSIONS:
            case VERSIONS_MODULAR:
                // Exited the directory for one target Java release.
                currentFilesToArchive = currentModule.baseRelease();
                currentTargetVersion = null;
                break;

            case META_INF:
                checkForManifest = true;
                break;
        }
    }

    /**
     * Archives a single file if accepted by the matcher.
     *
     * @param file the file
     * @param attributes the file's basic attributes
     */
    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
        if (fileMatcher.matches(file)) {
            if (checkForManifest && file.endsWith(MetadataFiles.MANIFEST) && currentModule.setManifest(file, false)) {
                // Do not add `MANIFEST.MF`, it will be handled by the `--manifest` option instead.
            } else {
                currentFilesToArchive.add(file, attributes, false);
            }
        } else {
            excludedFiles.add(file); // Cannot be null if excluded files may exist.
        }
        return FileVisitResult.CONTINUE;
    }

    /**
     * Removes all empty archives and ensures that the lowest version is declared as the base version.
     * This method should be invoked after all output directories to archive have been fully scanned.
     * If {@code skipIfEmpty} is {@code false}, this method ensures that at least one archive remains
     * even if that archive is empty.
     *
     * @param skipIfEmpty value of {@link AbstractJarMojo#skipIfEmpty}
     */
    public void prune(boolean skipIfEmpty) {
        boolean isModuleHierarchy = !moduleHierarchy.isEmpty();
        moduleHierarchy.values().forEach((archive) -> archive.prune(skipIfEmpty));
        moduleHierarchy.values().removeIf(Archive::isEmpty);
        packageHierarchy.prune(isModuleHierarchy || skipIfEmpty);
    }

    /**
     * Moves, copies, or ignores orphan files.
     * An orphan file is a file which is not in any module when module hierarchy is used.
     * For example, some Maven plugins may create files such as {@code META-INF/LICENSE},
     * {@code META-INF/NOTICE} or {@code META-INF/DEPENDENCIES}. These files are not in
     * the correct directory (they should be in a {@code "<module>/META-INF"} directory)
     * because the plugin may not be aware of module hierarchy.
     *
     * <p>A possible strategy could be to copy the {@code LICENSE} and {@code NOTICE} files
     * in each module, and ignore the {@code DEPENDENCIES} file because its content is not
     * correct for a module. For now, we just log a warning and ignore.</p>
     *
     * <h4>Prerequisites</h4>
     * The {@link #prune(boolean)} method should have been invoked once before invoking this method.
     *
     * @return if this method ignored some files, the root directory of those files
     */
    Path handleOrphanFiles() {
        if (moduleHierarchy.isEmpty() || packageHierarchy.isEmpty()) {
            // Classpath project or module-project without orphan files. Nothing to do.
            return null;
        }
        // TODO: we may want to copy LICENSE and NOTICE files here.
        return packageHierarchy.discardAllFiles();
    }

    /**
     * Returns the object in charge of moving excluded files to a temporary directory, or {@code null} if none.
     */
    private ExcludedFiles exclusion() throws IOException {
        if ((excludedFiles == null || excludedFiles.isEmpty())
                && (excludedDirectories == null || excludedDirectories.isEmpty())) {
            return null;
        }
        return new ExcludedFiles(rootDirectory, excludedFiles, excludedDirectories);
    }

    /**
     * Writes all <abbr>JAR</abbr> files.
     * If the project is multi-module, this method returns the path to the generated parent <abbr>POM</abbr> file.
     *
     * <h4>Prerequisites</h4>
     * The {@link #prune(boolean)} method should have been invoked once before to invoke this method.
     *
     * @return path to the generated parent <abbr>POM</abbr> file, or {@code null} if none
     * @throws MojoException if an error occurred during the execution of the "jar" tool
     * @throws IOException if an error occurred while reading or writing a manifest file
     */
    Path writeAllJARs(final ToolExecutor executor) throws IOException {
        try (ExcludedFiles exclusion = exclusion()) {
            if (exclusion != null) {
                exclusion.move();
            }
            for (Archive module : moduleHierarchy.values()) {
                executor.writeSingleJAR(this, module);
            }
            if (executor.pomDerivation != null) {
                return executor.pomDerivation.writeParentPOM(packageHierarchy);
            }
            if (!packageHierarchy.isEmpty()) {
                executor.writeSingleJAR(this, packageHierarchy);
            }
        }
        return null;
    }

    /**
     * {@return the paths to all root directories of modules in a module hierarchy}
     * They are usually {@code target/classes/<module>} directories, but could also
     * be sub-directories in the {@code META-INF/modular-versions/<n>/} directory.
     * If the project does not use module hierarchy, this method returns an empty list.
     *
     * <h4>Ignored package hierarchy</h4>
     * Note that an empty list does not mean that the <abbr>JAR</abbr> is not modular,
     * as a modular <abbr>JAR</abbr> can also be built from package hierarchy. But we intentionally
     * ignore the latter case because this method is used for deriving <abbr>POM</abbr> files, and
     * we do not perform such derivation for projects organized in the Maven 3 way.
     *
     * <h4>Prerequisites</h4>
     * The {@link #prune(boolean)} method should have been invoked once before invoking this method.
     */
    List<Path> getModuleHierarchyRoots() {
        return moduleHierarchy.values().stream()
                .map((archive) -> archive.baseRelease().directory)
                .toList();
    }
}
