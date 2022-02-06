/*-
 * #%L
 * Resolver Proxy Maven Plugin
 * %%
 * Copyright (C) 2018 - 2020 Andreas Veithen
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
import java.io.IOException;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.utils.io.FileUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

@Mojo(name = "start", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, threadSafe = true)
public class StartMojo extends AbstractMojo {
    @Component private ArtifactResolver resolver;

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Parameter(property = "session", required = true, readonly = true)
    private MavenSession session;

    /** The HTTP port to use for the resolver proxy; for debugging purposes only. */
    @Parameter(property = "resolverProxyPort", readonly = true)
    private int resolverProxyPort = -1;

    @Parameter(defaultValue = "${project.build.directory}/settings.xml", readonly = true)
    private File settingsFile;

    @Parameter(defaultValue = "${project.build.directory}/it-repo", readonly = true)
    private File localRepositoryPath;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Log log = getLog();
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        if (resolverProxyPort != -1) {
            connector.setPort(resolverProxyPort);
        }
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(server, "/");
        ServletHolder servlet =
                new ServletHolder(
                        new ResolverProxyServlet(
                                log, resolver, session, project.getPluginManagement()));
        context.addServlet(servlet, "/*");
        context.setErrorHandler(
                new ErrorHandler() {
                    @Override
                    public void handle(
                            String target,
                            Request baseRequest,
                            HttpServletRequest request,
                            HttpServletResponse response)
                            throws IOException, ServletException {
                        Throwable exception =
                                (Throwable) request.getAttribute("javax.servlet.error.exception");
                        if (exception != null) {
                            log.error("An error occurred in the resolver proxy", exception);
                        }
                        super.handle(target, baseRequest, request, response);
                    }
                });
        try {
            server.start();
        } catch (Exception ex) {
            throw new MojoExecutionException(
                    String.format("Failed to start embedded Jetty server: %s", ex.getMessage()),
                    ex);
        }
        int port = connector.getLocalPort();
        log.info(String.format("Resolver proxy started on port %s", port));

        Properties props = project.getProperties();
        props.setProperty("resolverProxyPort", String.valueOf(port));
        if (!props.containsKey("invoker.settingsFile")) {
            try {
                FileUtils.copyURLToFile(StartMojo.class.getResource("settings.xml"), settingsFile);
            } catch (IOException ex) {
                throw new MojoExecutionException(
                        String.format("Failed to create %s: %s", settingsFile, ex.getMessage()),
                        ex);
            }
            props.setProperty("invoker.settingsFile", settingsFile.getAbsolutePath());
        }
        if (!props.containsKey("invoker.localRepositoryPath")) {
            props.setProperty("invoker.localRepositoryPath", localRepositoryPath.getAbsolutePath());
        }

        getPluginContext().put(Constants.SERVER_KEY, server);
    }
}
