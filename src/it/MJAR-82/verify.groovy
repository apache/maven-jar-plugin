
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

import org.codehaus.plexus.util.cli.*
import org.codehaus.plexus.util.FileUtils

File target = new File(basedir, "target")
assert target.exists() && target.isDirectory() : "target file is missing or not a directory."

File artifact = new File( target, "MJAR-82-1.0-SNAPSHOT.jar" )
assert artifact.exists() && !artifact.isDirectory() : "artifact file is missing or a directory."

String localRepoPath = basedir.getParentFile().getParentFile().getParentFile().getPath() + "/target/local-repo"
FileUtils.copyFile( artifact, new File( localRepoPath, "MJAR-82-1.0-SNAPSHOT.jar" ) )
Commandline commandline = new Commandline()
commandline.setExecutable( "java" )
commandline.setWorkingDirectory( localRepoPath )
System.out.println(" localRepoPath " + localRepoPath )
commandline.createArg().setLine( "-jar MJAR-82-1.0-SNAPSHOT.jar" )
CommandLineUtils.StringStreamConsumer consumer = new CommandLineUtils.StringStreamConsumer()
int res = CommandLineUtils.executeCommandLine( commandline, consumer, consumer )
String output = consumer.getOutput()
System.out.println( "res " + res )
System.out.println( output )
assert res == 0 : "commandline execution return code != 0 " + res

assert output.indexOf("great it works") >= 0 : "output not contains great it works"
