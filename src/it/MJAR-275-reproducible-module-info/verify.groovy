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

import java.io.*;
import java.lang.module.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.jar.*;

File target = new File( basedir, "target" )

assert target.exists()
assert target.isDirectory()

File artifact = new File( target, "mjar-275-reproducible-multi-release-modular-jar-1.0-SNAPSHOT.jar" );

assert artifact.exists()
assert artifact.isFile()

JarFile jar = new JarFile( artifact );

Attributes manifest = jar.getManifest().getMainAttributes();

assert "myproject.HelloWorld".equals( manifest.get( Attributes.Name.MAIN_CLASS ) )

InputStream moduleDescriptorInputStream = jar.getInputStream( jar.getEntry( "META-INF/versions/9/module-info.class" ) );
ModuleDescriptor moduleDescriptor = ModuleDescriptor.read( moduleDescriptorInputStream );

assert "myproject.HelloWorld".equals( moduleDescriptor.mainClass().orElse( null ) )

// Normalize to UTC
long normalizeUTC( String timestamp )
{
  long millis = Instant.parse( timestamp ).toEpochMilli();
  Calendar cal = Calendar.getInstance();
  cal.setTimeInMillis( millis );
  return millis - ( cal.get( Calendar.ZONE_OFFSET ) + cal.get( Calendar.DST_OFFSET ) );
}

// All entries should have the same timestamp
FileTime expectedTimestamp = FileTime.fromMillis( normalizeUTC( "2022-06-26T13:25:58Z" ) );
Enumeration<JarEntry> entries = jar.entries();
while ( entries.hasMoreElements() )
{
    assert expectedTimestamp.equals( entries.nextElement().getLastModifiedTime() )
}

jar.close();
