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
import java.nio.file.*;
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

boolean result = true;

try
{
    // 1. Build must succeed.
    File target = new File( basedir, "target" );
    if ( !target.exists() || !target.isDirectory() )
    {
        System.err.println( "target directory is missing." );
        return false;
    }

    File artifact = new File( target, "mjar596-integration-test-1.0-SNAPSHOT.jar" );
    if ( !artifact.exists() || artifact.isDirectory() )
    {
        System.err.println( "artifact JAR is missing or is a directory." );
        return false;
    }

    // 2. The manifest inside the JAR must carry the sanitized name.
    JarFile jar = new JarFile( artifact );
    Manifest mf = jar.getManifest();
    jar.close();

    String moduleName = mf.getMainAttributes().getValue( "Automatic-Module-Name" );
    String expected   = "org.apache.maven.plugins.mjar596.integration.test";
    if ( !expected.equals( moduleName ) )
    {
        System.err.println( "Expected Automatic-Module-Name \"" + expected
                + "\" but got: " + moduleName );
        result = false;
    }

    // 3. A warning about the sanitization must appear in the build log.
    String log = new String( Files.readAllBytes( basedir.toPath().resolve( "build.log" ) ), "UTF-8" );
    String[] snippets = [
        "BUILD SUCCESS",
        "[WARNING]",
        "org.apache.maven.plugins.mjar596-integration-test",
        expected
    ];
    for ( String snippet : snippets )
    {
        if ( !log.contains( snippet ) )
        {
            System.err.println( "Snippet not found in build log: `" + snippet + "`" );
            result = false;
        }
    }
}
catch ( Throwable e )
{
    e.printStackTrace();
    result = false;
}

return result;
