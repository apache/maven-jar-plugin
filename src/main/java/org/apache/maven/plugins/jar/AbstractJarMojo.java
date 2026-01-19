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

import javax.lang.model.SourceVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;

import org.apache.maven.api.PathScope;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.shared.archiver.MavenArchiveConfiguration;
import org.apache.maven.shared.archiver.MavenArchiver;

/**
 * Base class for creating a <abbr>JAR</abbr> file from project classes.
 *
 * @author <a href="evenisse@apache.org">Emmanuel Venisse</a>
 * @author Martin Desruisseaux
 */
public abstract class AbstractJarMojo implements org.apache.maven.api.plugin.Mojo {
    /**
     * Identifier of the tool to use. This identifier shall match the identifier of a tool
     * registered as a {@link ToolProvider}. By default, the {@code "jar"} tool is used.
     *
     * @since 4.0.0-beta-2
     */
    @Parameter(defaultValue = "jar", required = true)
    private String toolId;

    /**
     * List of files to include. Specified as fileset patterns which are relative to the input directory whose contents
     * is being packaged into the JAR.
     */
    @Parameter
    private String[] includes;

    /**
     * List of files to exclude. Specified as fileset patterns which are relative to the input directory whose contents
     * is being packaged into the JAR.
     */
    @Parameter
    private String[] excludes;

    /**
     * Directory containing the generated <abbr>JAR</abbr> files.
     */
    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private Path outputDirectory;

    /**
     * Name of the generated <abbr>JAR</abbr> file.
     * The default value is {@code "${project.build.finalName}"},
     * which itself defaults to {@code "${artifactId}-${version}"}.
     * Ignored if the Maven sub-project to archive uses module hierarchy.
     */
    @Parameter(defaultValue = "${project.build.finalName}", readonly = true)
    private String finalName;

    /**
     * The Maven project.
     */
    @Inject
    private Project project;

    /**
     * The session.
     */
    @Inject
    private Session session;

    /**
     * The archive configuration to use. See <a href="http://maven.apache.org/shared/maven-archiver/index.html">Maven
     * Archiver Reference</a>.
     */
    @Parameter
    private MavenArchiveConfiguration archive = new MavenArchiveConfiguration();

    @Inject
    private ProjectManager projectManager;

    /**
     * Require the jar plugin to build new <abbr>JAR</abbr> files even if none of the contents appear to have changed.
     * By default, this plugin looks to see if the output <abbr>JAR</abbr> files exist and inputs have not changed.
     * If these conditions are true, the plugin skips creation of the <abbr>JAR</abbr> files.
     * This does not work when other plugins, like the maven-shade-plugin, are configured to post-process the JAR.
     * This plugin can not detect the post-processing, and so leaves the post-processed JAR file in place.
     * This can lead to failures when those plugins do not expect to find their own output as an input.
     * Set this parameter to {@code true} to avoid these problems by forcing this plugin to recreate the JAR every time.
     *
     * <p>Starting with <b>3.0.0</b> the property has been renamed from {@code jar.forceCreation}
     * to {@code maven.jar.forceCreation}.</p>
     */
    @Parameter(property = "maven.jar.forceCreation", defaultValue = "false")
    protected boolean forceCreation;

    /**
     * Skip creating empty archives.
     */
    @Parameter(defaultValue = "false")
    protected boolean skipIfEmpty;

    /**
     * Timestamp for reproducible output archive entries.
     * This is either formatted as ISO 8601 extended offset date-time
     * (e.g. in UTC such as '2011-12-03T10:15:30Z' or with an offset '2019-10-05T20:37:42+06:00'),
     * or as an integer representing seconds since the Java epoch (January 1st, 1970).
     * If not configured or disabled,
     * the <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>
     * environment variable is used as a fallback value,
     * to ease forcing Reproducible Build externally when the build has not enabled it natively in <abbr>POM</abbr>.
     *
     * @since 3.2.0
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /**
     * Whether to detect multi-release <abbr>JAR</abbr> files.
     * If the JAR contains the {@code META-INF/versions} directory it will be detected as a multi-release JAR file,
     * adding the {@code Multi-Release: true} attribute to the main section of the JAR {@code MANIFEST.MF} entry.
     * In addition, the class files in {@code META-INF/versions} will be checked for <abbr>API</abbr> compatibility
     * with the class files in the base version. If this flag is {@code false}, then the {@code META-INF/versions}
     * directories are included without processing.
     *
     * @since 3.4.0
     */
    @Parameter(property = "maven.jar.detectMultiReleaseJar", defaultValue = "true")
    protected boolean detectMultiReleaseJar;

