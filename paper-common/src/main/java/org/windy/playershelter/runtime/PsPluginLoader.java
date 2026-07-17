package org.windy.playershelter.runtime;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Paper 原生插件的运行期库加载器（决策：改用 paper-plugin.yml → 无 {@code libraries:} 块，须走此类）。
 *
 * <p>传统 plugin.yml 的 {@code libraries:} 列表由服务端自动拉取；paper-plugin.yml 里改由 {@link PluginLoader}
 * 用 Maven 解析后挂进<b>本插件隔离类加载器</b>。JDBC 连接池 + SQLite/MySQL 驱动运行期不打进 jar，从中央仓拉。
 */
public final class PsPluginLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpath) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", "https://repo.maven.apache.org/maven2/").build());
        resolver.addDependency(new Dependency(new DefaultArtifact("com.zaxxer:HikariCP:5.0.0"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("org.xerial:sqlite-jdbc:3.47.1.0"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("com.mysql:mysql-connector-j:8.4.0"), null));
        classpath.addLibrary(resolver);
    }
}
