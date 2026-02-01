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
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.maven.api.plugin.Log;

/**
 * Checks file timestamps in order to determine if anything changed compared to an existing <abbr>JAR</abbr> file.
 * This class may scan directories, but only if they have not already been visited by {@link FileCollector}.
 * Note that the latter can occur only if {@link FileCollector} has no {@code PathMatcher}.
 * Therefore, this class uses no {@code PathMatcher} neither.
 *
 * <h2>Ignore files</h2>
 * The {@code META-INF/MANIFEST.MF} file and the {@code META-INF/maven/} directory are ignored.
 * See {@link #isIgnored(Path)} for the rational.
 */
final class TimestampCheck extends SimpleFileVisitor<Path> {
    /**
     * The base directory of archived files.
     */
    private final Path classesDir;

    /**
     * Path to the existing <abbr>JAR</abbr> file.
     */
    private final Path jarFile;

    /**
     * The last modified time of the <abbr>JAR</abbr> file.
     */
    private final FileTime jarFileTime;

    /**
     * Where to send non-fatal error messages.
     */
    private final Log logger;

    /**
     * Entries of the <abbr>JAR</abbr> file. Note that getting elements from this enumeration can be costly.
     * Therefore, we do not fetch all elements in advance but only when needed.
     */
    private Enumeration<? extends ZipEntry> entries;

    /**
     * Files found in the <abbr>JAR</abbr> file but not yet traversed by the file visitor.
     * Files are added lazily only when needed, and removed as soon as they have been traversed.
     * Path are absolute (resolved with {@link #classesDir}).
     */
    private final Set<Path> filesInJAR;

    /**
     * Some of the files in the build directory. This list contains only the files for which we have already
     * verified the timestamp. We store them in a separated list for avoiding to check the timestamp twice.
     * We need this list because we still need to verify if the files are in the {@link #jarFile}.
     */
    private final Set<Path> filesInBuild;

    /**
     * Whether at least one file is more recent than the <abbr>JAR</abbr> file.
     * The scan of files will stop quickly after this flag become {@code true}.
     */
    private boolean hasUpdates;

    /**
     * Creates a new visitor for checking the validity of a <abbr>JAR</abbr> file.
     *
     * @param jarFile the existing <abbr>JAR</abbr> file
     * @param jarFileTime the last modified time of the <abbr>JAR</abbr> file
     * @param classesDir base directory of archived files
     * @param logger where to send a warning if an error occurred while checking an existing <abbr>JAR</abbr> file
     * @throws IOException if an error occurred while fetching the <abbr>JAR</abbr> file modification time
     */
    TimestampCheck(final Path jarFile, final Path classesDir, final Log logger) throws IOException {
        this.classesDir = classesDir;
        this.jarFile = jarFile;
        this.logger = logger;
        jarFileTime = Files.getLastModifiedTime(jarFile);
        filesInJAR = new HashSet<>();
        filesInBuild = new HashSet<>();
    }

    /**
     * Returns {@code true} if the given file is more recent that the <abbr>JAR</abbr> file.
     *
     * @param file the file to check
     * @param attributes the file's basic attributes
     * @param isDirectory whether the file is a directory
     * @return whether the modification time is more recent that the <abbr>JAR</abbr> file
     */
    boolean isUpdated(final Path file, final BasicFileAttributes attributes, final boolean isDirectory) {
        if (jarFileTime.compareTo(attributes.lastModifiedTime()) < 0) {
            return true;
        }
        if (!isDirectory) {
            filesInBuild.add(file);
        }
        return false;
    }

    /**
     * Checks if the <abbr>JAR</abbr> file contains all the given files, no extra entry, and no outdated entry.
     *
     * @param fileSets pairs of base directory and files potentially relative to the base directory
     * @return whether the <abbr>JAR</abbr> file is up-to-date
     */
    boolean isUpToDateJAR(final Collection<Archive.FileSet> fileSets) {
        // No need to use JarFile because no need to handle META-INF in a special way.
        try (ZipFile jar = new ZipFile(jarFile.toFile())) {
            entries = jar.entries();
            for (Path file : filesInBuild) {
                if (!isFoundInJAR(file)) {
                    return false;
                }
            }
            for (Archive.FileSet fileSet : fileSets) {
                final Path baseDir = fileSet.directory;
                for (Path file : fileSet.files) {
                    file = baseDir.resolve(file);
                    if (!filesInBuild.remove(file)) { // For skipping the files already verified by above loop.
                        Files.walkFileTree(file, this);
                        if (hasUpdates) {
                            return false;
                        }
                    }
                }
            }
            // Check for remaining files in the JAR which were not in the build directory.
            for (Path file : filesInJAR) {
                if (!isIgnored(classesDir.relativize(file))) {
                    return false;
                }
            }
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!(entry.isDirectory() || isIgnored(Path.of(entry.getName())))) {
                    return false;
                }
            }
        } catch (IOException e) {
            logger.warn(e);
            return false;
        } finally {
            entries = null;
        }
        return true;
    }

    /**
     * Returns whether the given file in a <abbr>JAR</abbr> file should be ignored.
     * We have to ignore the files that are generated in a temporary directory
     * because they do not exist yet when the directory is traversed. Furthermore,
     * their timestamp would always be newer then the <abbr>JAR</abbr> file anyway.
     *
     * @param file path to a file relative to the root of the <abbr>JAR</abbr> file
     */
    private static boolean isIgnored(Path file) {
        if (file.startsWith(MetadataFiles.META_INF)) {
            file = file.subpath(1, file.getNameCount());
            if (file.startsWith(MetadataFiles.MANIFEST)) {
                return file.getNameCount() == 1;
            } else if (file.startsWith(MetadataFiles.MAVEN_DIR)) {
                return true; // Ignore all subdirectories.
            }
        }
        return false;
    }

    /**
     * Checks if the given file is new or more recent than the <abbr>JAR</abbr> file.
     * Checks also if the file exists in the <abbr>JAR</abbr> file.
     *
     * @param file the traversed file
     * @param attributes the file's basic attributes
     */
    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
        if (jarFileTime.compareTo(attributes.lastModifiedTime()) >= 0 && isFoundInJAR(file)) {
            return FileVisitResult.CONTINUE;
        } else {
            hasUpdates = true;
            return FileVisitResult.TERMINATE;
        }
    }

    /**
     * Returns whether the given file is found in the <abbr>JAR</abbr> file.
     *
     * @param file the file to check
     * @return whether the given file was found in the <abbr>JAR</abbr> file
     */
    private boolean isFoundInJAR(final Path file) {
        if (filesInJAR.remove(file)) {
            return true;
        }
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                Path p = classesDir.resolve(entry.getName());
                if (p.equals(file)) {
                    return true;
                }
                filesInJAR.add(p);
            }
        }
        return false;
    }
}
