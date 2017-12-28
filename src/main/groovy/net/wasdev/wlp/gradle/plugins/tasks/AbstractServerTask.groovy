/**
 * (C) Copyright IBM Corporation 2017.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.wasdev.wlp.gradle.plugins.tasks

import groovy.xml.StreamingMarkupBuilder
import net.wasdev.wlp.ant.ServerTask
import net.wasdev.wlp.gradle.plugins.extensions.LibertyExtension
import net.wasdev.wlp.gradle.plugins.extensions.ServerExtension
import net.wasdev.wlp.gradle.plugins.utils.ApplicationXmlDocument
import net.wasdev.wlp.gradle.plugins.utils.LibertyIntstallController
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.WarPlugin
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.bundling.War
import org.gradle.plugins.ear.EarPlugin

abstract class AbstractServerTask extends AbstractTask {

    public final String HEADER = "# Generated by liberty-gradle-plugin\n"

    protected LibertyExtension libertyExt
    protected ServerExtension server

    protected String serverDirectory

    AbstractServerTask(){
        libertyExt = project.extensions.findByType(LibertyExtension)
        server = libertyExt.getServer()

        assert server != null

        serverDirectory = getServerDir(project).toString()
    }

    protected void executeServerCommand(Project project, String command, Map<String, String> params) {
        project.ant.taskdef(name: 'server',
                            classname: ServerTask.name,
                            classpath: project.rootProject.buildscript.configurations.classpath.asPath)
        params.put('operation', command)
        project.ant.server(params)
    }

    protected Map<String, String> buildLibertyMap(Project project) {
        Map<String, String> result = new HashMap();
        result.put('serverName', server.name)

        def installDir = LibertyIntstallController.getInstallDir(project)
        result.put('installDir', installDir)

        def userDir = getUserDir(project, installDir)
        result.put('userDir', userDir)

        if (getServerOutputDir(project) != null) {
            result.put('outputDir', getServerOutputDir(project))
        }
        if (server.timeout != null && !server.timeout.isEmpty()) {
            result.put('timeout', server.timeout)
        }

        return result;
    }

    protected File getServerDir(Project project){
        return new File(getUserDir(project).toString() + "/servers/" + server.name)
    }

    protected String getServerOutputDir(Project project) {
        if (server.outputDir != null) {
            return server.outputDir
        } else {
            return project.liberty.outputDir
        }
    }

    void makeParentDirectory(File file) {
        File parentDir = file.getParentFile()
        if (parentDir != null) {
            parentDir.mkdirs()
        }
    }
    protected void setServerDirectoryNodes(Project project, Node serverNode) {
        serverNode.appendNode('userDirectory', getUserDir(project).toString())
        serverNode.appendNode('serverDirectory', getServerDir(project).toString())
        String serverOutputDir = getServerOutputDir(project)
        if (serverOutputDir != null && !serverOutputDir.isEmpty()) {
            serverNode.appendNode('serverOutputDirectory', serverOutputDir)
        } else {
            serverNode.appendNode('serverOutputDirectory', getServerDir(project).toString())
        }
    }

    protected void setServerPropertyNodes(Project project, Node serverNode) {
        serverNode.appendNode('serverName', server.name)
        if (server.configDirectory != null && server.configDirectory.exists()) {
            serverNode.appendNode('configDirectory', server.configDirectory.toString())
        }

        if (server.configFile != null && server.configFile.exists()) {
            serverNode.appendNode('configFile', server.configFile.toString())
        }

        if (server.bootstrapProperties != null && !server.bootstrapProperties.isEmpty()) {
            Node bootstrapProperties = new Node(null, 'bootstrapProperties')
            server.bootstrapProperties.each { k, v ->
                bootstrapProperties.appendNode(k, v.toString())
            }
            serverNode.append(bootstrapProperties)
        } else if (server.bootstrapPropertiesFile != null && server.bootstrapPropertiesFile.exists()) {
            serverNode.appendNode('bootstrapPropertiesFile', server.bootstrapPropertiesFile.toString())
        }

        if (server.jvmOptions != null && !server.jvmOptions.isEmpty()) {
            Node jvmOptions = new Node(null, 'jvmOptions')
            server.jvmOptions.each { v ->
                jvmOptions.appendNode('params', v.toString())
            }
            serverNode.append(jvmOptions)
        } else if (server.jvmOptionsFile != null && server.jvmOptionsFile.exists()) {
            serverNode.appendNode('jvmOptionsFile', server.jvmOptionsFile.toString())
        }

        if (server.serverEnv != null && server.serverEnv.exists()) {
            serverNode.appendNode('serverEnv', server.serverEnv.toString())
        }

        serverNode.appendNode('looseApplication', server.looseApplication)
        serverNode.appendNode('stripVersion', server.stripVersion)

        File installAppsConfigDropinsFile = ApplicationXmlDocument.getApplicationXmlFile(getServerDir(project))
        if (installAppsConfigDropinsFile.exists()) {
            serverNode.appendNode('installAppsConfigDropins', installAppsConfigDropinsFile.toString())
        }
    }

    protected void createApplicationElements(Node applicationsNode, List<Objects> appList, String appDir) {
        appList.each { Object appObj ->
            Node application = new Node(null, 'application')
            if (appObj instanceof Task) {
                application.appendNode('appsDirectory', appDir)
                if (server.looseApplication) {
                    application.appendNode('applicationFilename', appObj.archiveName + '.xml')
                } else {
                    application.appendNode('applicationFilename', appObj.archiveName)
                }
                if (appObj instanceof War) {
                    application.appendNode('warSourceDirectory', project.webAppDirName)
                }
            } else if (appObj instanceof File) {
                application.appendNode('appsDirectory', appDir)
                if (server.looseApplication) {
                    application.appendNode('applicationFilename', appObj.name + '.xml')
                } else {
                    application.appendNode('applicationFilename', appObj.name)
                }
            }

            if(!application.children().isEmpty()) {
                if (project.plugins.hasPlugin("war")) {
                    application.appendNode('projectType', 'war')
                } else if (project.plugins.hasPlugin("ear")) {
                    application.appendNode('projectType', 'ear')
                }
                applicationsNode.append(application)
            }
        }
    }

    protected void setApplicationPropertyNodes(Project project, Node serverNode) {
        Node applicationsNode;
        if ((server.apps == null || server.apps.isEmpty()) && (server.dropins == null || server.dropins.isEmpty())) {
            if (project.plugins.hasPlugin('war')) {
                applicationsNode = new Node(null, 'applications')
                createApplicationElements(applicationsNode, [project.tasks.war], 'apps')
                serverNode.append(applicationsNode)
            }
        } else {
            applicationsNode = new Node(null, 'applications')
            if (server.apps != null && !server.apps.isEmpty()) {
                createApplicationElements(applicationsNode, server.apps, 'apps')
            }
            if (server.dropins != null && !server.dropins.isEmpty()) {
                createApplicationElements(applicationsNode, server.dropins, 'dropins')
            }
            serverNode.append(applicationsNode)
        }
    }

    protected void setDependencyNodes(Project project, Node serverNode) {
        Project parent = project.getParent()
        if (parent != null) {
            serverNode.appendNode('aggregatorParentId', parent.getName())
            serverNode.appendNode('aggregatorParentBaseDir', parent.getProjectDir())
        }

        if (project.configurations.findByName('compile') && !project.configurations.compile.dependencies.isEmpty()) {
            project.configurations.compile.dependencies.each { dependency ->
                serverNode.appendNode('projectCompileDependency', dependency.group + ':' + dependency.name + ':' + dependency.version)
            }
        }
    }

    protected void writeServerPropertiesToXml(Project project) {
        XmlParser pluginXmlParser = new XmlParser()
        Node libertyPluginConfig = pluginXmlParser.parse(new File(project.buildDir, 'liberty-plugin-config.xml'))
        if (libertyPluginConfig.getAt('servers').isEmpty()) {
            libertyPluginConfig.appendNode('servers')
        } else {
            //removes the server nodes from the servers element
            libertyPluginConfig.getAt('servers')[0].value = ""
        }
        Node serverNode = new Node(null, 'server')

        setServerDirectoryNodes(project, serverNode)
        setServerPropertyNodes(project, serverNode)
        setApplicationPropertyNodes(project, serverNode)
        setDependencyNodes(project, serverNode)

        libertyPluginConfig.getAt('servers')[0].append(serverNode)

        new File( project.buildDir, 'liberty-plugin-config.xml' ).withWriter('UTF-8') { output ->
            output << new StreamingMarkupBuilder().bind { mkp.xmlDeclaration(encoding: 'UTF-8', version: '1.0' ) }
            XmlNodePrinter printer = new XmlNodePrinter( new PrintWriter(output) )
            printer.preserveWhitespace = true
            printer.print( libertyPluginConfig )
        }
    }

    String getPackagingType() throws Exception{
      if (project.plugins.hasPlugin("war") || !project.tasks.withType(WarPlugin).isEmpty()) {
          return "war"
      }
      else if (project.plugins.hasPlugin("ear") || !project.tasks.withType(EarPlugin).isEmpty()) {
          return "ear"
      }
      else {
          throw new GradleException("Archive path not found. Supported formats are jar, war, and ear.")
      }
  }
}
