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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.apache.maven.api.Type;
import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

/**
 * Files or root directories to archive for a single module.
 * A single instance of {@code Archive} may contain many directories for different target Java releases.
 * Many instances of {@code Archive} may exist when archiving a multi-modules project.
 */
final class Archive {
    /**
     * Path to the <abbr>POM</abbr> file generated for this archive, or {@code null} if none.
     * This is non-null only if module source hierarchy is used, in which case the dependencies
     * declared in this file are the intersection of the project dependencies and the content of
     * the {@code module-info.class} file.
     */
    @Nullable
    Path pomFile;

    /**
     * The <var>JAR</var> file to create. May be an existing file,
     * in which case the file creation may be skipped if the file is still up-to-date.
     */
    @Nonnull
    final Path jarFile;

    /**
     * A helper class for checking whether an existing <abbr>JAR</abbr> file is still up-to-date.
     * This is null if there is no existing JAR file, or if we determined that the file is outdated.
     */
    private TimestampCheck existingJAR;

    /**
     * Name of the module being archived when the project is using module hierarchy.
     * This is {@code null} if the project is using package hierarchy, either because it is a classical
     * class-path project or because it is a single module compiled without using the module hierarchy.
     * When using module source hierarchy, {@code javac} guarantees that the module name in the output
     * directory is the name of the parent directory of {@code module-info.class}.
     */
    @Nullable
    final String moduleName;

    /**
     * Path to {@code META-INF/MANIFEST.MF}, or {@code null} if none. The manifest file
     * should be included by the {@code --manifest} option instead of as an ordinary file.
     */
    @Nullable
    private Path manifest;

    /**
     * The Maven generated {@code pom.xml} and {@code pom.properties} files, or {@code null} if none.
     * This first item shall be the base directory where the files are located.
     */
    @Nullable
    List<Path> mavenFiles;

    /**
     * Fully-qualified name of the main class, or {@code null} if none.
     * This is the value to provide to the {@code --main-class} option.
     */
    private String mainClass;

    /**
     * Files or root directories to store in the <abbr>JAR</abbr> file for each target Java release
     * other than the base release. Keys are the target Java release with {@code null} for the base
     * release.
     */
    @Nonnull
    private final NavigableMap<Runtime.Version, FileSet> filesetForRelease;

    /**
     * Files or root directories to archive for a single target Java release of a single module.
     * The {@link Archive} enclosing shall contain at least one instance of {@code FileSet} for
     * the base release, and an arbitrary amount of other instances for other target releases.
     */
    final class FileSet {
        /**
         * The root directory of all files or directories to archive.
         * This is the value to pass to the {@code -C} tool option.
         */
        @Nonnull
        final Path directory;

        /**
         * The files or directories to include in the <var>JAR</var> file.
         * May be absolute paths or paths relative to {@link #directory}.
         */
        @Nonnull
        final List<Path> files;

        /**
         * Creates an initially empty set of files or directories for a specific target Java release.
         *
         * @param directory the base directory of the files or directories to archive
         */
        private FileSet(Path directory) {
            this.directory = directory;
            this.files = new ArrayList<>();
        }

        /**
         * Discards all files in this file set, normally because those files are not in any module.
         * This method returns a common parent directory for all the files that were discarded.
         * The caller should use that common directory for logging a warning message.
         *
         * @param base base directory found by previous invocations of this method, or {@code null} if none
         * @return common directory of discarded files
         */
        private Path discardAllFiles(Path base) {
            for (Path file : files) {
                file = directory.resolve(file);
                if (base == null) {
                    base = file.getParent();
                } else {
                    while (!file.startsWith(base)) {
                        base = base.getParent();
                        if (base == null) {
                            break;
                        }
                    }
                }
            }
            files.clear();
            return base;
        }

