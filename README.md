# resolver-proxy-maven-plugin

This Maven plugin contains experimental code to support [maven-invoker-plugin](https://maven.apache.org/plugins/maven-invoker-plugin/); the intent is to eventually integrate that code directly into maven-invoker-plugin.

The plugin starts an HTTP server that acts as a proxy to the artifact resolver in the Maven session in which the plugin is executed. From the perspective of the client (which would be the project invoked by maven-invoker-plugin), that server behaves like a remote Maven repository. It is meant as an alternative to the [invoker:install](https://maven.apache.org/plugins/maven-invoker-plugin/install-mojo.html) goal (when used in conjunction with a dedicated local repository as described [here](https://maven.apache.org/plugins/maven-invoker-plugin/examples/fast-use.html)). The idea is that instead of prepopulating the local repository used by the invoked project, it lets the invoked project pull artifacts on demand from the proxy running in the invoking project.

This approach solves the following issues:

*   invoker:install is not threadsafe; see [MINVOKER-191](https://issues.apache.org/jira/browse/MINVOKER-191).
*   The invoked project may have dependencies or use plugins not used by the invoking project and which therefore may not be available in the local repository of the invoking project. These artifacts would then end up being downloaded by the artifact resolver in the invoked project every time maven-invoker-plugin is executed. Note that it would be possible to alleviate that problem by improving maven-invoker-plugin so that it analyzes the invoked projects and prefetches all transitive dependencies and plugins. However, that would never solve the problem completely because a plugin in the invoked project may request additional artifacts (e.g. [dependency:copy](https://maven.apache.org/plugins/maven-dependency-plugin/copy-mojo.html) would do that).

With resolver-proxy-maven-plugin, all remote artifact resolution in the invoked project is delegated to the invoking project, where the requested artifacts would then be resolved either from the reactor (which satisfies the use case for which invoker:install was designed), the local repository or a remote repository. This is achieved by configuring the proxy as a remote repository (or more precisely as a mirror of all remote repositories). This ensures that the invoked project will never download any remote artifacts itself.

A minimal configuration would look as follows:

    <plugin>
        <groupId>com.github.veithen.invoker</groupId>
        <artifactId>resolver-proxy-maven-plugin</artifactId>
        <executions>
            <execution>
                <goals>
                    <goal>start</goal>
                    <goal>stop</goal>
                </goals>
            </execution>
        </executions>
    </plugin>
    <plugin>
        <artifactId>maven-invoker-plugin</artifactId>
        <executions>
            <execution>
                <goals>
                    <goal>run</goal>
                </goals>
            </execution>
        </executions>
    </plugin>

The resolver-proxy:start goal will automatically configure maven-invoker-plugin to use a dedicated local repository and supply it with an appropriate `settings.xml` file, so that no further configuration is required. The settings file in question is [src/main/resources/com/github/veithen/invoker/proxy/settings.xml](src/main/resources/com/github/veithen/invoker/proxy/settings.xml); use that file as a template if you need to customize other Maven settings.
