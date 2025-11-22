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
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.apache.maven.api.annotations.Nonnull;
import org.apache.maven.api.annotations.Nullable;
import org.apache.maven.api.plugin.Log;

/**
 * Files or root directories to archive for a single module.
 * A single instance of {@code Archive} may contain many directories for different target Java releases.
 * Many instances of {@code Archive} may exist when archiving a multi-modules project.
 */
final class Archive {
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
     * Files or root directories to store in the <abbr>JAR</abbr> file for targeting the base Java release.
     */
    @Nonnull
    final FileSet baseRelease;

    /**
     * Files or root directories to store in the <abbr>JAR</abbr> file for each target Java release
     * other than the base release.
     *
     * <h4>Note on duplicated versions</h4>
     * In principle, we should not have two elements with the same {@link FileSet#version} value.
     * However, while it should not happen in default Maven builds, we do not forbid the case where
     * the same version would be defined in {@code "./META-INF"} and {@code "./<module>/META-INF"}.
     * In such case, two {@code FileSet} instances would exist for the same Java release but with
     * two different {@link FileSet#directory} values.
     */
    @Nonnull
    private final List<FileSet> additionalReleases;

    /**
     * Files or root directories to archive for a single target Java release of a single module.
     * The {@link Archive} enclosing shall contain at least one instance of {@code FileSet} for
     * the base release, and an arbitrary amount of other instances for other target releases.
     */
    final class FileSet {
        /**
         * The target Java release, or {@code null} for the base version of the <abbr>JAR</abbr> file.
         */
        @Nullable
        final Runtime.Version version;

        /**
         * The root directory of all files or directories to archive.
         * This is the value to pass to the {@code -C} tool option.
         */
        @Nonnull
        private final Path directory;

        /**
         * The files or directories to include in the <var>JAR</var> file.
         * May be absolute paths or paths relative to {@link #directory}.
         */
        @Nonnull
        private final List<Path> files;

        /**
         * Creates an initially empty set of files or directories for the specified target Java release.
         *
         * @param directory the base directory of the files or directories to archive
         * @param version the target Java release, or {@code null} for the base version of the <abbr>JAR</abbr> file
         */
        private FileSet(final Path directory, final Runtime.Version version) {
            this.version = version;
            this.directory = directory;
            this.files = new ArrayList<>();
        }

        /**
         * Finds a common directory for all remaining files, then clears the list of file.
         * The common directory can be used for logging a warning message.
         *
         * @param base base directory found by previous invocations of this method, or {@code null} if none
         * @return common directory of remaining files
         */
        private Path clear(Path base) {
            for (Path file : files) {
                base = findCommonBaseDirectory(base, directory.resolve(file));
            }
            files.clear();
            return base;
        }

