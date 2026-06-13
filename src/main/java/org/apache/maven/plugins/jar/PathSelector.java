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

import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.maven.api.annotations.Nonnull;

/**
 * Temporary copy of the {@code PathSelector} class in Maven core.
 * This class will be removed after the release of the Maven core version after 4.0.0-rc-4.
 * It is used for the transition from {@code org.apache.maven.shared} to Maven 4.0.0 core.
 */
final class PathSelector implements PathMatcher {
    /**
     * Maximum number of characters of the prefix before {@code ':'} for handling as a Maven syntax.
     */
    private static final int MAVEN_SYNTAX_THRESHOLD = 1;

    /**
     * The default syntax to use if none was specified. Note that when this default syntax is applied,
     * the user-provided pattern get some changes as documented in class Javadoc.
     */
    private static final String DEFAULT_SYNTAX = "glob:";

    /**
     * Characters having a special meaning in the glob syntax.
     *
     * @see FileSystem#getPathMatcher(String)
     */
    private static final String SPECIAL_CHARACTERS = "*?[]{}\\";

    /**
     * A path matcher which accepts all files.
     *
     * @see #simplify()
     */
    static final PathMatcher INCLUDES_ALL = (path) -> true;

    /**
     * String representations of the normalized include filters.
     * Each pattern shall be prefixed by its syntax, which is {@value #DEFAULT_SYNTAX} by default.
     * An empty array means to include all files.
     *
     * @see #toString()
     */
    private final String[] includePatterns;

    /**
     * String representations of the normalized exclude filters.
     * Each pattern shall be prefixed by its syntax. If no syntax is specified,
     * the default is a Maven 3 syntax similar, but not identical, to {@value #DEFAULT_SYNTAX}.
     * This array may be longer or shorter than the user-supplied excludes, depending on whether
     * default excludes have been added and whether some unnecessary excludes have been omitted.
     *
     * @see #toString()
     */
    private final String[] excludePatterns;

    /**
     * The matcher for includes. The length of this array is equal to {@link #includePatterns} array length.
     * An empty array means to include all files.
     */
    private final PathMatcher[] includes;

    /**
     * The matcher for excludes. The length of this array is equal to {@link #excludePatterns} array length.
     */
    private final PathMatcher[] excludes;

    /**
     * The matcher for all directories to include. This array includes the parents of all those directories,
     * because they need to be accepted before we can walk to the sub-directories.
     * This is an optimization for skipping whole directories when possible.
     * An empty array means to include all directories.
     */
    private final PathMatcher[] dirIncludes;

    /**
     * The matcher for directories to exclude. This array does <em>not</em> include the parent directories,
     * because they may contain other sub-trees that need to be included.
     * This is an optimization for skipping whole directories when possible.
     */
    private final PathMatcher[] dirExcludes;

    /**
     * The base directory. All files will be relativized to that directory before to be matched.
     */
    private final Path baseDirectory;

    /**
     * Whether paths must be relativized before being given to a matcher. If {@code true}, then every paths
     * will be made relative to {@link #baseDirectory} for allowing patterns like {@code "foo/bar/*.java"}
     * to work. As a slight optimization, we can skip this step if all patterns start with {@code "**"}.
     */
    private final boolean needRelativize;

    /**
     * Creates a new selector from the given includes and excludes.
     *
     * @param directory the base directory of the files to filter
     * @param includes the patterns of the files to include, or null or empty for including all files
     * @param excludes the patterns of the files to exclude, or null or empty for no exclusion
     * @throws NullPointerException if directory is null
     */
    private PathSelector(@Nonnull Path directory, Collection<String> includes, Collection<String> excludes) {
        baseDirectory = Objects.requireNonNull(directory, "directory cannot be null");
        includePatterns = normalizePatterns(includes, false);
        excludePatterns = normalizePatterns(effectiveExcludes(excludes, includePatterns), true);
        FileSystem fileSystem = baseDirectory.getFileSystem();
        this.includes = matchers(fileSystem, includePatterns);
        this.excludes = matchers(fileSystem, excludePatterns);
        dirIncludes = matchers(fileSystem, directoryPatterns(includePatterns, false));
        dirExcludes = matchers(fileSystem, directoryPatterns(excludePatterns, true));
        needRelativize = needRelativize(includePatterns) || needRelativize(excludePatterns);
    }

    /**
     * Creates a new matcher from the given includes and excludes.
     *
     * @param directory the base directory of the files to filter
     * @param includes the patterns of the files to include, or null or empty for including all files
     * @param excludes the patterns of the files to exclude, or null or empty for no exclusion
     * @throws NullPointerException if directory is null
     * @return a path matcher for the given includes and excludes
     */
    public static PathMatcher of(@Nonnull Path directory, Collection<String> includes, Collection<String> excludes) {
        return new PathSelector(directory, includes, excludes).simplify();
    }