    /**
     * Specifies whether to attach the <abbr>JAR</abbr> files to the project.
     *
     * @since 4.0.0-beta-2
     */
    @Parameter(property = "maven.jar.attach", defaultValue = "true")
    protected boolean attach;

    /**
     * The <abbr>MOJO</abbr> logger.
     */
    @Inject
    private Log log;

    /**
     * Creates a new <abbr>MOJO</abbr>.
     */
    protected AbstractJarMojo() {}

    /**
     * {@return the specific output directory to serve as the root for the archive}
     */
    protected abstract Path getClassesDirectory();

    /**
     * {@return the directory containing the generated <abbr>JAR</abbr> files}
     */
    protected Path getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * {@return the Maven session in which the project is built}
     */
    protected final Session getSession() {
        return session;
    }

    /**
     * {@return the Maven project}
     */
    protected final Project getProject() {
        return project;
    }

    /**
     * {@return the <abbr>MOJO</abbr> logger}
     */
    protected Log getLog() {
        return log;
    }

    /**
     * {@return the name of the generated <abbr>JAR</abbr> file}
     */
    protected String getFinalName() {
        return finalName;
    }

    /**
     * {@return the classifier of the <abbr>JAR</abbr> file to produce}
     * This is usually null or empty for the main artifact, or {@code "tests"} for the JAR file of test code.
     */
    protected abstract String getClassifier();

    /**
     * {@return the type of the JAR file to produce}
     * This is usually {@code "jar"} for the main artifact, or {@code "test-jar"} for the JAR file of test code.
     */
    protected abstract String getType();

    /**
     * {@return the scope of dependencies}
     * It should be {@link PathScope#MAIN_COMPILE} or {@link PathScope#TEST_COMPILE}.
     * Note that we use compile scope rather than runtime scope because dependencies
     * cannot appear in {@code requires} statement if they didn't had compile scope.
     */
    protected abstract PathScope getDependencyScope();

    /**
     * {@return the JAR tool to use for archiving the code}
     *
     * @throws MojoException if no JAR tool was found
     *
     * @since 4.0.0-beta-2
     */
    protected ToolProvider getJarTool() throws MojoException {
        return ToolProvider.findFirst(toolId).orElseThrow(() -> new MojoException("No such \"" + toolId + "\" tool."));
    }

