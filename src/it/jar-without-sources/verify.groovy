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

import java.util.jar.JarFile

File artifact = new File(basedir, "target/jar-without-sources-1.0-SNAPSHOT.jar")
if (!artifact.isFile()) {
    System.err.println("Expected JAR is missing: " + artifact)
    return false
}

JarFile jar = new JarFile(artifact)
try {
    // The manifest must be present.
    if (jar.getEntry("META-INF/MANIFEST.MF") == null) {
        System.err.println("JAR does not contain META-INF/MANIFEST.MF")
        return false
    }
    // As there is no source directory, the JAR must not contain any compiled class.
    for (entry in jar.entries()) {
        if (entry.getName().endsWith(".class")) {
            System.err.println("JAR unexpectedly contains a class entry: " + entry.getName())
            return false
        }
    }
} finally {
    jar.close()
}

return true