    /**
     * Returns the given array of excludes, optionally expanded with a default set of excludes,
     * then with unnecessary excludes omitted. An unnecessary exclude is an exclude which will never
     * match a file because there are no includes which would accept a file that could match the exclude.
     * For example, if the only include is {@code "*.java"}, then the <code>"**&sol;project.pj"</code>,
     * <code>"**&sol;.DS_Store"</code> and other excludes will never match a file and can be omitted.
     * Because the list of {@linkplain #DEFAULT_EXCLUDES default excludes} contains many elements,
     * removing unnecessary excludes can reduce a lot the number of matches tested on each source file.
     *
     * <h4>Implementation note</h4>
     * The removal of unnecessary excludes is done on a best effort basis. The current implementation
     * compares only the prefixes and suffixes of each pattern, keeping the pattern in case of doubt.
     * This is not bad, but it does not remove all unnecessary patterns. It would be possible to do
     * better in the future if benchmarking suggests that it would be worth the effort.
     *
     * @param excludes the user-specified excludes, potentially not yet converted to glob syntax
     * @param includes the include patterns converted to glob syntax
     * @return the potentially expanded or reduced set of excludes to use
     */
    private static Collection<String> effectiveExcludes(Collection<String> excludes, final String[] includes) {
        if (excludes == null || excludes.isEmpty()) {
            return List.of();
        } else {
            excludes = new ArrayList<>(excludes);
            excludes.removeIf(Objects::isNull);
        }
        if (includes.length == 0) {
            return excludes;
        }
        /*
         * Get the prefixes and suffixes of all includes, stopping at the first special character.
         * Redundant prefixes and suffixes are omitted.
         */
        var prefixes = new String[includes.length];
        var suffixes = new String[includes.length];
        for (int i = 0; i < includes.length; i++) {
            String include = includes[i];
            if (!include.startsWith(DEFAULT_SYNTAX)) {
                return excludes; // Do not filter if at least one pattern is too complicated.
            }
            include = include.substring(DEFAULT_SYNTAX.length());
            prefixes[i] = prefixOrSuffix(include, false);
            suffixes[i] = prefixOrSuffix(include, true);
        }
        prefixes = sortByLength(prefixes, false);
        suffixes = sortByLength(suffixes, true);
        /*
         * Keep only the exclude which start with one of the prefixes and end with one of the suffixes.
         * Note that a prefix or suffix may be the empty string, which match everything.
         */
        final Iterator<String> it = excludes.iterator();
        nextExclude:
        while (it.hasNext()) {
            final String exclude = it.next();
            final int s = exclude.indexOf(':');
            if (s <= MAVEN_SYNTAX_THRESHOLD || exclude.startsWith(DEFAULT_SYNTAX)) {
                if (cannotMatch(exclude, prefixes, false) || cannotMatch(exclude, suffixes, true)) {
                    it.remove();
                }
            }
        }
        return excludes;
    }

    /**
     * Returns the maximal amount of ordinary characters at the beginning or end of the given pattern.
     * The prefix or suffix stops at the first {@linkplain #SPECIAL_CHARACTERS special character}.
     *
     * @param include the pattern for which to get a prefix or suffix without special character
     * @param suffix {@code false} if a prefix is desired, or {@code true} if a suffix is desired
     */
    private static String prefixOrSuffix(final String include, boolean suffix) {
        int s = suffix ? -1 : include.length();
        for (int i = SPECIAL_CHARACTERS.length(); --i >= 0; ) {
            char c = SPECIAL_CHARACTERS.charAt(i);
            if (suffix) {
                s = Math.max(s, include.lastIndexOf(c));
            } else {
                int p = include.indexOf(c);
                if (p >= 0 && p < s) {
                    s = p;
                }
            }
        }
        return suffix ? include.substring(s + 1) : include.substring(0, s);
    }

