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
import java.util.*;
import java.util.jar.*;

File target = new File(basedir, "target");

Set<String> content = new HashSet<>();
content.add("module-info.class")
content.add("foo/MainFile.class")
content.add("META-INF/MANIFEST.MF")
content.add("META-INF/maven/org.apache.maven.plugins/multi-module/pom.xml")
content.add("META-INF/maven/org.apache.maven.plugins/multi-module/pom.properties")
verify(new File(target, "foo.bar-1.0-SNAPSHOT.jar"), content, "foo.MainFile")

content.clear()
content.add("module-info.class")
content.add("more/MainFile.class")
content.add("META-INF/MANIFEST.MF")
content.add("META-INF/maven/org.apache.maven.plugins/multi-module/pom.xml")
content.add("META-INF/maven/org.apache.maven.plugins/multi-module/pom.properties")
verify(new File(target, "foo.bar.more-1.0-SNAPSHOT.jar"), content, null)

void verify(File artifact, Set<String> content, String mainClass)
{
    JarFile jar = new JarFile(artifact)
    Enumeration jarEntries = jar.entries()
    while (jarEntries.hasMoreElements())
    {
        JarEntry entry = (JarEntry) jarEntries.nextElement()
        if (!entry.isDirectory())
        {
            String name = entry.getName()
            assert content.remove(name) : "Missing entry: " + name
        }
    }
    assert content.isEmpty() : "Unexpected entries: " + content

    Attributes attributes = jar.getManifest().getMainAttributes()
    assert attributes.get(Attributes.Name.MULTI_RELEASE) == null
    assert Objects.equals(mainClass, attributes.get(Attributes.Name.MAIN_CLASS))

    jar.close();
}
