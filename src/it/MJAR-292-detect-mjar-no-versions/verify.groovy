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

import java.util.jar.*;

def mrjar = new JarFile(new File(basedir,'target/mjar-292-detect-multi-release-jar-1.0-SNAPSHOT.jar'))
def manifest = mrjar.manifest.mainAttributes

assert manifest.getValue( Attributes.Name.MAIN_CLASS ) == "myproject.HelloWorld"
assert manifest.getValue( Attributes.Name.MULTI_RELEASE ) == null
