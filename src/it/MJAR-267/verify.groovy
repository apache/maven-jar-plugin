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

import java.util.jar.*

// MJAR-267: verify that addDefaultImplementationEntries and addDefaultSpecificationEntries
// can be enabled via the maven.jar.manifest.addDefaultImplementationEntries and
// maven.jar.manifest.addDefaultSpecificationEntries system properties (no XML config required).

File target = new File( basedir, "target" )
assert target.exists() && target.isDirectory()

File artifact = new File( target, "mjar-267-1.0-SNAPSHOT.jar" )
assert artifact.exists() && artifact.isFile()

JarFile jar = new JarFile( artifact )
Attributes manifest = jar.getManifest().getMainAttributes()

// Implementation entries (addDefaultImplementationEntries=true)
assert "MJAR-267 IT".equals( manifest.get( Attributes.Name.IMPLEMENTATION_TITLE ) ) :
    "Expected Implementation-Title 'MJAR-267 IT', got: " + manifest.get( Attributes.Name.IMPLEMENTATION_TITLE )
assert "1.0-SNAPSHOT".equals( manifest.get( Attributes.Name.IMPLEMENTATION_VERSION ) ) :
    "Expected Implementation-Version '1.0-SNAPSHOT', got: " + manifest.get( Attributes.Name.IMPLEMENTATION_VERSION )
assert "Test Org".equals( manifest.get( Attributes.Name.IMPLEMENTATION_VENDOR ) ) :
    "Expected Implementation-Vendor 'Test Org', got: " + manifest.get( Attributes.Name.IMPLEMENTATION_VENDOR )

// Specification entries (addDefaultSpecificationEntries=true)
assert "MJAR-267 IT".equals( manifest.get( Attributes.Name.SPECIFICATION_TITLE ) ) :
    "Expected Specification-Title 'MJAR-267 IT', got: " + manifest.get( Attributes.Name.SPECIFICATION_TITLE )
// Specification-Version strips the SNAPSHOT qualifier
assert "1.0".equals( manifest.get( Attributes.Name.SPECIFICATION_VERSION ) ) :
    "Expected Specification-Version '1.0', got: " + manifest.get( Attributes.Name.SPECIFICATION_VERSION )
assert "Test Org".equals( manifest.get( Attributes.Name.SPECIFICATION_VENDOR ) ) :
    "Expected Specification-Vendor 'Test Org', got: " + manifest.get( Attributes.Name.SPECIFICATION_VENDOR )

jar.close()
