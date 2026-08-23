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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * A list of files to temporarily move outside the directory to package in a <abbr>JAR</abbr> archive.
 * This is used for excluding files from the <abbr>JAR</abbr> archive according include/exclude filters.
 * We move these files for making possible to specify the whole directory to the {@code jar} tool.
 * This approach is used instead of enumerating files in arguments given to the {@code jar} tool because
 * such enumeration can not contain directory entries (otherwise the whole directory would be included).
 * Some software such as Spring applications component scan relies on the presence of directory entries.
 */
final class ExcludedFiles implements Closeable {
    /**
     * The paths of files or directories to temporarily move in another directory.
     */
    private final Path[] original;

    /**
     * The paths where files or directories were moved.
     * For each index <var>i</var>, the original path of {@code moved[i]} was {@code original[i]}.
     */
    private final Path[] moved;

    /**
     * Temporary directory which will contain the {@code moved} files.
     * It should be a parent directory of {@link #original} files for
     * increasing the chances that it is on the same file system.
     */
    private final Path temporaryDirectory;

    /**
     * Creates a new list of files to move in a temporary directory.
     *
     * @param directory the directory which was scanned for files to include in the <abbr>JAR</abbr>
     * @param files paths of files or directories to temporarily move in another directory
     * @throws IOException if an error occurred while creating the temporary directory.
     */
    ExcludedFiles(final Path directory, final List<Path> files) throws IOException {
        original = files.toArray(Path[]::new);
        moved = new Path[original.length];
        temporaryDirectory = Files.createTempDirectory(directory, "excluded-");
    }

    /**
     * Moves the files now. This method should be invoked inside the "try with resource" block.
     *
     * @throws IOException if an error occurred while moving a file.
     */
    public void move() throws IOException {
        for (int i = 0; i < original.length; i++) {
            final Path source = original[i];
            String prefix = source.getFileName().toString();
            String suffix = null;
            int s = prefix.lastIndexOf('.');
            if (s > 0) {
                suffix = prefix.substring(s);
                prefix = prefix.substring(0, s);
            }
            Path target = Files.createTempFile(temporaryDirectory, prefix, suffix);
            try {
                moved[i] = Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                try {
                    Files.delete(target);
                } catch (IOException e2) {
                    e.addSuppressed(e2);
                }
                throw e;
            }
        }
    }

    /**
     * Moves the temporary files back to their original locations.
     * This method can be invoked even if only a subset of the files were moved.
     * The latter may happen if an error occurred in the middle of {@link #move()} execution.
     *
     * @throws IOException if an error occurred while moving a temporary files or deleting the temporary directory.
     */
    @Override
    public void close() throws IOException {
        IOException e = null;
        for (int i = moved.length; --i >= 0; ) {
            final Path source = moved[i];
            if (source != null) {
                final Path target = original[i];
                try {
                    Files.move(source, target);
                    moved[i] = null;
                } catch (IOException s) {
                    if (e != null) {
                        e.addSuppressed(s);
                    } else {
                        e = s;
                    }
                }
            }
        }
        if (e != null) {
            throw e;
            // Do not try to delete the temporary directory because it is non-empty.
        }
        Files.delete(temporaryDirectory);
    }
}