        /**
         * Adds the given path to the list of files or directories to archive.
         * This method may store a relative path instead of the absolute path.
         *
         * @param item a file or directory to archive
         * @param attributes the file's basic attributes
         * @param isDirectory whether the file is a directory
         * @throws IllegalArgumentException if the given path cannot be made relative to the base directory
         */
        void add(Path item, BasicFileAttributes attributes, boolean isDirectory) {
            TimestampCheck tc = existingJAR;
            if (tc != null && tc.isUpdated(item, attributes, isDirectory)) {
                existingJAR = null; // Signal that the existing file is outdated.
            }
            if (files.isEmpty()) {
                /*
                 * In our tests, it seems that the first file after the "-C" option needs to be relative
                 * to the directory given to "-C" and all other files need to be absolute. This behavior
                 * does not seem to be documented, but we couldn't get the "jar" tool to work otherwise
                 * (except by repeating "-C" before each file).
                 */
                item = directory.relativize(item);
            }
            files.add(item);
        }

        /**
         * Adds to the given list the arguments to provide to the "jar" tool for this version.
         * Elements added to the list shall be instances of {@link String} or {@link Path}.
         *
         * @param addTo the list where to add the arguments as {@link String} or {@link Path} instances
         * @param version the target Java release, or {@code null} for the base version of the <abbr>JAR</abbr> file
         */
        private void arguments(List<Object> addTo, Runtime.Version version) {
            if (!files.isEmpty()) {
                if (version != null) {
                    addTo.add("--release");
                    addTo.add(version);
                }
                addTo.add("-C");
                addTo.add(directory);
                addTo.addAll(files);
            }
        }

        /**
         * {@return a string representation for debugging purposes}
         */
        @Override
        public String toString() {
            return getClass().getSimpleName() + '[' + directory.getFileName() + ": " + files.size() + " files]";
        }
    }

    /**
     * Creates an initially empty set of files or directories.
     *
     * @param jarFile path to the <abbr>JAR</abbr> file to create
     * @param moduleName the module name if using module hierarchy, or {@code null} if using package hierarchy
     * @param directory the directory of the classes targeting the base Java release
     * @param forceCreation whether to force a new <abbr>JAR</abbr> file even if the content seems unchanged.
     * @param logger where to send a warning if an error occurred while checking an existing <abbr>JAR</abbr> file
     */
    @SuppressWarnings("checkstyle:NeedBraces")
    Archive(Path jarFile, String moduleName, Path directory, boolean forceCreation, Log logger) {
        this.jarFile = jarFile;
        this.moduleName = moduleName;
        filesetForRelease = new TreeMap<>((v1, v2) -> {
            if (v1 == v2) return 0;
            if (v1 == null) return -1;
            if (v2 == null) return +1;
            return v1.compareTo(v2);
        });
        filesetForRelease.put(null, new FileSet(directory));
        if (!forceCreation && Files.isRegularFile(jarFile)) {
            try {
                existingJAR = new TimestampCheck(jarFile, directory, logger);
            } catch (IOException e) {
                // Ignore, we will regenerate the JAR file.
                logger.warn(e);
            }
        }
    }

    /**
     * {@return the files or directories to store in the <abbr>JAR</abbr> file for targeting the base Java release}
     *
     * @throws NoSuchElementException should not happen unless {@link #prune(boolean)} has been invoked
     */
    FileSet baseRelease() {
        return filesetForRelease.firstEntry().getValue();
    }

    /**
     * Returns the {@code module-info.class} files. Conceptually, there is at most once such file per module.
     * However, more than one file may exist if additional files are provided for additional Java releases.
     * This method returns only the files that exist.
     *
     * @return all {@code module-info.class} files found for all target Java releases
     */
    public List<Path> moduleInfoFiles() {
        var files = new ArrayList<Path>();
        filesetForRelease.values().forEach((release) -> {
            Path file = release.directory.resolve(FileCollector.MODULE_DESCRIPTOR_FILE_NAME);
            if (Files.isRegularFile(file)) {
                files.add(file);
            }
        });
        return files;
    }

