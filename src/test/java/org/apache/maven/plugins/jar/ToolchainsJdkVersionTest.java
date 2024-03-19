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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

import org.apache.maven.toolchain.Toolchain;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolchainsJdkVersionTest {

    @Mock
    private Toolchain toolchain;

    private final ToolchainsJdkSpecification toolchainsJdkSpecification = new ToolchainsJdkSpecification();

    @Test
    void shouldReturnCorrectSpec() {

        Path javacPath = getJavacPath();
        Assumptions.assumeTrue(Files.isExecutable(javacPath));

        when(toolchain.findTool("javac")).thenReturn(javacPath.toString());

        Optional<String> jdkVersion = toolchainsJdkSpecification.getJDKSpecification(toolchain);
        Assertions.assertTrue(jdkVersion.isPresent());
        Assertions.assertEquals(System.getProperty("java.specification.version"), jdkVersion.get());
    }

    @Test
    void shouldReturnEmptySpec() {

        when(toolchain.findTool("javac")).thenReturn(null);

        Optional<String> jdkVersion = toolchainsJdkSpecification.getJDKSpecification(toolchain);
        Assertions.assertFalse(jdkVersion.isPresent());
    }

    private String getJavaCName() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win")) {
            return "javac.exe";
        } else {
            return "javac";
        }
    }

    private Path getJavacPath() {
        String javaCName = getJavaCName();

        String javaHome = System.getProperty("java.home");

        Path javacPath = Paths.get(javaHome, "bin", javaCName);
        if (Files.isExecutable(javacPath)) {
            return javacPath;
        }

        // try with jre
        return Paths.get(javaHome, "../bin", javaCName);
    }
}
