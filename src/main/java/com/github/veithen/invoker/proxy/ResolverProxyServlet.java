/*-
 * #%L
 * Resolver Proxy Maven Plugin
 * %%
 * Copyright (C) 2018 Andreas Veithen
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
package com.github.veithen.invoker.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.codehaus.plexus.util.IOUtil;

@SuppressWarnings("serial")
final class ResolverProxyServlet extends HttpServlet {
    private final Log log;
    private final RepositorySystem repositorySystem;
    private final ArtifactResolver resolver;
    private final MavenSession session;

    ResolverProxyServlet(Log log, RepositorySystem repositorySystem, ArtifactResolver resolver, MavenSession session) {
        this.log = log;
        this.repositorySystem = repositorySystem;
        this.resolver = resolver;
        this.session = session;
    }

    private Dependency parsePath(String path) {
        int fileSlash = path.lastIndexOf('/');
        if (fileSlash == -1) {
            return null;
        }
        int versionSlash = path.lastIndexOf('/', fileSlash-1);
        if (versionSlash == -1) {
            return null;
        }
        int artifactSlash = path.lastIndexOf('/', versionSlash-1);
        if (artifactSlash == -1) {
            return null;
        }
        String groupId = path.substring(0, artifactSlash).replace('/', '.');
        String artifactId = path.substring(artifactSlash+1, versionSlash);
        String version = path.substring(versionSlash+1, fileSlash);
        String file = path.substring(fileSlash+1);
        if (!file.startsWith(artifactId + "-" + version)) {
            return null;
        }
        String remainder = file.substring(artifactId.length() + version.length() + 1); // Either ".<type>" or "-<classifier>.<type>"
        if (remainder.length() == 0) {
            return null;
        }
        String classifier;
        String type;
        if (remainder.charAt(0) == '-') {
            int dot = remainder.indexOf('.');
            if (dot == -1) {
                return null;
            }
            classifier = remainder.substring(1, dot);
            type = remainder.substring(dot+1);
        } else if (remainder.charAt(0) == '.') {
            classifier = null;
            type = remainder.substring(1);
        } else {
            return null;
        }
        if (type.endsWith(".md5") || type.endsWith(".sha1")) {
            return null;
        }
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(version);
        dependency.setClassifier(classifier);
        dependency.setType(type);
        return dependency;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String path = request.getPathInfo();
        Dependency dependency = null;
        if (path != null && path.startsWith("/")) {
            dependency = parsePath(path.substring(1));
        }
        if (dependency == null) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Returning 404 for %s", path));
            }
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Artifact dependencyArtifact = repositorySystem.createDependencyArtifact(dependency);
        File file;
        try {
            file = resolver.resolveArtifact(session.getProjectBuildingRequest(), dependencyArtifact).getArtifact().getFile();
        } catch (ArtifactResolverException ex) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("%s (%s) couldn't be resolved", path, dependencyArtifact), ex);
            }
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug(String.format("%s (%s) resolved to %s", path, dependencyArtifact, file));
        }
        try (FileInputStream in = new FileInputStream(file)) {
            IOUtil.copy(in, response.getOutputStream());
        }
    }
}
