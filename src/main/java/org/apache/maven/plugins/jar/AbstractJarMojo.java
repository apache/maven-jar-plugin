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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.stream.Stream;

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
import org.apache.maven.shared.archiver.MavenArchiverException;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.jar.JarArchiver;

/**
 * Base class for creating a <abbr>JAR</abbr> file from project classes.
 *
 * @author <a href="evenisse@apache.org">Emmanuel Venisse</a>
 */
public abstract class AbstractJarMojo implements org.apache.maven.api.plugin.Mojo {

    private static final String[] DEFAULT_EXCLUDES = new String[] {"**/package.html"};

    private static final String[] DEFAULT_INCLUDES = new String[] {"**/**"};

    private static final String MODULE_DESCRIPTOR_FILE_NAME = "module-info.class";

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
     * Directory containing the generated JAR.
     */
    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private Path outputDirectory;

    /**
     * Name of the generated JAR.
     */
    @Parameter(defaultValue = "${project.build.finalName}", readonly = true)
    private String finalName;

    /**
     * The JAR archiver.
     */
    @Inject
    private Map<String, Archiver> archivers;

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
     * Require the jar plugin to build a new JAR even if none of the contents appear to have changed.
     * By default, this plugin looks to see if the output JAR exists and inputs have not changed.
     * If these conditions are true, the plugin skips creation of the JAR file.
     * This does not work when other plugins, like the maven-shade-plugin, are configured to post-process the JAR.
     * This plugin can not detect the post-processing, and so leaves the post-processed JAR file in place.
     * This can lead to failures when those plugins do not expect to find their own output as an input.
     * Set this parameter to {@code true} to avoid these problems by forcing this plugin to recreate the JAR every time.
     *
     * <p>Starting with <b>3.0.0</b> the property has been renamed from {@code jar.forceCreation}
     * to {@code maven.jar.forceCreation}.</p>
     */
    @Parameter(property = "maven.jar.forceCreation", defaultValue = "false")
    private boolean forceCreation;

    /**
     * Skip creating empty archives.
     */
    @Parameter(defaultValue = "false")
    private boolean skipIfEmpty;

    /**
     * Timestamp for reproducible output archive entries.
     * This is either formatted as ISO 8601 extended offset date-time
     * (e.g. in UTC such as '2011-12-03T10:15:30Z' or with an offset '2019-10-05T20:37:42+06:00'),
     * or as an integer representing seconds since the epoch
     * (like <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @since 3.2.0
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /**
     * Whether to detect multi-release JAR files.
     * If the JAR contains the {@code META-INF/versions} directory it will be detected as a multi-release JAR file
     * ("MRJAR"), adding the {@code Multi-Release: true} attribute to the main section of the JAR {@code MANIFEST.MF}.
     *
     * @since 3.4.0
     */
    @Parameter(property = "maven.jar.detectMultiReleaseJar", defaultValue = "true")
    private boolean detectMultiReleaseJar;

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
     * Specifies whether to attach the jar to the project.
     *
     * @since 4.0.0-beta-2
     */
    @Parameter(property = "maven.jar.attach", defaultValue = "true")
    protected boolean attach;

    /**
     * {@return the specific output directory to serve as the root for the archive}
     */
    protected abstract Path getClassesDirectory();

    /**
     * Return the {@linkplain #project Maven project}.
     *
     * @return the Maven project
     */
    protected final Project getProject() {
        return project;
    }

    /**
     * {@return the <abbr>MOJO</abbr> logger}
     */
    protected final Log getLog() {
        return log;
    }

    /**
     * {@return the classifier of the JAR file to produce}
     * This is usually null or empty for the main artifact, or {@code "tests"} for the JAR file of test code.
     */
    protected abstract String getClassifier();

    /**
     * {@return the type of the JAR file to produce}
     * This is usually {@code "jar"} for the main artifact, or {@code "test-jar"} for the JAR file of test code.
     */
    protected abstract String getType();

    /**
     * Returns the JAR file to generate, based on an optional classifier.
     *
     * @param basedir the output directory
     * @param resultFinalName the name of the JAR file
     * @param classifier an optional classifier
     * @return the file to generate
     */
    protected Path getJarFile(Path basedir, String resultFinalName, String classifier) {
        Objects.requireNonNull(basedir, "basedir is not allowed to be null");
        Objects.requireNonNull(resultFinalName, "finalName is not allowed to be null");
        String fileName = resultFinalName + (hasClassifier(classifier) ? '-' + classifier : "") + ".jar";
        return basedir.resolve(fileName);
    }

