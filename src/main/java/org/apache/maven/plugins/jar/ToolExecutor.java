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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.spi.ToolProvider;

import org.apache.maven.api.Project;
import org.apache.maven.api.Type;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.shared.archiver.MavenArchiveConfiguration;

/**
 * Writer of <abbr>JAR</abbr> files using the information collected by {@link FileCollector}.
 * This class uses the {@code "jar"} tool provided with the <abbr>JDK</abbr>.
 */
final class ToolExecutor {
    /**
     * The Maven project for which to create an archive.
     */
    final Project project;

    /**
     * {@code "jar"} or {@link "test-jar"}.
     */
    private final String artifactType;

    /**
     * The output directory where to write the <abbr>JAR</abbr> file.
     * This is usually {@code ${baseDir}/target/}.
     */
    private final Path outputDirectory;

    /**
     * The <abbr>JAR</abbr> file name when package hierarchy is used.
     * This is usually a file placed in the {@link ToolExecutor#outputDirectory} directory.
     */
    private final String finalName;

    /**
     * The classifier (e.g. "test"), or {@code null} if none.
     */
    private final String classifier;

    /**
     * The tool to use for creating the <abbr>JAR</abbr> files.
     */
    private final ToolProvider tool;

    /**
     * Where to send messages emitted by the "jar" tool.
     */
    private final PrintWriter messageWriter;

    /**
     * Where to send error messages emitted by the "jar" tool.
     */
    private final PrintWriter errorWriter;

    /**
     * Where the messages sent to {@link #messageWriter} are stored.
     */
    private final StringBuffer messages;

    /**
     * Where the messages sent to {@link #errorWriter} are stored.
     */
    private final StringBuffer errors;

    /**
     * A buffer for the arguments given to the "jar" tool, reused for each module.
     * Each element of the list shall be instances of either {@link String} or {@link Path}.
     */
    private final List<Object> arguments;

    /**
     * The paths to the created archive files.
     * Map keys are module names or {@code null} if the project does not use module hierarchy.
     * Values are (<var>type</var>, <var>path</var>) pairs associated with each module where
     * <var>type</var> is {@code "pom"}, {@code "jar"} or {@code "test-jar"} and <var>path</var>
     * is the path to the <abbr>POM</abbr> or <abbr>JAR</abbr> file.
     */
    private final Map<String, Map<String, Path>> result;

    /**
     * Mapper from Maven dependencies to Java modules, or {@code null} if the project does not use module hierarchy.
     * This mapper is created only once for a Maven project and reused for each Java module to archive.
     *
     * <p>This field is not used directly by {@code ToolExecutor}. It is defined in this class for transferring
     * this information from {@link AbstractJarMojo} to {@link PomDerivation.ForModule}.
     * This is an internal mechanism that should not be public or protected.</p>
     */
    PomDerivation pomDerivation;

    /**
     * Manifest to merge with the manifest found in the files to archive.
     * This is a manifest built from the {@code <archive>} plugin configuration.
     * Can be {@code null} if there is noting to add to the existing manifests.
     */
    private final Manifest manifestFromPlugin;

    /**
     * The file from which {@link #manifestFromPlugin} has been read, or {@code null} if none.
     * If non-null, reading that file shall produce the same manifest as {@link #manifestFromPlugin}.
     * It implies that this field shall be {@code null} if {@link #manifestFromPlugin} is the result
     * of merging elements specified in {@code <archive>} with a file specified in the plugin configuration.
     */
    private final Path manifestFile;

    /**
     * The archive configuration to use.
     */
    private final MavenArchiveConfiguration archiveConfiguration;

    /**
     * The timestamp in ISO-8601 extended offset date-time, or {@code null} if none.
     * If user provided a value in seconds, it shall have been converted to ISO-8601.
     * This is used for reproducible builds.
     */
    private final String outputTimestamp;

    /**
     * Whether to force to build new <abbr>JAR</abbr> files even if none of the contents appear to have changed.
     */
    private final boolean forceCreation;

    /**
     * Where to send informative or error messages.
     */
    private final Log logger;