    /**
     * Returns whether the specified Java version is supported.
     *
     * @param release name of an {@link SourceVersion} enumeration constant
     * @return whether the current environment support that version
     */
    private static boolean isSupported(String release) {
        try {
            return SourceVersion.latestSupported().compareTo(SourceVersion.valueOf(release)) >= 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Returns the output time stamp or, as a fallback, the {@code SOURCE_DATE_EPOCH} environment variable.
     * If the time stamp is expressed in seconds, it is converted to ISO 8601 format. Otherwise it is returned as-is.
     *
     * @return the time stamp in presumed ISO 8601 format, or {@code null} if none
     *
     * @since 4.0.0-beta-2
     */
    protected String getOutputTimestamp() {
        String time = nullIfAbsent(outputTimestamp);
        if (time == null) {
            time = nullIfAbsent(System.getenv("SOURCE_DATE_EPOCH"));
            if (time == null) {
                return null;
            }
        }
        if (!isSupported("RELEASE_19")) {
            log.warn("Reproducible build requires Java 19 or later.");
            return null;
        }
        for (int i = time.length(); --i >= 0; ) {
            char c = time.charAt(i);
            if ((c < '0' || c > '9') && (i != 0 || c != '-')) {
                return time;
            }
        }
        return Instant.ofEpochSecond(Long.parseLong(time)).toString();
    }

    /**
     * {@return the patterns of files to include, or an empty list if no include pattern was specified}
     */
    protected List<String> getIncludes() {
        return asList(includes);
    }

    /**
     * {@return the patterns of files to exclude, or an empty list if no exclude pattern was specified}
     */
    protected List<String> getExcludes() {
        return asList(excludes);
    }

    /**
     * Returns the given elements as a list if non-null.
     *
     * @param elements the elements, or {@code null}
     * @return the elements as a list, or {@code null} if the given array was null
     */
    private static List<String> asList(String[] elements) {
        return (elements == null) ? List.of() : Arrays.asList(elements);
    }

    /**
     * Generates the <abbr>JAR</abbr> files.
     * Map keys are module names or {@code null} if the project does not use module hierarchy.
     * Values are (<var>type</var>, <var>path</var>) pairs associated with each module where
     * <var>type</var> is {@code "pom"}, {@code "jar"} or {@code "test-jar"} and <var>path</var>
     * is the path to the <abbr>POM</abbr> or <abbr>JAR</abbr> file.
     *
     * <p>Note that a null key does not necessarily means that the <abbr>JAR</abbr> is not modular.
     * It only means that the project was not compiled with module hierarchy,
     * i.e. {@code target/classes/} subdirectories having module names.
     * A project can be compiled with package hierarchy and still be modular.</p>
     *
     * @return the paths to the created archive files
     * @throws IOException if an error occurred while walking the file tree
     * @throws MojoException if an error occurred while writing a <abbr>JAR</abbr> file
     */
    public Map<String, Map<String, Path>> createArchives() throws IOException, MojoException {
        final Path classesDirectory = getClassesDirectory();
        final boolean notExists = Files.notExists(classesDirectory);
        if (notExists) {
            if (forceCreation) {
                getLog().warn("No JAR created because no content was marked for inclusion.");
            }
            if (skipIfEmpty) {
                getLog().info(String.format("Skipping packaging of the %s.", getType()));
                return Map.of();
            }
        }
        archive.setForced(forceCreation);
        // TODO: we want a null manifest if there is no <archive> configuration.
        Manifest manifest = new MavenArchiver().getManifest(session, project, archive);
        var executor = new ToolExecutor(this, manifest, archive);
        var files = new FileCollector(this, executor, classesDirectory);
        if (!notExists) {
            Files.walkFileTree(classesDirectory, files);
        }
        files.prune(skipIfEmpty);
        List<Path> moduleRoots = files.getModuleHierarchyRoots();
        if (!moduleRoots.isEmpty()) {
            executor.pomDerivation = new PomDerivation(this, moduleRoots);
        }
        return executor.writeAllJARs(files);
    }

    /**
     * Generates the <abbr>JAR</abbr> file, then attaches the artifact.
     *
     * @throws MojoException in case of an error
     */
    @Override
    @SuppressWarnings("UseSpecificCatch")
    public void execute() throws MojoException {
        final Map<String, Map<String, Path>> artifactFiles;
        try {
            artifactFiles = createArchives();
        } catch (MojoException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoException("Error while assembling the JAR file.", e);
        }
        if (artifactFiles.isEmpty()) {
            // Message already logged by `createArchives()`.
            return;
        }
        if (attach) {
            final String classifier = nullIfAbsent(getClassifier());
            for (Map.Entry<String, Map<String, Path>> entry : artifactFiles.entrySet()) {
                String moduleName = entry.getKey();
                for (Map.Entry<String, Path> path : entry.getValue().entrySet()) {
                    ProducedArtifact artifact;
                    if (moduleName == null && classifier == null) {
                        // Note: the two maps on which we are iterating should contain only one entry in this case.
                        if (projectHasAlreadySetAnArtifact()) {
                            throw new MojoException("You have to use a classifier "
                                    + "to attach supplemental artifacts to the project instead of replacing them.");
                        }
                        artifact = project.getMainArtifact().orElseThrow();
                    } else {
                        artifact = session.createProducedArtifact(
                                project.getGroupId(),
                                (moduleName != null) ? moduleName : project.getArtifactId(),
                                project.getVersion(),
                                classifier,
                                null,
                                path.getKey());
                    }
                    projectManager.attachArtifact(project, artifact, path.getValue());
                }
            }
        } else {
            getLog().debug("Skipping attachment of the " + getType() + " artifact to the project.");
        }
    }

    /**
     * Verifies whether the main artifact is already set.
     * This verification does not apply for module hierarchy, where more than one artifact is produced.
     */
    private boolean projectHasAlreadySetAnArtifact() {
        return projectManager.getPath(project).filter(Files::isRegularFile).isPresent();
    }

    /**
     * Returns the given value if non-null, non-empty and non-blank, or {@code null} otherwise.
     */
    static String nullIfAbsent(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
