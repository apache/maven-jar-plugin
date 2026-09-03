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

File artifact = new File(basedir, "target/directory-entries-1.0-SNAPSHOT.jar")
assert artifact.isFile() : "JAR artifact is missing: " + artifact

JarFile jar = new JarFile(artifact)
try {
    // Regression guard for maven-jar-plugin #508 (Sergey Chernov, dev list):
    // if the JAR is built by enumerating individual files, the intermediate directory entries
    // can not be specified (if they were, they would be traversed), which breaks consumers
    // relying on JAR directory traversal (e.g. Spring Boot @ComponentScan).
    // Assert that the directory entries are present.
    def requiredDirectoryEntries = [
        "com/",
        "com/acme/",
        "com/acme/sub/"
    ]
    for (String name : requiredDirectoryEntries) {
        def entry = jar.getEntry(name)
        assert entry != null : "Missing directory entry: " + name
        assert entry.isDirectory() : "Entry is not a directory: " + name
    }

    // The class files must of course still be present.
    def requiredFileEntries = [
        "com/acme/App.class",
        "com/acme/sub/Helper.class"
    ]
    for (String name : requiredFileEntries) {
        def entry = jar.getEntry(name)
        assert entry != null : "Missing class entry: " + name
        assert !entry.isDirectory() : "Class entry unexpectedly a directory: " + name
    }
} finally {
    jar.close()
}

return true
