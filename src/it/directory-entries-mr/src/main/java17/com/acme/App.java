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
package com.acme;

/**
 * JDK-17 specific override of {@link App}. Placed under {@code src/main/java17} and compiled with
 * {@code multiReleaseOutput=true}, so it lands in {@code META-INF/versions/17/com/acme/App.class}.
 */
public class App {
    public String greet() {
        return "Hello (java17) from " + new com.acme.sub.Helper().name();
    }
}
