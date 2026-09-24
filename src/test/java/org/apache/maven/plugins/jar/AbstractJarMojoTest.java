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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.apache.maven.api.PathScope;
import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AbstractJarMojo#getOutputTimestamp()}.
 */
class AbstractJarMojoTest {

    private static class TestMojo extends AbstractJarMojo {
        final List<String> warnings = new ArrayList<>();

        TestMojo(String outputTimestamp) {
            this.outputTimestamp = outputTimestamp;
            this.log = new Log() {
                @Override
                public boolean isDebugEnabled() {
                    return false;
                }

                @Override
                public void debug(CharSequence message) {}

                @Override
                public void debug(CharSequence message, Throwable throwable) {}

                @Override
                public void debug(Throwable throwable) {}

                @Override
                public void debug(Supplier<String> message) {}

                @Override
                public void debug(Supplier<String> message, Throwable throwable) {}

                @Override
                public boolean isInfoEnabled() {
                    return false;
                }

                @Override
                public void info(CharSequence message) {}

                @Override
                public void info(CharSequence message, Throwable throwable) {}

                @Override
                public void info(Throwable throwable) {}

                @Override
                public void info(Supplier<String> message) {}

                @Override
                public void info(Supplier<String> message, Throwable throwable) {}

                @Override
                public boolean isWarnEnabled() {
                    return true;
                }

                @Override
                public void warn(CharSequence message) {
                    warnings.add(message.toString());
                }

                @Override
                public void warn(CharSequence message, Throwable throwable) {
                    warnings.add(message.toString());
                }

                @Override
                public void warn(Throwable throwable) {}

                @Override
                public void warn(Supplier<String> message) {
                    warnings.add(message.get());
                }

                @Override
                public void warn(Supplier<String> message, Throwable throwable) {
                    warnings.add(message.get());
                }

                @Override
                public boolean isErrorEnabled() {
                    return false;
                }

                @Override
                public void error(CharSequence message) {}

                @Override
                public void error(CharSequence message, Throwable throwable) {}

                @Override
                public void error(Throwable throwable) {}

                @Override
                public void error(Supplier<String> message) {}

                @Override
                public void error(Supplier<String> message, Throwable throwable) {}
            };
        }

        @Override
        protected Path getClassesDirectory() {
            return null;
        }

        @Override
        protected String getClassifier() {
            return null;
        }

        @Override
        protected String getType() {
            return "jar";
        }

        @Override
        protected PathScope getDependencyScope() {
            return PathScope.MAIN_COMPILE;
        }
    }

    /** No timestamp configured → null. */
    @Test
    void returnsNullWhenNotConfigured() {
        assertNull(new TestMojo(null).getOutputTimestamp());
    }

    /** A valid ISO 8601 string is returned as-is (jar tool validates it). */
    @Test
    void validIso8601PassesThroughUnchanged() {
        var mojo = new TestMojo("2023-06-15T12:00:00Z");
        assertEquals("2023-06-15T12:00:00Z", mojo.getOutputTimestamp());
        assertTrue(mojo.warnings.isEmpty());
    }

    /** An ISO 8601 string before 1980 is also returned as-is — jar tool is the validator. */
    @Test
    void iso8601BeforeMinPassesThroughUnchanged() {
        var mojo = new TestMojo("1970-01-01T00:00:00Z");
        assertEquals("1970-01-01T00:00:00Z", mojo.getOutputTimestamp());
        assertTrue(mojo.warnings.isEmpty());
    }

    /** Verifies that EPOCH_MIN equals Instant.parse("1980-01-01T00:00:02Z").getEpochSecond(). */
    @Test
    void epochMinMatchesParsedInstant() {
        assertEquals(Instant.parse("1980-01-01T00:00:02Z").getEpochSecond(), AbstractJarMojo.EPOCH_MIN);
    }

    /**
     * SOURCE_DATE_EPOCH=0 (Unix epoch) as seconds → clamped to EPOCH_MIN with a warning.
     * Primary regression scenario from issue #595.
     */
    @Test
    void epochZeroSecondsClamped() {
        var mojo = new TestMojo("0");
        assertEquals(Instant.ofEpochSecond(AbstractJarMojo.EPOCH_MIN).toString(), mojo.getOutputTimestamp());
        assertEquals(1, mojo.warnings.size());
        assertTrue(mojo.warnings.get(0).contains("1980-01-01T00:00:02Z"));
    }

    /** Negative seconds (before Unix epoch) → clamped to EPOCH_MIN with a warning. */
    @Test
    void negativeEpochSecondsClamped() {
        var mojo = new TestMojo("-1");
        assertEquals(Instant.ofEpochSecond(AbstractJarMojo.EPOCH_MIN).toString(), mojo.getOutputTimestamp());
        assertEquals(1, mojo.warnings.size());
    }

    /** Any seconds value before EPOCH_MIN → clamped. */
    @Test
    void epochSecondsBeforeMinClamped() {
        // 315532801 = 1980-01-01T00:00:01Z, one second before EPOCH_MIN
        var mojo = new TestMojo("315532801");
        assertEquals(Instant.ofEpochSecond(AbstractJarMojo.EPOCH_MIN).toString(), mojo.getOutputTimestamp());
        assertEquals(1, mojo.warnings.size());
    }

    /** Exactly EPOCH_MIN (315532802) → not clamped, no warning. */
    @Test
    void epochSecondsAtEpochMinNotClamped() {
        var mojo = new TestMojo("315532802");
        assertEquals(Instant.ofEpochSecond(AbstractJarMojo.EPOCH_MIN).toString(), mojo.getOutputTimestamp());
        assertTrue(mojo.warnings.isEmpty());
    }

    /** A valid modern timestamp as seconds → converted to ISO 8601, no clamping. */
    @Test
    void validEpochSecondsConvertedToIso8601() {
        // 2000-01-01T00:00:00Z = 946684800 seconds since epoch
        var mojo = new TestMojo("946684800");
        assertEquals("2000-01-01T00:00:00Z", mojo.getOutputTimestamp());
        assertTrue(mojo.warnings.isEmpty());
    }
}
