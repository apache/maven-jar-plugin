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

import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Archive}, focused on the two behaviours that are otherwise only
 * exercised by integration tests whose outcome depends on the (unspecified) filesystem
 * directory-iteration order, and therefore pass on some platforms while failing on others.
 */
class ArchiveTest {

    /**
     * Creates an {@code Archive} suitable for a unit test. {@code forceCreation = true} skips the
     * existing-JAR timestamp check, so the (here {@code null}) logger is never dereferenced.
     */
    private static Archive archive(String moduleName, Runtime.Version version, Path directory) {
        return new Archive(directory.resolve("out.jar"), moduleName, version, directory, true, null);
    }

    /**
     * Creates a manifest with the main class attribute set to the given value.
     *
     * @param value value of the main class attribute
     * @return a new manifest with the given attribute value
     */
    private static Manifest manifestWithMainClass(String value) {
        Manifest m = new Manifest();
        Attributes attributes = m.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, value);
        return m;
    }

    /**
     * Returns the value of the main class attribute.
     *
     * @param m the manifest from which to get the value
     * @return the main class attribute value, or {@code null} if none
     */
    private static Object mainClassOf(Manifest m) {
        return m.getMainAttributes().get(Attributes.Name.MAIN_CLASS);
    }

    /**
     * Verifies that {@link Archive#setMainClass(Manifest)} takes ownership for a module-qualified
     * {@code "module/Class"} main class.
     *
     * Note that the {@code "foo.bar/"} prefix in this test (the module name) is a Maven extension.
     * The standard <abbr>JAR</abbr> specification accepts only the {@code "foo.MainFile"} class name.
     */
    @Test
    void owningModuleClaimsMainClassAndRemovesItFromManifest() {
        Archive owner = archive("foo.bar", null, Path.of("."));
        Manifest m = manifestWithMainClass("foo.bar/foo.MainFile");
        // The owner keeps the main class (emitted via --main-class) ...
        assertTrue(owner.setMainClass(m));
        // ... and the raw `module/Class` value is removed from the written manifest.
        assertNull(mainClassOf(m));
    }

    /**
     * Verifies that {@link Archive#setMainClass(Manifest)} does <em>not</em> take ownership fo
     * a module-qualified {@code "module/Class"} main class when the module name does not match.
     * Note that the {@code "foo.bar/"} prefix in this test (the module name) is a Maven extension.
     * The standard <abbr>JAR</abbr> specification accepts only the {@code "foo.MainFile"} class name.
     */
    @Test
    void nonOwningModuleRejectsMainClass() {
        Archive nonOwner = archive("foo.bar.more", null, Path.of("."));
        Manifest m = manifestWithMainClass("foo.bar/foo.MainFile");
        assertFalse(nonOwner.setMainClass(m));
        assertNull(mainClassOf(m));
    }

    /**
     * Tests that which module keeps the main class must not depend on processing order.
     * {@link ToolExecutor} gives each module a <em>copy</em> of the shared plugin manifest;
     * this pins that the owning module (and only it) keeps the main class in either order,
     * and that the shared manifest is never consumed.
     */
    @Test
    void mainClassAssignmentIsIndependentOfModuleOrder() {
        assertOwnership("foo.bar", "foo.bar.more", true); // owner processed first
        assertOwnership("foo.bar.more", "foo.bar", false); // non-owner processed first
    }

    /**
     * Helper method for {@link #mainClassAssignmentIsIndependentOfModuleOrder()}.
     * Asserts that {@link Archive#setMainClass(Manifest)} returns {@code true}
     * for the owner and {@code false} for the other module.
     *
     * <p>Note that the {@code "foo.bar/"} prefix in this test (the module name) is a Maven extension.
     * The standard <abbr>JAR</abbr> specification accepts only the {@code "foo.MainFile"} class name.
     * This extension is used by the plugin for identifying in which <abbr>JAR</abbr> file to add this
     * {@code Main-Class} manifest entry.</p>
     */
    private static void assertOwnership(String first, String second, boolean ownerIsFirst) {
        final Path path = Path.of(".");
        final Manifest shared = manifestWithMainClass("foo.bar/foo.MainFile");
        final Manifest m1 = new Manifest(shared);
        final Manifest m2 = new Manifest(shared);
        assertEquals(ownerIsFirst, archive(first, null, path).setMainClass(m1));
        assertEquals(!ownerIsFirst, archive(second, null, path).setMainClass(m2));
        // Per-module copies must leave the shared plugin manifest untouched.
        assertEquals("foo.bar/foo.MainFile", mainClassOf(shared));
        assertNull(mainClassOf(m1));
        assertNull(mainClassOf(m2));
    }

    /**
     * Tests that {@code FileSet} association to target release is consistent regardless creation order.
     * Verifies that the base (version-less) release binds to the true {@code <module>} directory even
     * when the {@link Archive} was first created from a {@code META-INF/versions-modular/<n>/<module>}
     * directory (which happens when the file-tree walk visits the version directory first).
     */
    @Test
    void baseReleaseBindingIsIndependentOfDirectoryOrder() {
        final Path base = Path.of("classes", "foo.bar");
        final Path v16 = Path.of("classes", "META-INF", "versions-modular", "16", "foo.bar");
        final Runtime.Version r16 = Runtime.Version.parse("16");

        // Version-directory first (the failing order): Archive seeded from v16, base registered later.
        final Archive a = archive("foo.bar", r16, v16);
        assertNotSame(a.newTargetRelease(v16, r16), a.newTargetRelease(base, null));
        assertEquals(base, a.baseRelease().directory, "base must rebind to the version-less directory");

        // Base-directory first: still correct.
        final Archive b = archive("foo.bar", null, base);
        assertNotSame(b.newTargetRelease(base, null), b.newTargetRelease(v16, r16));
        assertEquals(base, b.baseRelease().directory);
    }
}
