/*-
 * #%L
 * Resolver Proxy Maven Plugin
 * %%
 * Copyright (C) 2018 - 2025 Andreas Veithen-Knowles
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.github.veithen.maven.resolver.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel.MapMode;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.version.Version;
import org.eclipse.jetty.ee10.servlet.HttpOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("serial")
final class ResolverProxyServlet extends HttpServlet {
    private static final Logger log = LoggerFactory.getLogger(ResolverProxyServlet.class);

    private final RepositorySystem repositorySystem;
    private final ArtifactResolver resolver;
    private final MavenSession session;
    private final PluginManagement pluginManagement;

    ResolverProxyServlet(
            RepositorySystem repositorySystem,
            ArtifactResolver resolver,
            MavenSession session,
            PluginManagement pluginManagement) {
        this.repositorySystem = repositorySystem;
        this.resolver = resolver;
        this.session = session;
        this.pluginManagement = pluginManagement;
    }

    private DefaultArtifactCoordinate parseArtifactRequest(String path) {
        int fileSlash = path.lastIndexOf('/');
        if (fileSlash == -1) {
            return null;
        }
        int versionSlash = path.lastIndexOf('/', fileSlash - 1);
        if (versionSlash == -1) {
            return null;
        }
        int artifactSlash = path.lastIndexOf('/', versionSlash - 1);
        if (artifactSlash == -1) {
            return null;
        }
        String groupId = path.substring(0, artifactSlash).replace('/', '.');
        String artifactId = path.substring(artifactSlash + 1, versionSlash);
        String version = path.substring(versionSlash + 1, fileSlash);
        String file = path.substring(fileSlash + 1);
        if (!file.startsWith(artifactId + "-" + version)) {
            return null;
        }
        String remainder =
                file.substring(
                        artifactId.length()
                                + version.length()
                                + 1); // Either ".<type>" or "-<classifier>.<type>"
        if (remainder.length() == 0) {
            return null;
        }
        String classifier;
        String extension;
        if (remainder.charAt(0) == '-') {
            int dot = remainder.indexOf('.');
            if (dot == -1) {
                return null;
            }
            classifier = remainder.substring(1, dot);
            extension = remainder.substring(dot + 1);
        } else if (remainder.charAt(0) == '.') {
            classifier = null;
            extension = remainder.substring(1);
        } else {
            return null;
        }
        DefaultArtifactCoordinate artifact = new DefaultArtifactCoordinate();
        artifact.setGroupId(groupId);
        artifact.setArtifactId(artifactId);
        artifact.setVersion(version);
        artifact.setClassifier(classifier);
        artifact.setExtension(extension);
        return artifact;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        process(request, response, false);
    }

    @Override
    protected void doHead(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        process(request, response, true);
    }

    private void process(HttpServletRequest request, HttpServletResponse response, boolean head)
            throws ServletException, IOException {
        String path = request.getPathInfo();
        if (path != null && path.startsWith("/")) {
            try {
                process(path.substring(1), response, head);
            } catch (ServletException | IOException ex) {
                log.debug("Error processing request for {}", path, ex);
                throw ex;
            }
        } else {
            log.error("Expected pathInfo starting with '/'; was: {}", path);
        }
    }

    private void process(String path, HttpServletResponse response, boolean head)
            throws IOException, ServletException {
        String orgPath = path;
        String checksumType = null;
        int idx = path.lastIndexOf('.');
        if (idx != -1) {
            String suffix = path.substring(idx + 1);
            if (suffix.equals("md5") || suffix.equals("sha1")) {
                path = path.substring(0, idx);
                checksumType = suffix;
            }
        }
        if (path.endsWith("/maven-metadata.xml")) {
            int fileSlash = path.lastIndexOf('/');
            int artifactSlash = path.lastIndexOf('/', fileSlash - 1);
            if (artifactSlash != -1) {
                processMetadataRequest(
                        orgPath,
                        path.substring(0, artifactSlash).replace('/', '.'),
                        path.substring(artifactSlash + 1, fileSlash),
                        checksumType,
                        response,
                        head);
                return;
            }
        } else {
            DefaultArtifactCoordinate artifact = parseArtifactRequest(path);
            if (artifact != null) {
                processArtifactRequest(orgPath, artifact, checksumType, response, head);
                return;
            }
        }

        log.debug("Returning 404 for {}", path);
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return;
    }

    private void processArtifactRequest(
            String path,
            DefaultArtifactCoordinate artifact,
            String checksumType,
            HttpServletResponse response,
            boolean head)
            throws IOException {
        // Note that we don't attempt to resolve checksum files. ArtifactResolver would be able to
        // do that for artifacts downloaded from a remote repository, but for artifacts from the
        // reactor it will trigger an error. It may also do unnecessary attempts to download them
        // from remote repositories.
        File file;
        try {
            file =
                    resolver.resolveArtifact(session.getProjectBuildingRequest(), artifact)
                            .getArtifact()
                            .getFile();
        } catch (ArtifactResolverException ex) {
            log.debug("{} ({}) couldn't be resolved", path, artifact, ex);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        if (checksumType != null) {
            File checksumFile =
                    new File(
                            file.getParent(), String.format("%s.%s", file.getName(), checksumType));
            if (checksumFile.exists()) {
                // Just continue and send the existing checksum file.
                file = checksumFile;
            } else {
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance(checksumType);
                } catch (NoSuchAlgorithmException ex) {
                    log.error("Could not create message digest", ex);
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    return;
                }
                byte[] buffer = new byte[4096];
                try (FileInputStream in = new FileInputStream(file)) {
                    int c;
                    while ((c = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, c);
                    }
                }
                String checksum = Hex.encodeHexString(digest.digest(), false);
                log.debug(
                        "{} served by computing checksum of {} ({}): {}",
                        path,
                        file,
                        artifact,
                        checksum);
                response.getWriter().write(checksum);
                return;
            }
        }

        log.debug("{} ({}, checksumType={}) resolved to {}", path, artifact, checksumType, file);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long size = raf.length();
            response.setContentLengthLong(size);
            if (!head) {
                ((HttpOutput) response.getOutputStream())
                        .sendContent(raf.getChannel().map(MapMode.READ_ONLY, 0, size));
            }
        }
    }

    private void processMetadataRequest(
            String path,
            String groupId,
            String artifactId,
            String checksumType,
            HttpServletResponse response,
            boolean head)
            throws IOException, ServletException {
        String latestVersion;
        List<String> versions;
        String key = Plugin.constructKey(groupId, artifactId);
        Plugin plugin =
                pluginManagement == null ? null : pluginManagement.getPluginsAsMap().get(key);
        if (plugin != null) {
            String version = plugin.getVersion();
            log.debug("{} ({}) served by generating metadata for version {}", path, key, version);
            latestVersion = version;
            versions = Collections.singletonList(version);
        } else {
            try {
                VersionRangeRequest request =
                        new VersionRangeRequest(
                                new DefaultArtifact(groupId, artifactId, "", "pom", "[0,)"),
                                RepositoryUtils.toRepos(
                                        session.getProjectBuildingRequest()
                                                .getRemoteRepositories()),
                                null);
                VersionRangeResult result =
                        repositorySystem.resolveVersionRange(
                                session.getRepositorySession(), request);
                log.debug("Resolved version range {}: {}", request, result.getVersions());
                if (result.getVersions().isEmpty()) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                latestVersion = result.getHighestVersion().toString();
                versions =
                        result.getVersions().stream()
                                .map(Version::toString)
                                .collect(Collectors.toList());
            } catch (VersionRangeResolutionException ex) {
                throw new ServletException(ex);
            }
        }
        if (!head) {
            MessageDigest digest;
            OutputStream out;
            if (checksumType == null) {
                digest = null;
                out = response.getOutputStream();
            } else {
                try {
                    digest = MessageDigest.getInstance(checksumType);
                } catch (NoSuchAlgorithmException ex) {
                    log.error("Could not create message digest", ex);
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    return;
                }
                out = new DigestOutputStream(NullOutputStream.INSTANCE, digest);
            }
            try {
                XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
                writer.writeStartDocument("utf-8", "1.0");
                writer.writeStartElement("metadata");
                writer.writeStartElement("groupId");
                writer.writeCharacters(groupId);
                writer.writeEndElement();
                writer.writeStartElement("artifactId");
                writer.writeCharacters(artifactId);
                writer.writeEndElement();
                writer.writeStartElement("versioning");
                writer.writeStartElement("latest");
                writer.writeCharacters(latestVersion);
                writer.writeEndElement();
                writer.writeStartElement("versions");
                for (String version : versions) {
                    writer.writeStartElement("version");
                    writer.writeCharacters(version);
                    writer.writeEndElement();
                }
                writer.writeEndElement();
                writer.writeEndElement();
                writer.writeEndElement();
                writer.writeEndDocument();
                writer.flush();
            } catch (XMLStreamException ex) {
                throw new ServletException(ex);
            }
            if (digest != null) {
                response.getWriter().write(Hex.encodeHexString(digest.digest(), false));
            }
        }
    }
}
