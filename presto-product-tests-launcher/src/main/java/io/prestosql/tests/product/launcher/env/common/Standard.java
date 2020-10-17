/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.tests.product.launcher.env.common;

import io.airlift.log.Logger;
import io.prestosql.tests.product.launcher.docker.DockerFiles;
import io.prestosql.tests.product.launcher.env.DockerContainer;
import io.prestosql.tests.product.launcher.env.Environment;
import io.prestosql.tests.product.launcher.env.EnvironmentConfig;
import io.prestosql.tests.product.launcher.env.ServerPackage;
import io.prestosql.tests.product.launcher.testcontainers.PortBinder;
import org.testcontainers.containers.startupcheck.IsRunningStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;

import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.tests.product.launcher.docker.ContainerUtil.exposePort;
import static io.prestosql.tests.product.launcher.env.EnvironmentContainers.COORDINATOR;
import static io.prestosql.tests.product.launcher.env.EnvironmentContainers.TESTS;
import static io.prestosql.tests.product.launcher.env.EnvironmentContainers.WORKER;
import static io.prestosql.tests.product.launcher.env.EnvironmentContainers.WORKER_NTH;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.testcontainers.containers.BindMode.READ_ONLY;
import static org.testcontainers.containers.wait.strategy.Wait.forHealthcheck;
import static org.testcontainers.containers.wait.strategy.Wait.forLogMessage;
import static org.testcontainers.utility.MountableFile.forHostPath;

public final class Standard
        implements EnvironmentExtender
{
    private static final Logger log = Logger.get(Standard.class);

    public static final String CONTAINER_HEALTH_D = "/etc/health.d/";
    public static final String CONTAINER_CONF_ROOT = "/docker/presto-product-tests/";
    public static final String CONTAINER_PRESTO_ETC = CONTAINER_CONF_ROOT + "conf/presto/etc";
    public static final String CONTAINER_PRESTO_JVM_CONFIG = CONTAINER_PRESTO_ETC + "/jvm.config";
    public static final String CONTAINER_PRESTO_ACCESS_CONTROL_PROPERTIES = CONTAINER_PRESTO_ETC + "/access-control.properties";
    public static final String CONTAINER_PRESTO_CONFIG_PROPERTIES = CONTAINER_PRESTO_ETC + "/config.properties";
    public static final String CONTAINER_TEMPTO_PROFILE_CONFIG = "/docker/presto-product-tests/conf/tempto/tempto-configuration-profile-config-file.yaml";

    private final DockerFiles dockerFiles;
    private final PortBinder portBinder;

    private final String imagesVersion;
    private final File serverPackage;

    @Inject
    public Standard(
            DockerFiles dockerFiles,
            PortBinder portBinder,
            EnvironmentConfig environmentConfig,
            @ServerPackage File serverPackage)
    {
        this.dockerFiles = requireNonNull(dockerFiles, "dockerFiles is null");
        this.portBinder = requireNonNull(portBinder, "portBinder is null");
        this.imagesVersion = requireNonNull(environmentConfig, "environmentConfig is null").getImagesVersion();
        this.serverPackage = requireNonNull(serverPackage, "serverPackage is null");
        checkArgument(serverPackage.getName().endsWith(".tar.gz"), "Currently only server .tar.gz package is supported");
    }

    @Override
    public void extendEnvironment(Environment.Builder builder)
    {
        builder.addContainers(createPrestoMaster(), createTestsContainer());
    }

    @SuppressWarnings("resource")
    private DockerContainer createPrestoMaster()
    {
        DockerContainer container =
                createPrestoContainer(dockerFiles, serverPackage, "prestodev/centos7-oj11:" + imagesVersion, COORDINATOR)
                        .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("common/standard/access-control.properties")), CONTAINER_PRESTO_ACCESS_CONTROL_PROPERTIES)
                        .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("common/standard/config.properties")), CONTAINER_PRESTO_CONFIG_PROPERTIES);

        portBinder.exposePort(container, 8080); // Presto default port
        return container;
    }

    @SuppressWarnings("resource")
    private DockerContainer createTestsContainer()
    {
        DockerContainer container = new DockerContainer("prestodev/centos6-oj8:" + imagesVersion, TESTS)
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath()), "/docker/presto-product-tests")
                .withCommand("bash", "-xeuc", "echo 'No command provided' >&2; exit 69")
                .waitingFor(new WaitAllStrategy()) // don't wait
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy());

        return container;
    }

    @SuppressWarnings("resource")
    public static DockerContainer createPrestoContainer(DockerFiles dockerFiles, File serverPackage, String dockerImageName, String logicalName)
    {
        return new DockerContainer(dockerImageName, logicalName)
                .withNetworkAliases(logicalName + ".docker.cluster")
                .withExposedLogPaths("/var/presto/var/log", "/var/log/container-health.log")
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath()), "/docker/presto-product-tests")
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("conf/presto/etc/jvm.config")), CONTAINER_PRESTO_JVM_CONFIG)
                .withCopyFileToContainer(forHostPath(dockerFiles.getDockerFilesHostPath("health-checks/presto-health-check.sh")), CONTAINER_HEALTH_D + "presto-health-check.sh")
                // the server package is hundreds MB and file system bind is much more efficient
                .withFileSystemBind(serverPackage.getPath(), "/docker/presto-server.tar.gz", READ_ONLY)
                .withCommand("/docker/presto-product-tests/run-presto.sh")
                .withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
                .waitingForAll(forLogMessage(".*======== SERVER STARTED ========.*", 1), forHealthcheck())
                .withStartupTimeout(Duration.ofMinutes(5))
                .withHealthCheck(dockerFiles.getDockerFilesHostPath("health-checks/health.sh"));
    }

    public static void enablePrestoJavaDebugger(DockerContainer dockerContainer)
    {
        String logicalName = dockerContainer.getLogicalName();

        if (logicalName.equals(COORDINATOR)) {
            enablePrestoJavaDebugger(dockerContainer, logicalName, 5005);
        }

        if (logicalName.equals(WORKER)) {
            enablePrestoJavaDebugger(dockerContainer, logicalName, 5009);
        }

        if (logicalName.startsWith(WORKER_NTH)) {
            int workerNumber = Integer.valueOf(logicalName.substring(WORKER_NTH.length()));
            enablePrestoJavaDebugger(dockerContainer, logicalName, 5008 + workerNumber);
        }
    }

    private static void enablePrestoJavaDebugger(DockerContainer container, String containerName, int debugPort)
    {
        log.info("Enabling Java debugger for container: '%s' on port %d", containerName, debugPort);

        try {
            FileAttribute<Set<PosixFilePermission>> rwx = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx"));
            Path script = Files.createTempFile("enable-java-debugger", ".sh", rwx);
            Files.writeString(
                    script,
                    format(
                            "#!/bin/bash\n" +
                                    "printf '%%s\\n' '%s' >> '%s'\n",
                            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:" + debugPort,
                            CONTAINER_PRESTO_JVM_CONFIG),
                    UTF_8);
            container.withCopyFileToContainer(forHostPath(script), "/docker/presto-init.d/enable-java-debugger.sh");

            // expose debug port unconditionally when debug is enabled
            exposePort(container, debugPort);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
