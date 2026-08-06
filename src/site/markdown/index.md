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
This plugin provides the capability to build jars. To sign jars, use the [Maven Jarsigner Plugin](/plugins/maven-jarsigner-plugin/).

## Goals Overview

- [jar:jar](./jar-mojo.html) create a jar file for your project classes inclusive resources.
- [jar:test-jar](./test-jar-mojo.html) create a jar file for your project test classes .
## Usage

General instructions on how to use the JAR Plugin can be found on the [usage page](./usage.html). Some more specific use cases are described in the examples below.

In case you still have questions regarding the plugin's usage, please have a look at the [FAQ](./faq.html) and feel free to contact the [user mailing list](./mailing-lists.html). The posts to the mailing list are archived and could already contain the answer to your question as part of an older thread. Hence, it is also worth browsing/searching the [mail archive](./mailing-lists.html).

If you feel like the plugin is missing a feature or has a defect, you can file a feature request or bug report in our [issue tracker](https://github.com/apache/maven-jar-plugin/issues). When creating a new issue, please provide a comprehensive description of your concern. Especially for fixing bugs it is crucial that the developers can reproduce your problem. For this reason, entire debug logs, POMs, or most preferably little demo projects attached to the issue are very much appreciated. Of course, patches are welcome, too. Contributors can check out the project from our [source repository](./scm.html) and will find supplementary information in the [guide to helping with Maven](http://maven.apache.org/guides/development/guide-helping.html).

## Archiver Configuration

The plugin uses Maven Archiver to handle jar content and manifest configuration.

You can have a look at the [Maven Archiver Documentation](/shared/maven-archiver/) to understand how to setup this.

You might also want to consult the [Guide to Working with Manifests](/guides/mini/guide-manifest.html).

## Examples

To provide you with better understanding of some usages of the JAR Plugin, you can take a look at the following examples:

- [Manifest Customization](./examples/manifest-customization.html)
- [Howto include/exclude Content from a jar archive](./examples/include-exclude.html)
- [How to create an additional attached jar artifact from the project](./examples/attached-jar.html)
- [How to create a jar containing test classes](./examples/create-test-jar.html)