        /**
         * Returns files as values, together with the base directory (as key) for resolving relative files.
         */
        private Map.Entry<Path, Iterable<Path>> files() {
            return Map.entry(directory, files);
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
        void add(Path item, final BasicFileAttributes attributes, final boolean isDirectory) {
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
         * @param versioned whether to add arguments for the version specified by {@linkplain #version}
         * @return whether at least one file has been added as argument
         */
        private boolean arguments(final List<Object> addTo, final boolean versioned) {
            if (files.isEmpty()) {
                // Happen if both `FileCollector.moduleHierarchy` and `FileCollector.packageHierarchy` are empty.
                return false;
            }
            if (versioned && version != null) {
                addTo.add("--release");
                addTo.add(version);
            }
            addTo.add("-C");
            addTo.add(directory);
            addTo.addAll(files);
            return true;
        }

        /**
         * {@return a string representation for debugging purposes}
         */
        @Override
        public String toString() {
            return getClass().getSimpleName() + '[' + (version != null ? version : "base") + " = "
                    + directory.getFileName() + ']';
        }
    }

    /**
     * Creates an initially empty set of files or directories.
     *
     * @param jarFile path to the <abbr>JAR</abbr> file to create
     * @param moduleName the module name if using module hierarchy, or {@code null} if using package hierarchy
     * @param directory the directory of the classes targeting the base Java release
     * @param forceCreation whether to force new <abbr>JAR</abbr> file even the contents seem unchanged.
     * @param logger where to send a warning if an error occurred while checking an existing <abbr>JAR</abbr> file
     */
    Archive(final Path jarFile, final String moduleName, final Path directory, boolean forceCreation, Log logger) {
        this.jarFile = jarFile;
        this.moduleName = moduleName;
        baseRelease = new FileSet(directory, null);
        additionalReleases = new ArrayList<>();
        if (!forceCreation && Files.isRegularFile(jarFile)) {
            try {
                existingJAR = new TimestampCheck(jarFile, directory(), logger);
            } catch (IOException e) {
                // Ignore, we will regenerate the JAR file.
                logger.warn(e);
            }
        }
    }

    /**
     * Returns the root directory of all files or directories to archive for this module.
     *
     * @return the root directory of this module.
     */
    public Path directory() {
        return baseRelease.directory;
    }

    /**
     * Finds a common directory for all remaining files, then clears the list of file.
     * The common directory can be used for logging a warning message.
     *
     * @return common directory of remaining files
     */
    Path clear() {
        Path base = baseRelease.clear(null);
        for (FileSet release : additionalReleases) {
            base = release.clear(base);
        }
        return (base != null) ? base : directory();
    }

    /**
     * Returns a directory which is the base of the given {@code file}.
     * This method returns either {@code base}, or a parent of {@code base}, or {@code null}.
     *
     * @param base the last base directory found, or {@code null}
     * @param file the file for which to find a common base directory
     * @return {@code base}, or a parent of {@code base}, or {@code null}
     */
    private static Path findCommonBaseDirectory(Path base, Path file) {
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
        return base;
    }

    /**
     * Returns whether this module can be skipped. This is {@code true} if this module has no file to archive,
     * ignoring Maven-generated files, and {@code skipIfEmpty} is {@code true}. This method should be invoked
     * even in the trivial case where the {@code skipIfEmpty} argument is {@code false}.
     *
     * @param skipIfEmpty value of {@link AbstractJarMojo#skipIfEmpty}
     * @return whether this module can be skipped
     */
    public boolean canSkip(final boolean skipIfEmpty) {
        additionalReleases.removeIf((v) -> v.files.isEmpty());
        return skipIfEmpty && baseRelease.files.isEmpty() && additionalReleases.isEmpty();
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
        var fileSets = new ArrayList<Map.Entry<Path, Iterable<Path>>>(additionalReleases.size() + 1);
        fileSets.add(baseRelease.files());
        additionalReleases.forEach((release) -> fileSets.add(release.files()));
        return tc.isUpToDateJAR(fileSets);
    }

    /**
     * Returns an initially empty set of files or directories for the specified target Java release.
     *
     * @param directory the base directory of the files to archive
     * @param version the target Java release, or {@code null} for the base version
     * @return container where to declare files and directories to archive
     */
    FileSet newTargetRelease(Path directory, Runtime.Version version) {
        var release = new FileSet(directory, version);
        additionalReleases.add(release);
        return release;
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
     *   <li>{@code --file} followed by the name of the <abbr>JAR</abbr> file</li>
     *   <li>{@code --manifest} followed by path to the manifest file</li>
     *   <li>{@code --main-class} followed by fully qualified name class</li>
     *   <li>{@code --release} followed by Java target release</li>
     *   <li>{@code -C} followed by directory</li>
     *   <li>files or directories to archive</li>
     * </ul>
     *
     * @param addTo the list where to add the arguments as {@link String} or {@link Path} instances
     */
    @SuppressWarnings("checkstyle:NeedBraces")
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
        // Sort by increasing release version.
        additionalReleases.sort((f1, f2) -> {
            Runtime.Version v1 = f1.version;
            Runtime.Version v2 = f2.version;
            if (v1 != v2) {
                if (v1 == null) return -1;
                if (v2 == null) return +1;
                int c = v1.compareTo(v2);
                if (c != 0) return c;
            }
            // Give precedence to directories closer to the root.
            return f1.directory.getNameCount() - f2.directory.getNameCount();
        });
        boolean versioned = baseRelease.arguments(addTo, false);
        for (FileSet release : additionalReleases) {
            versioned |= release.arguments(addTo, versioned);
        }
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
     * {@return a string representation for debugging purposes}
     */
    @Override
    public String toString() {
        return getClass().getSimpleName() + '[' + (moduleName != null ? moduleName : "no module") + ']';
    }
}
