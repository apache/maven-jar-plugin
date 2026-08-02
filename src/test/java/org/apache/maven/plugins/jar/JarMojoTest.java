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

import java.util.HashMap;

import org.apache.maven.api.build.context.BuildContext;
import org.apache.maven.api.di.Priority;
import org.apache.maven.api.di.Provides;
import org.apache.maven.api.services.PathMatcherFactory;
import org.apache.maven.impl.DefaultPathMatcherFactory;
import org.apache.maven.internal.build.context.impl.DefaultBuildContext;
import org.apache.maven.internal.build.context.impl.FilesystemWorkspace;
import org.apache.maven.testing.plugin.Basedir;
import org.apache.maven.testing.plugin.InjectMojo;
import org.apache.maven.testing.plugin.MojoTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test for {@link JarMojo}
 *
 * @version $Id$
 */
@MojoTest
class JarMojoTest {

    /**
     * Tests the discovery and configuration of the mojo.
     *
     * @throws Exception in case of an error
     */
    @Test
    @Basedir("${basedir}/src/test/resources/unit/jar-basic-test")
    @InjectMojo(goal = "jar")
    void jarTestEnvironment(JarMojo mojo) throws Exception {
        assertNotNull(mojo);

        assertEquals("foo", mojo.getProject().getGroupId());
    }

    @Provides
    @Priority(10)
    @SuppressWarnings("unused")
    private static PathMatcherFactory pathMatcherFactory() {
        return new DefaultPathMatcherFactory();
    }

    @Provides
    @Priority(10)
    @SuppressWarnings("unused")
    private static BuildContext buildContext() {
        return new DefaultBuildContext(
                new FilesystemWorkspace(), null, new HashMap<>(), null, new DefaultPathMatcherFactory());
    }
}
