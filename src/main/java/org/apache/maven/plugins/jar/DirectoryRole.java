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
package org.apache.maven.plugins.jar;

/**
 * Directories that the archiver needs to handle in a special way.
 */
enum DirectoryRole {
    /**
     * The root directory. This is usually {@code "target/classes"}.
     * The next locations can be {@link #META_INF}, {@link #NAMED_MODULE} or {@link #RESOURCES}.
     */
    ROOT,

    /**
     * The {@code "META-INF"} or {@code "<module>/META-INF"} directory.
     * This is part of the <abbr>JAR</abbr> specification.
     * The next locations can be {@link #VERSIONS} or {@link #VERSIONS_MODULAR}.
     */
    META_INF,

    /**
     * The {@code "META-INF/versions"} or {@code "<module>/META-INF/versions"} directory.
     * This is part of the <abbr>JAR</abbr> specification, except the {@code <module>} prefix.
     * The sub-directories are named according Java releases such as "21".
     * The next location can only be {@link #RESOURCES}.
     */
    VERSIONS,

    /**
     * The Maven-specific {@code "META-INF/versions-modular"} directory.
     * Note that {@code "<module>/META-INF/versions-modular"} is not forbidden, but does not make sense.
     * The sub-directories are named according Java releases such as "21".
     * The next location can only be {@link #MODULES}.
     */
    VERSIONS_MODULAR,

    /**
     * The Maven-specific {@code "META-INF/versions-modular"} directory.
     * All sub-directories shall have the name of a Java module.
     * The next location can only be {@link #NAMED_MODULE}.
     */
    MODULES,

    /**
     * The root of a single Java module in a module hierarchy.
     * The name of this directory is the Java module name.
     * The next location can only be {@link #RESOURCES}.
     */
    NAMED_MODULE,

    /**
     * The classes or other types of resources to include in a single archive.
     * May also be other files in the {@code META-INF} directory.
     */
    RESOURCES
}