    /**
     * Returns {@code true} if the given exclude cannot match any include patterns.
     * In case of doubt, returns {@code false}.
     *
     * @param exclude the exclude pattern to test
     * @param fragments the prefixes or suffixes (fragments without special characters) of the includes
     * @param suffix {@code false} if the specified fragments are prefixes, {@code true} if they are suffixes
     * @return {@code true} if it is certain that the exclude pattern cannot match, or {@code false} in case of doubt
     */
    private static boolean cannotMatch(String exclude, final String[] fragments, final boolean suffix) {
        exclude = prefixOrSuffix(exclude, suffix);
        for (String fragment : fragments) {
            int fg = fragment.length();
            int ex = exclude.length();
            int length = Math.min(fg, ex);
            if (suffix) {
                fg -= length;
                ex -= length;
            } else {
                fg = 0;
                ex = 0;
            }
            if (exclude.regionMatches(ex, fragment, fg, length)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sorts the given patterns by their length. The main intent is to have the empty string first,
     * while will cause the loops testing for prefixes and suffixes to stop almost immediately.
     * Short prefixes or suffixes are also more likely to be matched.
     *
     * @param fragments the fragments to sort in-place
     * @param suffix {@code false} if the specified fragments are prefixes, {@code true} if they are suffixes
     * @return the given array, or a smaller array if some fragments were discarded because redundant
     */
    private static String[] sortByLength(final String[] fragments, final boolean suffix) {
        Arrays.sort(fragments, (s1, s2) -> s1.length() - s2.length());
        int count = 0;
        /*
         * Simplify the array of prefixes or suffixes by removing all redundant elements.
         * An element is redundant if there is a shorter prefix or suffix with the same characters.
         */
        nextBase:
        for (String fragment : fragments) {
            for (int i = count; --i >= 0; ) {
                String base = fragments[i];
                if (suffix ? fragment.endsWith(base) : fragment.startsWith(base)) {
                    continue nextBase; // Skip this fragment
                }
            }
            fragments[count++] = fragment;
        }
        return (fragments.length == count) ? fragments : Arrays.copyOf(fragments, count);
    }

    /**
     * Returns the given array of patterns with path separator normalized to {@code '/'}.
     * Null or empty patterns are ignored, and duplications are removed.
     *
     * @param patterns the patterns to normalize
     * @param excludes whether the patterns are exclude patterns
     * @return normalized patterns without null, empty or duplicated patterns
     */
    private static String[] normalizePatterns(final Collection<String> patterns, final boolean excludes) {
        if (patterns == null || patterns.isEmpty()) {
            return new String[0];
        }
        // TODO: use `LinkedHashSet.newLinkedHashSet(int)` instead with JDK19.
        final var normalized = new LinkedHashSet<String>(patterns.size());
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isEmpty()) {
                if (pattern.indexOf(':') <= MAVEN_SYNTAX_THRESHOLD) {
                    pattern = pattern.replace(File.separatorChar, '/');
                    if (pattern.endsWith("/")) {
                        pattern += "**";
                    }
                    // Following are okay only when "**" means "0 or more directories".
                    while (pattern.endsWith("/**/**")) {
                        pattern = pattern.substring(0, pattern.length() - 3);
                    }
                    while (pattern.startsWith("**/**/")) {
                        pattern = pattern.substring(3);
                    }
                    pattern = pattern.replace("/**/**/", "/**/");

                    // Escape special characters, including braces
                    // Braces from user input must be literals; we'll inject our own braces for expansion below
                    pattern = pattern.replace("\\", "\\\\")
                            .replace("[", "\\[")
                            .replace("]", "\\]")
                            .replace("{", "\\{")
                            .replace("}", "\\}");

                    // Transform ** patterns to use brace expansion for POSIX behavior
                    // This replaces the complex addPatternsWithOneDirRemoved logic
                    // We perform this after escaping so that only these injected braces participate in expansion
                    pattern = pattern.replace("**/", "{**/,}");

                    normalized.add(DEFAULT_SYNTAX + pattern);
                } else {
                    normalized.add(pattern);
                }
            }
        }
        return simplify(normalized, excludes);
    }

    /**
     * Applies some heuristic rules for simplifying the set of patterns,
     * then returns the patterns as an array.
     *
     * @param patterns the patterns to simplify and return as an array
     * @param excludes whether the patterns are exclude patterns
     * @return the set content as an array, after simplification
     */
    private static String[] simplify(Set<String> patterns, boolean excludes) {
        /*
         * If the "**" pattern is present, it makes all other patterns useless.
         * In the case of include patterns, an empty set means to include everything.
         */
        if (patterns.remove("**")) {
            patterns.clear();
            if (excludes) {
                patterns.add("**");
            }
        }
        return patterns.toArray(String[]::new);
    }

    /**
     * Eventually adds the parent directory of the given patterns, without duplicated values.
     * The patterns given to this method should have been normalized.
     *
     * @param patterns the normalized include or exclude patterns
     * @param excludes whether the patterns are exclude patterns
     * @return patterns of directories to include or exclude
     */
    private static String[] directoryPatterns(final String[] patterns, final boolean excludes) {
        // TODO: use `LinkedHashSet.newLinkedHashSet(int)` instead with JDK19.
        final var directories = new LinkedHashSet<String>(patterns.length);
        for (String pattern : patterns) {
            if (pattern.startsWith(DEFAULT_SYNTAX)) {
                if (excludes) {
                    if (pattern.endsWith("/**")) {
                        directories.add(pattern.substring(0, pattern.length() - 3));
                    }
                } else {
                    int s = pattern.indexOf(':');
                    if (pattern.regionMatches(++s, "**/", 0, 3)) {
                        s = pattern.indexOf('/', s + 3);
                        if (s < 0) {
                            return new String[0]; // Pattern is "**", so we need to accept everything.
                        }
                        directories.add(pattern.substring(0, s));
                    }
                }
            }
        }
        return simplify(directories, excludes);
    }

    /**
     * Returns {@code true} if at least one pattern requires path being relativized before to be matched.
     *
     * @param patterns include or exclude patterns
     * @return whether at least one pattern require relativization
     */
    private static boolean needRelativize(String[] patterns) {
        for (String pattern : patterns) {
            if (!pattern.startsWith(DEFAULT_SYNTAX + "**/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates the path matchers for the given patterns.
     * The syntax (usually {@value #DEFAULT_SYNTAX}) must be specified for each pattern.
     */
    private static PathMatcher[] matchers(final FileSystem fs, final String[] patterns) {
        final var matchers = new PathMatcher[patterns.length];
        for (int i = 0; i < patterns.length; i++) {
            matchers[i] = fs.getPathMatcher(patterns[i]);
        }
        return matchers;
    }

    /**
     * {@return a potentially simpler matcher equivalent to this matcher}.
     */
    @SuppressWarnings("checkstyle:MissingSwitchDefault")
    private PathMatcher simplify() {
        if (!needRelativize && excludes.length == 0) {
            switch (includes.length) {
                case 0:
                    return INCLUDES_ALL;
                case 1:
                    return includes[0];
            }
        }
        return this;
    }

    /**
     * Determines whether a path is selected.
     * This is true if the given file matches an include pattern and no exclude pattern.
     *
     * @param path the pathname to test, must not be {@code null}
     * @return {@code true} if the given path is selected, {@code false} otherwise
     */
    @Override
    public boolean matches(Path path) {
        if (needRelativize) {
            path = baseDirectory.relativize(path);
        }
        return (includes.length == 0 || isMatched(path, includes))
                && (excludes.length == 0 || !isMatched(path, excludes));
    }

    /**
     * {@return whether the given file matches according to one of the given matchers}.
     */
    private static boolean isMatched(Path path, PathMatcher[] matchers) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether {@link #couldHoldSelected(Path)} may return {@code false} for some directories.
     * This method can be used to determine if directory filtering optimization is possible.
     *
     * @return {@code true} if directory filtering is possible, {@code false} if all directories
     *         will be considered as potentially containing selected files
     */
    boolean canFilterDirectories() {
        return dirIncludes.length != 0 || dirExcludes.length != 0;
    }

    /**
     * Determines whether a directory could contain selected paths.
     *
     * @param directory the directory pathname to test, must not be {@code null}
     * @return {@code true} if the given directory might contain selected paths, {@code false} if the
     *         directory will definitively not contain selected paths
     */
    public boolean couldHoldSelected(Path directory) {
        if (baseDirectory.equals(directory)) {
            return true;
        }
        directory = baseDirectory.relativize(directory);
        return (dirIncludes.length == 0 || isMatched(directory, dirIncludes))
                && (dirExcludes.length == 0 || !isMatched(directory, dirExcludes));
    }

    /**
     * Appends the elements of the given array in the given buffer.
     * This is a helper method for {@link #toString()} implementations.
     *
     * @param buffer the buffer to add the elements to
     * @param label label identifying the array of elements to add
     * @param patterns the elements to append, or {@code null} if none
     */
    private static void append(StringBuilder buffer, String label, String[] patterns) {
        buffer.append(label).append(": [");
        if (patterns != null) {
            for (int i = 0; i < patterns.length; i++) {
                if (i != 0) {
                    buffer.append(", ");
                }
                buffer.append(patterns[i]);
            }
        }
        buffer.append(']');
    }

    /**
     * {@return a string representation for logging purposes}.
     */
    @Override
    public String toString() {
        var buffer = new StringBuilder();
        append(buffer, "includes", includePatterns);
        append(buffer.append(", "), "excludes", excludePatterns);
        return buffer.toString();
    }
}
