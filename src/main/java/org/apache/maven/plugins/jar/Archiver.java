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
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;

import org.apache.maven.api.annotations.Nonnull;

/**
 * Creates the JAR file.
 *
 * TODO: this is a work in progress.
 */
final class Archiver extends SimpleFileVisitor<Path> {
    /**
     * Combination of includes and excludes path matchers applied on files.
     */
    @Nonnull
    private final PathMatcher fileMatcher;

    /**
     * Combination of includes and excludes path matchers applied on directories.
     */
    @Nonnull
    private final PathMatcher directoryMatcher;

    /**
     * Creates a new archiver.
     *
     * @param directory the base directory of the files to archive
     * @param includes the patterns of the files to include, or null or empty for including all files
     * @param excludes the patterns of the files to exclude, or null or empty for no exclusion
     */
    Archiver(Path directory, Collection<String> includes, Collection<String> excludes) {
        fileMatcher = PathSelector.of(directory, includes, excludes);
        if (fileMatcher instanceof PathSelector ps) {
            if (ps.canFilterDirectories()) {
                directoryMatcher = (path) -> ps.couldHoldSelected(path);
                return;
            }
        }
        directoryMatcher = PathSelector.INCLUDES_ALL;
    }

    /**
     * A scanner of {@code module-info.class} file and {@code META-INF/versions/*} directories.
     * This is used only when we need to auto-detect whether the JAR is modular or multiè-release.
     * This is not needed anymore for projects using the Maven 4 {@code <source>} elements.
     */
    final class VersionScanner extends SimpleFileVisitor<Path> {
        /**
         * The file to check for deciding whether the JAR is a modular JAR.
         */
        private static final String MODULE_DESCRIPTOR_FILE_NAME = "module-info.class";

        /**
         * The directory level in the {@code ./META-INF/versions/###/} path.
         * The root directory is at level 0 before we enter in that directory.
         * After the execution of {@code preVisitDirectory} (i.e., at the time of visiting files), values are:
         *
         * <ol>
         *   <li>for any file in the {@code ./} root classes directory.</li>
         *   <li>for any file in the {@code ./META-INF/} directory.</li>
         *   <li>for any file in the {@code ./META-INF/versions/} directory (i.e., any version number).</li>
         *   <li>for any file in the {@code ./META-INF/versions/###/} directory (i.e., any versioned file)</li>
         * </ol>
         */
        private int level;

        /**
         * First level of versioned files in a {@code ./META-INF/versions/###/} directory.
         */
        private static final int LEVEL_OF_VERSIONED_FILES = 4;

        /**
         * Whether a {@code META-INF/versions/} directory has been found.
         * The value of this flag is invalid if this scanner was constructed
         * with a {@code detectMultiReleaseJar} argument value of {@code true}.
         */
        boolean detectedMultiReleaseJAR;

        /**
         * Whether a {@code module-info.class} file has been found.
         */
        boolean containsModuleDescriptor;

        /**
         * Creates a scanner with all flags initially {@code false}.
         *
         * @param detectMultiReleaseJar whether to enable the detection of multi-release JAR
         */
        VersionScanner(boolean detectMultiReleaseJar) {
            detectedMultiReleaseJAR = !detectMultiReleaseJar; // Not actually true, but will cause faster stop.
        }

        /**
         * Returns {@code true} if the given directory is one of the parts of {@code ./META-INF/versions/*}.
         * The {@code .} directory is at level 0, {@code META-INF} at level 1, {@code versions} at level 2,
         * and one of the versions at level 3. Files at level 4 are versioned class files.
         */
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (detectedMultiReleaseJAR && level >= LEVEL_OF_VERSIONED_FILES) {
                // When searching only for `module-info.class`, we do not need to go further than that level.
                return (level > LEVEL_OF_VERSIONED_FILES)
                        ? FileVisitResult.SKIP_SIBLINGS
                        : FileVisitResult.SKIP_SUBTREE;
            }
            if (directoryMatcher.matches(dir)) {
                String expected =
                        switch (level) {
                            case 1 -> "META-INF";
                            case 2 -> "versions";
                            default -> null;
                        };
                if (expected == null || dir.endsWith(expected)) {
                    level++;
                    return FileVisitResult.CONTINUE;
                }
            }
            return FileVisitResult.SKIP_SUBTREE;
        }

        /**
         * Decrements our count of directory levels after visiting a directory.
         */
        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            level--;
            return super.postVisitDirectory(dir, exc);
        }

        /**
         * Updates the flags for the given files. This method terminates the
         * scanning if it detects that there is no new information to collect.
         */
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (fileMatcher.matches(file)) {
                if (level == 1 || level == LEVEL_OF_VERSIONED_FILES) {
                    // Root directory or a `META-INF/versions/###/` directory.
                    containsModuleDescriptor |= file.endsWith(MODULE_DESCRIPTOR_FILE_NAME);
                }
                detectedMultiReleaseJAR |= (level >= LEVEL_OF_VERSIONED_FILES);
                if (detectedMultiReleaseJAR) {
                    if (containsModuleDescriptor) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (level > LEVEL_OF_VERSIONED_FILES) {
                        return FileVisitResult.SKIP_SIBLINGS;
                    }
                }
            }
            return FileVisitResult.CONTINUE;
        }
    }

    /**
     * Determines if the given directory should be scanned for files to archive.
     */
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        return directoryMatcher.matches(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
    }

    /**
     * Archives a file in a directory.
     */
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (fileMatcher.matches(file)) {
            // TODO
        }
        return FileVisitResult.CONTINUE;
    }
}
