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

import java.util.jar.*;

/*
 * MJAR-596: An invalid Automatic-Module-Name read from a MANIFEST.MF file (not from POM
 * <manifestEntries>) must be sanitized to a valid name instead of failing the build.
 *
 * The MANIFEST.MF resource sets:
 *   Automatic-Module-Name: org.apache.maven.plugins.mjar596-integration-test
 * The hyphen is invalid in a JPMS module name.  The plugin must:
 *   - succeed (BUILD SUCCESS)
 *   - sanitize the name to:  org.apache.maven.plugins.mjar596.integration.test
 *   - emit a [WARNING] about the sanitization
 */

// 1. Build must succeed: target directory and artifact must exist.
File artifact = new File( basedir, "target/mjar596-integration-test-1.0-SNAPSHOT.jar" );
assert artifact.isFile() : "artifact JAR is missing: " + artifact;

// 2. The manifest inside the JAR must carry the sanitized name.
String expected = "org.apache.maven.plugins.mjar596.integration.test";
JarFile jar = new JarFile( artifact );
String moduleName = jar.getManifest().getMainAttributes().getValue( "Automatic-Module-Name" );
jar.close();
assert expected == moduleName : "Expected Automatic-Module-Name \"" + expected + "\" but got: " + moduleName;

// 3. A warning about the sanitization must appear in the build log.
String log = new File( basedir, "build.log" ).getText( "UTF-8" );
assert log.contains( "BUILD SUCCESS" )                                        : "BUILD SUCCESS not found in build.log";
assert log.contains( "[WARNING]" )                                            : "[WARNING] not found in build.log";
assert log.contains( "org.apache.maven.plugins.mjar596-integration-test" )   : "original name not found in build.log";
assert log.contains( expected )                                               : "sanitized name not found in build.log";
