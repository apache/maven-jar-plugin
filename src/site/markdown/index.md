---
title: Introduction
author: 
  - Dennis Lundberg
date: 2013-07-22
---

<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Apache Maven JAR Plugin

This plugin builds JAR files. To sign JAR files, use the [Maven Jarsigner Plugin](/plugins/maven-jarsigner-plugin/).

## Goals Overview

- [jar:jar](./jar-mojo.html): create a JAR file for your project classes and their resources.
- [jar:test-jar](./test-jar-mojo.html): create a JAR file for your project test classes.

## Usage

General instructions for the JAR Plugin are on the [usage page](./usage.html). The examples below describe more specific use cases.

If you have questions about the plugin, see the [FAQ](./faq.html) or contact the [user mailing list](./mailing-lists.html). The mailing list stores all posts in an archive. An older thread can contain the answer to your question. You can search the [mail archive](./mailing-lists.html) for the answer.

If the plugin is missing a feature or has a defect, file a feature request or bug report in the [issue tracker](https://github.com/apache/maven-jar-plugin/issues). When you create a new issue, describe your concern in detail. The developers must reproduce the problem to fix a bug. Attach entire debug logs, POMs, or small demo projects to the issue. Patches are welcome. Contributors can check out the project from the [source repository](./scm.html) and find supplementary information in the [guide to helping with Maven](http://maven.apache.org/guides/development/guide-helping.html).

## Archiver Configuration

The plugin uses Maven Archiver to handle JAR content and manifest configuration. See the [Maven Archiver Documentation](/shared/maven-archiver/) to learn how to set it up. You can also see the [Guide to Working with Manifests](/guides/mini/guide-manifest.html).

## Examples

The following examples show some usages of the JAR Plugin:

- [Manifest Customization](./examples/manifest-customization.html)
- [Include/Exclude Content from a JAR Archive](./examples/include-exclude.html)
- [Create an Additional Attached JAR Artifact](./examples/attached-jar.html)
- [Create a JAR With Test Classes](./examples/create-test-jar.html)
