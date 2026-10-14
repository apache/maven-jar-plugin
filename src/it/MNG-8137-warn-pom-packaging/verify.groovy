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

import java.nio.file.*;

// No JAR should be created.
File jar = new File(basedir, "target/maven-jar-plugin-test-mng-8137-1.0.jar")
assert !jar.exists() : "JAR must NOT be created for pom packaging, but found: " + jar

// The build log must contain the expected warning.
String log = new String(Files.readAllBytes(basedir.toPath().resolve("build.log")), "UTF-8")
String warning = "does not produce a main artifact"
assert log.contains(warning) : "Expected warning containing '" + warning + "' not found in build.log"

// The build must succeed (no BUILD FAILURE).
assert !log.contains("BUILD FAILURE") : "Build must succeed but BUILD FAILURE found in build.log"

return true
