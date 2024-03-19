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

import javax.inject.Named;
import javax.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.toolchain.Toolchain;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Component provided JDK specification based on toolchains.
 */
@Named
@Singleton
class ToolchainsJdkSpecification {

    private final Logger logger = LoggerFactory.getLogger(ToolchainsJdkSpecification.class);

    private final Map<Path, String> cache = new HashMap<>();

    public synchronized Optional<String> getJDKSpecification(Toolchain toolchain) {
        Optional<Path> javacPath = getJavacPath(toolchain);
        return javacPath.map(path -> cache.computeIfAbsent(path, this::getSpecForPath));
    }

    private Optional<Path> getJavacPath(Toolchain toolchain) {
        return Optional.ofNullable(toolchain.findTool("javac")).map(Paths::get).map(this::getCanonicalPath);
    }

    private Path getCanonicalPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            if (path.getParent() != null) {
                return getCanonicalPath(path.getParent()).resolve(path.getFileName());
            } else {
                throw new UncheckedIOException(e);
            }
        }
    }

    private String getSpecForPath(Path path) {
        try {
            Commandline cl = new Commandline(path.toString());
            cl.createArg().setValue("-version");
            CommandLineUtils.StringStreamConsumer out = new CommandLineUtils.StringStreamConsumer();
            CommandLineUtils.StringStreamConsumer err = new CommandLineUtils.StringStreamConsumer();
            CommandLineUtils.executeCommandLine(cl, out, err);
            String version = out.getOutput().trim();
            if (version.isEmpty()) {
                version = err.getOutput().trim();
            }
            if (version.startsWith("javac ")) {
                version = version.substring(6);
                if (version.startsWith("1.")) {
                    version = version.substring(0, 3);
                } else {
                    version = version.substring(0, 2);
                }
                return version;
            } else {
                logger.warn("Unrecognized output form " + path + " -version - " + version);
                return null;
            }
        } catch (CommandLineException | IndexOutOfBoundsException e) {
            logger.warn("Failed to execute: " + path + " - " + e.getMessage());
            return null;
        }
    }
}