    /**
     * Discards all files in this archive, normally because those files are not in any module.
     * This method returns a common parent directory for all the files that were discarded.
     * The caller should use that common directory for logging a warning message.
     *
     * @return common directory of discarded files, or {@code null} if none
     */
    Path discardAllFiles() {
        Path base = null;
        for (FileSet release : filesetForRelease.values()) {
            base = release.discardAllFiles(base);
        }
        filesetForRelease.clear();
        return base;
    }

    /**
     * Removes all empty file sets and ensures that the lowest version is declared as the base version.
     * This method should be invoked after all output directories to archive have been fully scanned.
     * If {@code skipIfEmpty} is {@code false}, then this method ensures that at least one file set
     * remains even if that file set is empty.
     *
     * @param skipIfEmpty value of {@link AbstractJarMojo#skipIfEmpty}
     */
    public void prune(final boolean skipIfEmpty) {
        FileSet keep = (skipIfEmpty || isEmpty())
                ? null
                : filesetForRelease.firstEntry().getValue();
        filesetForRelease.values().removeIf((fs) -> fs.files.isEmpty());
        Iterator<Map.Entry<Runtime.Version, FileSet>> it =
                filesetForRelease.entrySet().iterator();
        if (it.hasNext()) {
            Map.Entry<Runtime.Version, FileSet> first = it.next();
            if (first.getKey() == null) {
                return; // Already contains an entry for the base version, nothing to do.
            }
            keep = first.getValue();
            it.remove();
        }
        if (keep != null) {
            filesetForRelease.put(null, keep);
        }
    }

    /**
     * {@return whether this archive has nothing to archive}
     * Note that this method may return {@code false} even when there is zero file to archive.
     * It may happen if {@link AbstractJarMojo#skipIfEmpty} is {@code false}. In such case, the
     * "empty" <abbr>JAR</abbr> file will still contain at {@code META-INF/MANIFEST.MF} file.
     *
     * <h4>Prerequisites</h4>
     * The {@link #prune(boolean)} method should be invoked before this method for accurate result.
     */
    public boolean isEmpty() {
        return filesetForRelease.isEmpty();
    }

    /**
     * Checks whether the <abbr>JAR</abbr> file already exists and can be reused.
     * This method verifies that the <abbr>JAR</abbr> file contains all the files to archive,
     * contains no extra file, and no file to archive is newer than the <abbr>JAR</abbr> file.
     *
     * <p>This method can be invoked only once.
     * If invoked more often, it returns {@code false} on all subsequent invocations.</p>
     *
     * @return whether the <abbr>JAR</abbr> file already exists and can be reused
     */
    public boolean isUpToDateJAR() {
        final TimestampCheck tc = existingJAR;
        if (tc == null) {
            return false;
        }
        existingJAR = null; // Let GC do its job.
        return tc.isUpToDateJAR(filesetForRelease.values());
    }

    /**
     * Returns an initially empty set of files or directories for the specified target Java release.
     *
     * @param directory the base directory of the files to archive
     * @param version the target Java release, or {@code null} for the base version
     * @return container where to declare files and directories to archive
     */
    FileSet newTargetRelease(Path directory, Runtime.Version version) {
        return filesetForRelease.computeIfAbsent(version, (key) -> new FileSet(directory));
    }

    /**
     * Sets the {@code --main-class} option to the value of the {@code Main-Class} entry of the given manifest.
     * As an extension, this method accepts the {@code module/classname} syntax (a syntax already used in some
     * Java tools). If a module is specified, the main class is kept only if the module match. The intent is to
     * allow users to specify on which module the main class applies when they use plugin configuration.
     *
     * @param content combination of existing {@code MANIFEST.MF} and manifest inferred from configuration, or null
     * @return whether the given manifest has been modified by this method
     */
    boolean setMainClass(Manifest content) {
        if (content == null || mainClass != null) {
            return false;
        }
        // We need to remove the attribute, otherwise it will conflict with `--main-class`.
        mainClass = (String) content.getMainAttributes().remove(Attributes.Name.MAIN_CLASS);
        if (mainClass != null) {
            int s = mainClass.indexOf('/');
            if (s >= 0) {
                if (mainClass.substring(0, s).strip().equals(moduleName)) {
                    mainClass = mainClass.substring(s + 1).strip();
                } else {
                    mainClass = null; // Main class is defined for another module.
                }
            }
        }
        return mainClass != null;
    }