    /**
     * Generates the JAR.
     *
     * @return the path to the created archive file
     * @throws MojoException in case of an error
     */
    public Path createArchive() throws MojoException {
        Path jarFile = getJarFile(outputDirectory, finalName, getClassifier());

        FileSetManager fileSetManager = new FileSetManager();
        FileSet jarContentFileSet = new FileSet();
        jarContentFileSet.setDirectory(getClassesDirectory().toAbsolutePath().toString());
        jarContentFileSet.setIncludes(Arrays.asList(getIncludes()));
        jarContentFileSet.setExcludes(Arrays.asList(getExcludes()));

        String[] includedFiles = fileSetManager.getIncludedFiles(jarContentFileSet);

        if (detectMultiReleaseJar
                && Arrays.stream(includedFiles)
                        .anyMatch(
                                p -> p.startsWith("META-INF" + File.separatorChar + "versions" + File.separatorChar))) {
            getLog().debug("Adding 'Multi-Release: true' manifest entry.");
            archive.addManifestEntry(Attributes.Name.MULTI_RELEASE.toString(), "true");
        }

        // May give false positives if the files is named as module descriptor
        // but is not in the root of the archive or in the versioned area
        // (and hence not actually a module descriptor).
        // That is fine since the modular Jar archiver will gracefully
        // handle such case.
        // And also such case is unlikely to happen as file ending
        // with "module-info.class" is unlikely to be included in Jar file
        // unless it is a module descriptor.
        boolean containsModuleDescriptor =
                Arrays.stream(includedFiles).anyMatch(p -> p.endsWith(MODULE_DESCRIPTOR_FILE_NAME));

        String archiverName = containsModuleDescriptor ? "mjar" : "jar";

        MavenArchiver archiver = new MavenArchiver();
        archiver.setCreatedBy("Maven JAR Plugin", "org.apache.maven.plugins", "maven-jar-plugin");
        archiver.setArchiver((JarArchiver) archivers.get(archiverName));
        archiver.setOutputFile(jarFile.toFile());

        // configure for Reproducible Builds based on outputTimestamp value
        archiver.configureReproducibleBuild(outputTimestamp);

        archive.setForced(forceCreation);

        try {
            Path contentDirectory = getClassesDirectory();
            if (!Files.exists(contentDirectory)) {
                if (!forceCreation) {
                    getLog().warn("JAR will be empty - no content was marked for inclusion!");
                }
            } else {
                archiver.getArchiver().addDirectory(contentDirectory.toFile(), getIncludes(), getExcludes());
            }

            archiver.createArchive(session, project, archive);

            return jarFile;
        } catch (Exception e) {
            // TODO: improve error handling
            throw new MojoException("Error assembling JAR", e);
        }
    }

    /**
     * Generates the JAR.
     *
     * @throws MojoException in case of an error
     */
    @Override
    public void execute() throws MojoException {
        if (skipIfEmpty && isEmpty(getClassesDirectory())) {
            getLog().info(String.format("Skipping packaging of the %s.", getType()));
        } else {
            Path jarFile = createArchive();
            ProducedArtifact artifact;
            String classifier = getClassifier();
            if (attach) {
                if (hasClassifier(classifier)) {
                    artifact = session.createProducedArtifact(
                            project.getGroupId(),
                            project.getArtifactId(),
                            project.getVersion(),
                            classifier,
                            null,
                            getType());
                } else {
                    if (projectHasAlreadySetAnArtifact()) {
                        throw new MojoException("You have to use a classifier "
                                + "to attach supplemental artifacts to the project instead of replacing them.");
                    }
                    artifact = project.getMainArtifact().get();
                }
                projectManager.attachArtifact(project, artifact, jarFile);
            } else {
                getLog().debug("Skipping attachment of the " + getType() + " artifact to the project.");
            }
        }
    }

    private static boolean isEmpty(Path directory) {
        if (!Files.isDirectory(directory)) {
            return true;
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.findAny().isEmpty();
        } catch (IOException e) {
            throw new MavenArchiverException("Unable to access directory", e);
        }
    }

    private boolean projectHasAlreadySetAnArtifact() {
        Path path = projectManager.getPath(project).orElse(null);
        return path != null && Files.isRegularFile(path);
    }

    /**
     * Return {@code true} if the classifier is not {@code null} and contains something other than white spaces.
     *
     * @param classifier the classifier to verify
     * @return {@code true} if the classifier is set
     */
    private static boolean hasClassifier(String classifier) {
        return classifier != null && !classifier.isBlank();
    }

    private String[] getIncludes() {
        if (includes != null && includes.length > 0) {
            return includes;
        }
        return DEFAULT_INCLUDES;
    }

    private String[] getExcludes() {
        if (excludes != null && excludes.length > 0) {
            return excludes;
        }
        return DEFAULT_EXCLUDES;
    }
}
