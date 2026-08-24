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
 * The project sets addDefaultEntries=false, so Maven archiver writes no Created-By. Without the fix the
 * jar tool fills that gap with its own value, java.version + " (" + java.vendor + ")" (for example
 * "21.0.10 (Amazon.com Inc.)"). That is JDK- and vendor-specific, so the same sources produce different
 * bytes on Temurin vs Corretto -- a reproducibility regression, and it ignores addDefaultEntries=false.
 * The plugin must instead write a stable, JDK-independent Created-By.
 */
File artifact = new File(basedir, "target/manifest-created-by-1.0-SNAPSHOT.jar")
assert artifact.isFile() : "artifact is missing: " + artifact

JarFile jar = new JarFile(artifact)
try {
    Attributes attributes = jar.getManifest().getMainAttributes()
    String createdBy = attributes.getValue("Created-By")

    assert createdBy != null : "Created-By is missing."
    assert createdBy.startsWith("Maven JAR Plugin") : "Unexpected Created-By: " + createdBy

    // addMavenDescriptor=false must still be honored: no Maven metadata in the JAR.
    assert jar.getEntry("META-INF/maven/") == null : "addMavenDescriptor=false was not honored"
} finally {
    jar.close()
}