    /**
     * Sets the {@code --manifest} option to the given value if that option was not already set.
     *
     * @param file path to the manifest file
     * @param force whether to set the manifest even if already set
     * @return whether the manifest has been set
     */
    boolean setManifest(Path file, boolean force) {
        if (manifest == null || force) {
            manifest = file;
            return true;
        }
        return false;
    }

    /**
     * Merges the manifest of this module with the manifest specified in plugin configuration.
     * If both {@code file} and {@code content} are non-null, then {@code content} must be the
     * result of reading {@code file}.
     *
     * <p>This method never modifies the given {@code content} object. If manifest are merged,
     * a new {@link Manifest} instance is created. Therefore, caller can check whether this
     * method returned a new instance as a way to recognize that a merge occurred.</p>
     *
     * <p>If a merge occurs, the content specified to {@link #setManifest(Path)} has precedence.
     * It should be the {@code target/classes/META-INF/MANIFEST.MF} file (or modular equivalent).</p>
     *
     * @param  file     an additional manifest file, or {@code null}
     * @param  content  the content of {@code file}, or a standalone manifest produced at runtime
     * @return the merged manifest as a new instance if some changes were necessary
     * @throws IOException if an error occurred while reading a manifest file
     */
    Manifest mergeManifest(Path file, Manifest content) throws IOException {
        if (manifest == null) {
            manifest = file;
        } else if (file != null && Files.isSameFile(file, manifest)) {
            // Nothing to merge because of the constraint that `content` must be the content of `file`.
        } else {
            try (InputStream in = Files.newInputStream(manifest)) {
                // No need to wrap in `BufferedInputStream`.
                if (content != null) {
                    content = new Manifest(content);
                    content.read(in);
                } else {
                    content = new Manifest(in);
                }
            }
        }
        return content;
    }

    /**
     * Adds to the given list the arguments to provide to the "jar" tool for each version.
     * Elements added to the list shall be instances of {@link String} or {@link Path}.
     * Callers should have added the following options (if applicable) before to invoke this method:
     *
     * <ul>
     *   <li>{@code --create}</li>
     *   <li>{@code --no-compress}</li>
     *   <li>{@code --date} followed by the output time stamp</li>
     *   <li>{@code --module-version} followed by module version</li>
     *   <li>{@code --hash-modules} followed by patters of module names</li>
     *   <li>{@code --module-path} followed by module path</li>
     * </ul>
     *
     * This method adds the following options:
     *
     * <ul>
     *   <li>{@code --file} followed by the path to the <abbr>JAR</abbr> file</li>
     *   <li>{@code --manifest} followed by path to the manifest file</li>
     *   <li>{@code --main-class} followed by fully qualified name class</li>
     *   <li>{@code --release} followed by Java target release</li>
     *   <li>{@code -C} followed by directory</li>
     *   <li>files or directories to archive</li>
     * </ul>
     *
     * @param addTo the list where to add the arguments as {@link String} or {@link Path} instances
     */
    void arguments(final List<Object> addTo) {
        addTo.add("--file");
        addTo.add(jarFile);
        if (manifest != null) {
            addTo.add("--manifest");
            addTo.add(manifest);
        }
        if (mainClass != null) {
            addTo.add("--main-class");
            addTo.add(mainClass);
        }
        if (mavenFiles != null) {
            addTo.add("-C");
            addTo.addAll(mavenFiles);
        }
        for (Map.Entry<Runtime.Version, FileSet> entry : filesetForRelease.entrySet()) {
            entry.getValue().arguments(addTo, entry.getKey());
        }
    }

