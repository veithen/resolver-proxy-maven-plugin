/*-
 * #%L
 * Resolver Proxy Maven Plugin
 * %%
 * Copyright (C) 2018 - 2022 Andreas Veithen
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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.eclipse.jetty.server.Server;

@Mojo(name = "stop", defaultPhase = LifecyclePhase.POST_INTEGRATION_TEST, threadSafe = true)
public class StopMojo extends AbstractMojo {
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Server server = (Server) getPluginContext().get(Constants.SERVER_KEY);
        if (server != null) {
            try {
                server.stop();
            } catch (Exception ex) {
                throw new MojoExecutionException(
                        String.format("Failed to stop embedded Jetty server: %s", ex.getMessage()),
                        ex);
            }
            getLog().info("Resolver proxy stopped");
        }
    }
}
