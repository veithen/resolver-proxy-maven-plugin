/*-
 * #%L
 * local-repo-proxy-maven-plugin
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
package com.github.veithen.repoproxy;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

@Mojo(name="start", defaultPhase=LifecyclePhase.PRE_INTEGRATION_TEST, threadSafe=true)
public class StartMojo extends AbstractMojo {
    @Component
    private RepositorySystem repositorySystem;

    @Component
    private ArtifactResolver resolver;

    @Parameter(property="project", required=true, readonly=true)
    private MavenProject project;

    @Parameter(property="session", required=true, readonly=true)
    private MavenSession session;

    public void execute() throws MojoExecutionException, MojoFailureException {
        Log log = getLog();
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(server, "/");
        ServletHolder servlet = new ServletHolder(new RepoProxyServlet(log, repositorySystem, resolver, session));
        context.addServlet(servlet, "/*");
        try {
            server.start();
        } catch (Exception ex) {
            throw new MojoExecutionException(String.format("Failed to start embedded Jetty server: %s", ex.getMessage()), ex);
        }
        int port = connector.getLocalPort();
        if (log.isDebugEnabled()) {
            log.debug(String.format("Local repository proxy started on port %s", port));
        }
        project.getProperties().setProperty("localRepositoryProxyPort", String.valueOf(port));
    }
}
