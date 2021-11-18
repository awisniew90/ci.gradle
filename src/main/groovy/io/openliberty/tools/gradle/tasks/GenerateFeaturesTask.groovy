/**
 * (C) Copyright IBM Corporation 2021.
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
package io.openliberty.tools.gradle.tasks

import java.nio.file.Files
import java.util.Set
import java.net.URL;
import java.net.URLClassLoader;

import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.TransformerException

import org.gradle.api.artifacts.ResolveException
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.xml.sax.SAXException

import io.openliberty.tools.common.plugins.util.InstallFeatureUtil
import io.openliberty.tools.common.plugins.config.ServerConfigXmlDocument
import io.openliberty.tools.common.plugins.util.DevUtil
import io.openliberty.tools.common.plugins.util.PluginExecutionException
import io.openliberty.tools.common.plugins.util.PluginScenarioException
import io.openliberty.tools.gradle.utils.ArtifactDownloadUtil

class GenerateFeaturesTask extends AbstractFeatureTask {

    private static final String GENERATED_FEATURES_FILE_NAME = "generated-features.xml";
    protected static final String GENERATED_FEATURES_FILE_PATH = "configDropins/overrides/" + GENERATED_FEATURES_FILE_NAME;
    protected static final String FEATURES_FILE_MESSAGE = "The Liberty Gradle Plugin has generated Liberty features necessary for your application in " + GENERATED_FEATURES_FILE_PATH;
    protected static final String HEADER = "# Generated by liberty-gradle-plugin";

    private static final String BINARY_SCANNER_MAVEN_GROUP_ID = "com.ibm.websphere.appmod.tools";
    private static final String BINARY_SCANNER_MAVEN_ARTIFACT_ID = "binaryAppScanner";
    private static final String BINARY_SCANNER_MAVEN_TYPE = "jar";
    private static final String BINARY_SCANNER_MAVEN_VERSION = "latest.release";

    private File binaryScanner;

    GenerateFeaturesTask() {
        configure({
            description 'Generate the features used by an application and add to the configuration of a Liberty server'
            group 'Liberty'
        })
    }

    private List<String> classFiles;

    @Option(option = 'classFile', description = 'If set, will generate features for the list of classes passed.')
    void setClassFiles(List<String> classFiles) {
        this.classFiles = classFiles;
    }

    @TaskAction
    void generateFeatures() {
        binaryScanner = getBinaryScannerJarFromRepository();

        if (classFiles != null && !classFiles.isEmpty()) {
            logger.debug("Generate features for the following class files: " + classFiles);
        } else {
            logger.debug("Generate features for all class files");
        }

        initializeConfigDirectory();
        def serverDirectory = getServerDir(project);
        def libertyDirPropertyFiles;
        try {
            libertyDirPropertyFiles = getLibertyDirectoryPropertyFiles(getInstallDir(project), getUserDir(project), serverDirectory);
        } catch (IOException x) {
            logger.debug("Exception reading the server property files", e);
            logger.error("Error attempting to generate server feature list. Ensure your user account has read permission to the property files in the server installation directory.");
            return;
        }
        // get existing installed server features
        InstallFeatureUtil util;
        try {
            util = getInstallFeatureUtil(new HashSet<String>(), null);
        } catch (PluginScenarioException e) {
            logger.debug("Exception creating the server utility object", e);
            logger.error("Error attempting to generate server feature list.");
            return;
        }

        final boolean optimize = (classFiles == null || classFiles.isEmpty()) ? true : false;
        Set<String> generatedFiles = new HashSet<String>();
        generatedFiles.add(GENERATED_FEATURES_FILE_NAME);    

        util.setLowerCaseFeatures(false);
        // if optimizing, ignore generated files when passing in existing features to binary scanner
        Set<String> existingFeatures = util.getServerFeatures(serverDirectory, libertyDirPropertyFiles, optimize ? generatedFiles : null);
        if (existingFeatures == null) {
            existingFeatures = new HashSet<String>();
        }
        logger.debug("Existing features:" + existingFeatures);
        util.setLowerCaseFeatures(true);

        Set<String> directories = getClassesDirectories();
        String[] binaryInputs = getBinaryInputs(classFiles, directories);
        Set<String> scannedFeatureList = runBinaryScanner(existingFeatures, binaryInputs);
        def missingLibertyFeatures = new HashSet<String>();
        if (scannedFeatureList != null) {
            missingLibertyFeatures.addAll(scannedFeatureList);

            util.setLowerCaseFeatures(false);
            // get set of user defined features so they can be omitted from the generated file that will be written
            Set<String> userDefinedFeatures = optimize ? existingFeatures : util.getServerFeatures(serverDirectory, libertyDirPropertyFiles, generatedFiles);
            logger.debug("User defined features:" + userDefinedFeatures);
            util.setLowerCaseFeatures(true);
            if (userDefinedFeatures != null) {
                missingLibertyFeatures.removeAll(userDefinedFeatures);
            }
        }
        logger.debug("Features detected by binary scanner which are not in server.xml : " + missingLibertyFeatures);

        def newServerXmlSrc = new File(server.configDirectory, GENERATED_FEATURES_FILE_PATH);
        if (missingLibertyFeatures.size() > 0) {
            // Create specialized server.xml
            try {
                ServerConfigXmlDocument configDocument = ServerConfigXmlDocument.newInstance();
                configDocument.createComment(HEADER);
                for (String missing : missingLibertyFeatures) {
                    logger.debug(String.format("Adding missing feature %s to %s.", missing, GENERATED_FEATURES_FILE_PATH));
                    configDocument.createFeature(missing);
                }
                configDocument.writeXMLDocument(newServerXmlSrc);
                logger.debug("Created file "+newServerXmlSrc);
                // Add a reference to this new file in existing server.xml.
                def serverXml = findConfigFile("server.xml", server.serverXmlFile);
                def doc = getServerXmlDocFromConfig(serverXml);
                logger.debug("Xml document we'll try to update after generate features doc="+doc+" file="+serverXml);
                addGenerationCommentToConfig(doc, serverXml);

                logger.info("Generated the following additional features: " + missingLibertyFeatures);
            } catch(ParserConfigurationException | TransformerException | IOException e) {
                logger.debug("Exception creating the server features file", e);
                logger.error("Error attempting to create the server feature file. Ensure your id has write permission to the server installation directory.");
                return;
            }
        } else {
            logger.debug("No additional features were generated.");
        }
    }

    /**
     * Gets the binary scanner jar file from the local cache.
     * Downloads it first from connected repositories such as Maven Central if a newer release is available than the cached version.
     * Note: Maven updates artifacts daily by default based on the last updated timestamp. Users should use 'mvn -U' to force updates if needed.
     * 
     * @return The File object of the binary scanner jar in the local cache.
     * @throws PluginExecutionException
     */
    private File getBinaryScannerJarFromRepository() throws PluginExecutionException {
        try {
            return ArtifactDownloadUtil.downloadArtifact(project, BINARY_SCANNER_MAVEN_GROUP_ID, BINARY_SCANNER_MAVEN_ARTIFACT_ID, BINARY_SCANNER_MAVEN_TYPE, BINARY_SCANNER_MAVEN_VERSION);
        } catch (Exception e) {
            throw new PluginExecutionException("Could not retrieve the binary scanner jar. Ensure you have a connection to Maven Central or another repository that contains the jar configured in your build.gradle: " + e.getMessage(), e);
        }
    }

    /**
     * Return specificFile if it exists; otherwise check for a file with the requested name in the
     * configDirectory and return it if it exists. Null is returned if a file does not exist in 
     * either location.
     */
    private File findConfigFile(String fileName, File specificFile) {
        if (specificFile != null && specificFile.exists()) {
            return specificFile;
        }

        if (server.configDirectory == null) {
            return null;
        }
        File f = new File(server.configDirectory, fileName);
        if (f.exists()) {
            return f;
        } else {
            return null;
        }
    }

    // Convert a file into a document object
    private ServerConfigXmlDocument getServerXmlDocFromConfig(File serverXml) {
        if (serverXml == null || !serverXml.exists()) {
            return null;
        }
        try {
            return ServerConfigXmlDocument.newInstance(serverXml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            logger.debug("Exception creating server.xml object model", e);
        }
        return null;
    }

    /**
     * Add a comment to server.xml to warn them we created another file with features in it.
     */
    private void addGenerationCommentToConfig(ServerConfigXmlDocument doc, File serverXml) {
        if (doc == null) {
            return;
        }
        try {
            if (doc.findFMComment(FEATURES_FILE_MESSAGE) == null) {
                doc.createFMComment(FEATURES_FILE_MESSAGE);
                doc.writeXMLDocument(serverXml);    
            }
        } catch (IOException | TransformerException e) {
            log.debug("Exception adding comment to server.xml", e);
        }
        return;
    }

    private Set<String> runBinaryScanner(Set<String> currentFeatureSet, String[] binaryInputs) throws PluginExecutionException {
        Set<String> featureList = null;
        logger.debug(" ");
        logger.debug("binaryScanner="+binaryScanner);
        if (binaryScanner != null && binaryScanner.exists()) {
            ClassLoader cl = this.getClass().getClassLoader();
            try {
                URL[] u = new URL[1];
                u[0] = binaryScanner.toURI().toURL();
                URLClassLoader ucl = new URLClassLoader(u, cl);
                Class driveScan = ucl.loadClass("com.ibm.ws.report.binary.cmdline.DriveScan");
                // args: String[], String, String, List, java.util.Locale
                java.lang.reflect.Method driveScanMavenFeatureList = driveScan.getMethod("driveScanMavenFeatureList", String[].class, String.class, String.class, List.class, java.util.Locale.class);
                if (driveScanMavenFeatureList == null) {
                    logger.debug("Error finding binary scanner method using reflection");
                    return null;
                }
 
                String eeVersion = getEEVersion(project); 
                String mpVersion = getMPVersion(project);
                List<String> currentFeatures = new ArrayList<String>(currentFeatureSet);
                logger.debug("The following messages are from the application binary scanner used to generate Liberty features");
                featureList = (Set<String>) driveScanMavenFeatureList.invoke(null, binaryInputs, eeVersion, mpVersion, currentFeatures, java.util.Locale.getDefault());
                logger.debug("End of messages from application binary scanner. Features recommended :");
                for (String s : featureList) {logger.debug(s);};
            } catch (MalformedURLException|ClassNotFoundException|NoSuchMethodException|IllegalAccessException|java.lang.reflect.InvocationTargetException x){
                // TODO Figure out what to do when there is a problem scanning the features
                logger.error("Exception:"+x.getClass().getName());
                Object o = x.getCause();
                if (o != null) {
                    logger.warn("Caused by exception:"+x.getCause().getClass().getName());
                    logger.warn("Caused by exception message:"+x.getCause().getMessage());
                }
                logger.error(x.getMessage());
            }
        } else {
            if (binaryScanner == null) {
                throw new PluginExecutionException("The binary scanner jar location is not defined.");
            } else {
                throw new PluginExecutionException("Could not find the binary scanner jar at " + binaryScanner.getAbsolutePath());
            }
        }
        return featureList;
    }

    private String[] getBinaryInputs(List<String> classFiles, Set<String> classDirectories) throws PluginExecutionException {
        Collection<String> resultSet;
        if (classFiles != null && !classFiles.isEmpty()) {
            resultSet = classFiles;
        } else {
            if (classDirectories == null || classDirectories.isEmpty()) {
                throw new PluginExecutionException("Error collecting list of directories to send to binary scanner, list is null or empty.");
            }
            resultSet = classDirectories;
        }

        for (String s : resultSet) {
            logger.debug("Binary scanner input: " + s);
        }

        String[] result = resultSet.toArray(new String[resultSet.size()]);
        return result;
    }

    private Set<String> getClassesDirectories() {
        Set<String> classesDirectories = new ArrayList<String>();
        project.sourceSets.main.getOutput().getClassesDirs().each {
            classesDirectories.add( it.getAbsolutePath() );
        }
        return classesDirectories;
    }

    private getEEVersion(Object project) {
        return null;
    }

    private getMPVersion(Object project) {
        return null;
    }
}
