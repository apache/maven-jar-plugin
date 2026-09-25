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

// MJAR-138: -DskipTests must NOT prevent the test-jar from being built.
// The model module's test-jar must be produced so the client module can
// resolve it as a reactor artifact and compile its test sources against it.

import java.io.*;

boolean result = true;

try {
    // 1. The model test-jar must have been produced despite -DskipTests.
    File testJar = new File(basedir, "model/target/model-1.0-SNAPSHOT-tests.jar");
    if (!testJar.exists() || testJar.isDirectory()) {
        System.err.println("MJAR-138: model test-jar is missing: " + testJar);
        return false;
    }

    // 2. The client's test classes must have compiled (they depend on TestComponent from the test-jar).
    File clientTestClasses = new File(basedir, "client/target/test-classes/org/apache/maven/its/mjar138/ClientTest.class");
    if (!clientTestClasses.exists()) {
        System.err.println("MJAR-138: client test classes not compiled (test-jar was not available in reactor): " + clientTestClasses);
        return false;
    }
} catch (Throwable e) {
    e.printStackTrace();
    result = false;
}

return result;
