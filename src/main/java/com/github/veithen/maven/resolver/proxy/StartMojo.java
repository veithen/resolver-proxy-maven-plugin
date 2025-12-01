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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;

@Mojo(name = "start", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, threadSafe = true)
public class StartMojo extends AbstractMojo {
    private static final Logger log = LoggerFactory.getLogger(StartMojo.class);

    @Component private RepositorySystem repositorySystem;
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
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        if (resolverProxyPort != -1) {
            connector.setPort(resolverProxyPort);
        }
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler("/");
        ServletHolder servlet =
                new ServletHolder(
                        new ResolverProxyServlet(
                                repositorySystem,
                                resolver,
                                session,
                                project.getPluginManagement()));
        context.addServlet(servlet, "/*");
        context.setErrorHandler(
                new ErrorHandler() {
                    @Override
                    public boolean handle(Request request, Response response, Callback callback)
                            throws Exception {
                        Throwable exception =
                                (Throwable) request.getAttribute("javax.servlet.error.exception");
                        if (exception != null) {
                            log.error("An error occurred in the resolver proxy", exception);
                        }
                        return super.handle(request, response, callback);
                    }
                });
        server.setHandler(context);
        try {
            server.start();
        } catch (Exception ex) {
            throw new MojoExecutionException(
                    String.format("Failed to start embedded Jetty server: %s", ex.getMessage()),
                    ex);
        }
        int port = connector.getLocalPort();
        log.info("Resolver proxy started on port {}", port);

        Properties props = project.getProperties();
        props.setProperty("resolverProxyPort", String.valueOf(port));
        if (!props.containsKey("invoker.settingsFile")) {
            settingsFile.getParentFile().mkdirs();
            try (InputStream in = StartMojo.class.getResourceAsStream("settings.xml")) {
                Files.copy(in, settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