    /**
     * Creates a new writer.
     *
     * @param mojo the <abbr>MOJO</abbr> from which to get the configuration
     * @param manifest manifest built from plugin configuration, or {@code null} if none
     * @param archive the archive configuration
     * @throws IOException if an error occurred while reading the manifest file
     */
    ToolExecutor(AbstractJarMojo mojo, Manifest manifest, MavenArchiveConfiguration archive) throws IOException {
        project = mojo.getProject();
        artifactType = mojo.getType();
        outputDirectory = mojo.getOutputDirectory();
        classifier = AbstractJarMojo.nullIfAbsent(mojo.getClassifier());
        finalName = mojo.getFinalName();
        forceCreation = mojo.forceCreation;
        outputTimestamp = mojo.getOutputTimestamp();
        logger = mojo.getLog();
        tool = mojo.getJarTool();

        var buffer = new StringWriter();
        messages = buffer.getBuffer();
        messageWriter = new PrintWriter(buffer);

        buffer = new StringWriter();
        errors = buffer.getBuffer();
        errorWriter = new PrintWriter(buffer);

        arguments = new ArrayList<>();
        result = new LinkedHashMap<>();
        archiveConfiguration = archive;

        Path file = archive.getManifestFile();
        if (file != null) {
            try (InputStream in = Files.newInputStream(file)) {
                // No need to wrap in `BufferedInputStream`.
                if (manifest != null) {
                    manifest.read(in);
                    file = null; // Because the manifest is the result of a merge.
                } else {
                    manifest = new Manifest(in);
                }
            }
        }
        if (manifest != null) {
            if (mojo.detectMultiReleaseJar) {
                manifest.getMainAttributes().remove(Attributes.Name.MULTI_RELEASE);
            }
            if (!isReproducible()) {
                // If reproducible build was not requested, let the tool declares itself.
                // This is a workaround until we port Maven archiver to this JAR plugin.
                manifest.getMainAttributes().remove("Created-By");
            }
        }
        manifestFromPlugin = manifest;
        manifestFile = file;
    }

    /**
     * Whether reproducible build was requested.
     * In current version, the output time stamp is used as a sentinel value.
     */
    public boolean isReproducible() {
        return outputTimestamp != null;
    }

    /**
     * Creates an initially empty archive for <abbr>JAR</abbr> file to generate.
     * This method does not create the <abbr>JAR</abbr> file immediately,
     * but collect information for creating the file later.
     *
     * @param  moduleName the module name if using module hierarchy, or {@code null} if using package hierarchy
     * @param  directory the directory of the classes targeting the base Java release
     */
    Archive newArchive(final String moduleName, final Path directory) {
        var sb = new StringBuilder(60);
        if (moduleName != null) {
            sb.append(moduleName).append('-').append(project.getVersion());
        } else {
            sb.append(finalName);
        }
        if (classifier != null) {
            sb.append('-').append(classifier);
        }
        String filename = sb.append(".jar").toString();
        return new Archive(outputDirectory.resolve(filename), moduleName, directory, forceCreation, logger);
    }

    /**
     * Writes all <abbr>JAR</abbr> files, together with their derived <abbr>POM</abbr> files if applicable.
     * The derived <abbr>POM</abbr> files are the intersections of the project <abbr>POM</abbr> with the
     * content of {@code module-info.class} files.
     *
     * <h4>Prerequisites</h4>
     * The {@link FileCollector#prune(boolean)} method should have been invoked once before to invoke this method.
     *
     * @param files the result of scanning the build directory for listing the files or directories to archive
     * @return the paths to the created archive files
     * @throws MojoException if an error occurred during the execution of the "jar" tool
     * @throws IOException if an error occurred while reading or writing a manifest file
     */
    @SuppressWarnings("ReturnOfCollectionOrArrayField")
    public Map<String, Map<String, Path>> writeAllJARs(final FileCollector files) throws IOException {
        Path ignored = files.handleOrphanFiles();
        Path parentPOM = files.writeAllJARs(this);
        if (ignored != null) {
            logger.warn("Some files in \"" + relativize(outputDirectory, ignored)
                    + "\" were ignored because they belong to no module.");
        }
        if (parentPOM != null) {
            if (result.put(null, Map.of(Type.POM, parentPOM)) != null) {
                throw new MojoException("Internal error."); // Should never happen.
            }
        }
        return result;
    }

