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

// MJAR-267: verify that CLI system properties override <archive><manifest> XML configuration in both directions.
//
// This IT uses a POM that sets:
//   <addDefaultImplementationEntries>false</addDefaultImplementationEntries>  (XML says NO)
//   <addDefaultSpecificationEntries>true</addDefaultSpecificationEntries>     (XML says YES)
//
// And invokes with:
//   -Dmaven.jar.manifest.addDefaultImplementationEntries=true   (CLI overrides to YES)
//   -Dmaven.jar.manifest.addDefaultSpecificationEntries=false   (CLI overrides to NO)
//
// Expected result:
//   - Implementation entries ARE present (CLI=true wins over XML=false)
//   - Specification entries are ABSENT  (CLI=false wins over XML=true)

File target = new File( basedir, "target" )
assert target.exists() && target.isDirectory()

File artifact = new File( target, "mjar-267-override-1.0-SNAPSHOT.jar" )
assert artifact.exists() && artifact.isFile()

JarFile jar = new JarFile( artifact )
Attributes manifest = jar.getManifest().getMainAttributes()

// CLI property=true must override XML=false: implementation entries MUST be present
assert "MJAR-267 Override IT".equals( manifest.get( Attributes.Name.IMPLEMENTATION_TITLE ) ) :
    "CLI property=true should override XML=false: expected Implementation-Title 'MJAR-267 Override IT', got: " + manifest.get( Attributes.Name.IMPLEMENTATION_TITLE )
assert "1.0-SNAPSHOT".equals( manifest.get( Attributes.Name.IMPLEMENTATION_VERSION ) ) :
    "CLI property=true should override XML=false: expected Implementation-Version '1.0-SNAPSHOT', got: " + manifest.get( Attributes.Name.IMPLEMENTATION_VERSION )
assert "Test Org".equals( manifest.get( Attributes.Name.IMPLEMENTATION_VENDOR ) ) :
    "CLI property=true should override XML=false: expected Implementation-Vendor 'Test Org', got: " + manifest.get( Attributes.Name.IMPLEMENTATION_VENDOR )

// CLI property=false must override XML=true: specification entries MUST be absent
assert manifest.get( Attributes.Name.SPECIFICATION_TITLE ) == null :
    "CLI property=false should override XML=true: Specification-Title should be absent, got: " + manifest.get( Attributes.Name.SPECIFICATION_TITLE )
assert manifest.get( Attributes.Name.SPECIFICATION_VERSION ) == null :
    "CLI property=false should override XML=true: Specification-Version should be absent, got: " + manifest.get( Attributes.Name.SPECIFICATION_VERSION )
assert manifest.get( Attributes.Name.SPECIFICATION_VENDOR ) == null :
    "CLI property=false should override XML=true: Specification-Vendor should be absent, got: " + manifest.get( Attributes.Name.SPECIFICATION_VENDOR )

jar.close()