    /**
     * Adds to the given list the arguments to provide to the "jar" tool for validating the <abbr>JAR</abbr> file.
     * The file is validated only if the validation was not done implicitly at <abbr>JAR</abbr> creation time.
     * This is the case if no {@code --release} option was used.
     * This method adds the following options:
     *
     * <ul>
     *   <li>{@code --validate} operation mode</li>
     *   <li>{@code --file} followed by the path to the <abbr>JAR</abbr> file</li>
     * </ul>
     *
     * @param  addTo the list where to add the arguments as {@link String} or {@link Path} instances
     * @return whether a validation should be run
     */
    boolean validate(final List<Object> addTo) {
        if (filesetForRelease.values().stream().allMatch(Objects::isNull)) {
            // At least one --release option was used. Validation was implicit.
            return false;
        }
        addTo.add("--validate");
        addTo.add("--file");
        addTo.add(jarFile);
        return true;
    }

    /**
     * Dumps the tool options together with the list of files into a debug file.
     * This is invoked in case of compilation failure, or if debug is enabled.
     * The arguments can be separated by spaces or by new line characters.
     * File name should be between double quotation marks.
     *
     * @param baseDir project base directory for relativizing the arguments
     * @param debugDirectory the directory where to write the debug file
     * @param classifier the classifier (e.g. "tests"), or {@code null} if none
     * @param arguments the arguments formatted by {@link #arguments(List)}
     * @return the debug file where arguments have been written
     * @throws IOException if an error occurred while writing the debug file
     */
    Path writeDebugFile(Path baseDir, Path debugDirectory, String classifier, List<Object> arguments)
            throws IOException {
        var filename = new StringBuilder("jar");
        if (moduleName != null) {
            filename.append('-').append(moduleName);
        }
        if (classifier != null) {
            filename.append('-').append(classifier);
        }
        Path debugFile = debugDirectory.resolve(filename.append(".args").toString());
        try (BufferedWriter out = Files.newBufferedWriter(debugFile)) {
            boolean isNewLine = true;
            for (Object argument : arguments) {
                if (argument instanceof Path file) {
                    try {
                        file = baseDir.relativize(file);
                    } catch (IllegalArgumentException e) {
                        // Ignore, keep the absolute path.
                    }
                    if (!isNewLine) {
                        out.write(' ');
                    }
                    out.write('"');
                    out.write(file.toString());
                    out.write('"');
                    out.newLine();
                    isNewLine = true;
                } else {
                    String option = argument.toString();
                    if (!isNewLine) {
                        if (option.startsWith("--") || option.equals("-C")) {
                            out.newLine();
                        } else {
                            out.write(' ');
                        }
                    }
                    out.write(option);
                    isNewLine = false;
                }
            }
        }
        return debugFile;
    }

    /**
     * Stores in the given map the paths to the artifacts produced for this archive.
     *
     * @param artifactType {@code "jar"} or {@code "test-jar"}
     * @param addTo the map where to add the results
     */
    void saveArtifactPaths(final String artifactType, final Map<String, Map<String, Path>> addTo) {
        final Map<String, Path> paths;
        if (pomFile != null) {
            paths = Map.of(artifactType, jarFile, Type.POM, pomFile);
        } else {
            paths = Map.of(artifactType, jarFile);
        }
        if (addTo.put(moduleName, paths) != null) {
            // Should never happen, but check anyway.
            throw new MojoException("Module archived twice: " + moduleName);
        }
    }

    /**
     * {@return a string representation for debugging purposes}
     */
    @Override
    public String toString() {
        var sb = new StringBuilder(getClass().getSimpleName()).append('[');
        if (moduleName != null) {
            sb.append('"').append(moduleName).append("\": ");
        }
        int count = filesetForRelease.values().stream()
                .mapToInt((release) -> release.files.size())
                .sum();
        return sb.append(count).append(" files]").toString();
    }
}