    /**
     * Creates the <abbr>JAR</abbr> files for the specified set of files.
     * If the operation fails, an error message may be available in the {@link #errors} buffer.
     *
     * @param files the result of scanning the build directory for listing the files or directories to archive
     * @throws MojoException if an error occurred during the execution of the "jar" tool
     * @throws IOException if an error occurred while reading or writing a manifest file
     */
    void writeSingleJAR(final FileCollector files, final Archive archive) throws IOException {
        final Path relativePath = relativize(project.getRootDirectory(), archive.jarFile);
        if (archive.isUpToDateJAR()) {
            logger.info("Keep up-to-date JAR: \"" + relativePath + "\".");
            archive.saveArtifactPaths(artifactType, result);
            return;
        }
        logger.info("Building JAR: \"" + relativePath + "\".");
        /*
         * If `MANIFEST.MF` entries were specified by JAR plugin configuration,
         * merge those entries with the content of `MANIFEST.MF` file found in
         * the files to archive.
         */
        boolean writeTemporaryManifest = (manifestFromPlugin != null && manifestFile == null); // Check <archive>.
        final Manifest manifest = archive.mergeManifest(manifestFile, manifestFromPlugin);
        if (manifest != manifestFromPlugin) {
            writeTemporaryManifest |= (manifestFromPlugin != null); // Check if a merge of two manifests.
        }
        writeTemporaryManifest |= archive.setMainClass(manifest);
        if (manifest != null) {
            String name = manifest.getMainAttributes().getValue("Automatic-Module-Name");
            if (name != null && !SourceVersion.isName(name)) {
                throw new MojoException("Invalid automatic module name: \"" + name + "\".");
            }
        }
        /*
         * Creates temporary files for META-INF (if the existing file cannot be used directly)
         * and for the Maven metadata (if requested). The temporary files are in the `target`
         * directory and will be deleted, unless the build fails or is run in verbose mode.
         */
        try (MetadataFiles metadata = new MetadataFiles(project, outputDirectory)) {
            if (writeTemporaryManifest) {
                archive.setManifest(metadata.addManifest(manifest), true);
            }
            if (archive.moduleName != null) {
                metadata.deriveModulePOM(this, archive, manifest);
            }
            if (archiveConfiguration.isAddMavenDescriptor()) {
                archive.mavenFiles = metadata.addPOM(archiveConfiguration, isReproducible());
            }
            /*
             * Prepare the arguments to send to the `jar` tool and log a message.
             */
            arguments.add("--create");
            if (!archiveConfiguration.isCompress()) {
                arguments.add("--no-compress");
            }
            if (outputTimestamp != null) {
                arguments.add("--date");
                arguments.add(outputTimestamp);
            }
            archive.arguments(arguments);
            /*
             * Execute the `jar` tool with arguments determined by the values dispatched
             * in the various fields of the `Archive`. Information and error essages are logged.
             */
            String[] options = new String[arguments.size()];
            Arrays.setAll(options, (i) -> arguments.get(i).toString());
            int status = tool.run(messageWriter, errorWriter, options);
            if (!messages.isEmpty()) {
                logger.info(messages);
            }
            if (!errors.isEmpty()) {
                logger.error(errors);
            }
            if (status != 0 || logger.isDebugEnabled()) {
                Path debugFile = archive.writeDebugFile(project.getBasedir(), outputDirectory, classifier, arguments);
                metadata.cancelFileDeletion();
                if (status != 0) {
                    logCommandLineTip(project.getBasedir(), debugFile);
                    String error = errors.toString().strip();
                    if (error.isEmpty()) {
                        error = "unspecified error.";
                    }
                    throw new MojoException("Cannot create the \"" + relativePath + "\" archive file: " + error);
                }
            }
        }
        arguments.clear();
        errors.setLength(0);
        messages.setLength(0);
        archive.saveArtifactPaths(artifactType, result);
    }

    /**
     * Sends an error message to the logger if non-blank, then log a tip for testing from the command-line.
     *
     * @param baseDir the project base directory, or {@code null}
     * @param debugFile the file containing the "jar" tool arguments
     */
    private void logCommandLineTip(Path baseDir, Path debugFile) {
        final var commandLine = new StringBuilder("For trying to archive from the command-line, use:");
        if (baseDir != null) {
            debugFile = relativize(baseDir, debugFile);
            baseDir = relativize(Path.of(System.getProperty("user.dir")), baseDir);
            String chdir = baseDir.toString();
            if (!chdir.isEmpty()) {
                boolean isWindows = (File.separatorChar == '\\');
                commandLine
                        .append(System.lineSeparator())
                        .append("    ")
                        .append(isWindows ? "chdir " : "cd ")
                        .append(chdir);
            }
        }
        commandLine
                .append(System.lineSeparator())
                .append("    ")
                .append(tool.name())
                .append(" @")
                .append(debugFile);
        logger.info(commandLine);
    }

    /**
     * Logs a warning saying that a {@code META-INF/versions/} directory cannot be parsed as a version number.
     *
     * @param path the directory
     * @param e the exception that occurred while trying to parse the directory name
     */
    void warnInvalidVersion(Path path, IllegalArgumentException e) {
        var message = new StringBuilder(160)
                .append("The \"")
                .append(relativize(outputDirectory, path))
                .append("\" directory cannot be parsed as a version number.");
        String cause = e.getMessage();
        if (cause != null) {
            message.append(System.lineSeparator()).append("Caused by: ").append(cause);
        }
        logger.warn(message, e);
    }

    /**
     * Tries to return the given directory relative to the given base.
     * If any directory is null, or if the directory cannot be relativized,
     * returns the directory unchanged (usually as an absolute path).
     */
    private static Path relativize(Path base, Path dir) {
        if (base != null && dir != null) {
            try {
                return base.relativize(dir);
            } catch (IllegalArgumentException e) {
                // Ignore, keep the absolute path.
            }
        }
        return dir;
    }
}
