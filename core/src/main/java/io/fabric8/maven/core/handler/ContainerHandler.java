/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.core.handler;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.maven.core.config.ResourceConfig;
import io.fabric8.maven.core.config.VolumeConfig;
import io.fabric8.maven.core.util.KubernetesResourceUtil;
import io.fabric8.maven.core.util.MavenUtil;
import io.fabric8.maven.core.util.SpringBootProperties;
import io.fabric8.maven.docker.access.PortMapping;
import io.fabric8.maven.docker.config.BuildImageConfiguration;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.utils.Strings;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static io.fabric8.maven.core.util.MavenUtil.hasClass;
import static io.fabric8.utils.PropertiesHelper.getInteger;

/**
 * @author roland
 * @since 08/04/16
 */
class ContainerHandler {
    private static final Integer DEFAULT_MANAGEMENT_PORT = 8080;

    private final EnvVarHandler envVarHandler;
    private final ProbeHandler probeHandler;
    private final MavenProject project;

    public ContainerHandler(MavenProject project, EnvVarHandler envVarHandler, ProbeHandler probeHandler) {
        this.envVarHandler = envVarHandler;
        this.probeHandler = probeHandler;
        this.project = project;
    }

    List<Container> getContainers(ResourceConfig config, List<ImageConfiguration> images)  {
        List<Container> ret = new ArrayList<>();
        int idx = 1;
        for (ImageConfiguration imageConfig : images) {
            if (imageConfig.getBuildConfiguration() != null) {
                Probe livenessProbe = probeHandler.getProbe(config.getLiveness());
                Probe readinessProbe = probeHandler.getProbe(config.getReadiness());
                // lets only discover probes for the last contributed image (spring boot etc)
                if (idx++ == images.size()) {
                    if (livenessProbe == null) {
                        livenessProbe = discoverLivenessProbe();
                    }
                    if (readinessProbe == null) {
                        readinessProbe = discoverReadinessProbe();
                    }
                }
                Container container = new ContainerBuilder()
                    .withName(KubernetesResourceUtil.extractContainerName(project, imageConfig))
                    .withImage(imageConfig.getName())
                    .withImagePullPolicy(getImagePullPolicy(config))
                    .withEnv(envVarHandler.getEnvironmentVariables(config.getEnv()))
                    .withSecurityContext(createSecurityContext(config))
                    .withPorts(getContainerPorts(imageConfig))
                    .withVolumeMounts(getVolumeMounts(config))
                    .withLivenessProbe(livenessProbe)
                    .withReadinessProbe(readinessProbe)
                    .build();
                ret.add(container);
            }
        }
        return ret;
    }

    private Probe discoverReadinessProbe() {
        Probe probe = discoverSpringBootHealthCheck(10);
        if( probe!=null)
            return probe;
        probe = discoverKarafProbe("/readiness-check", 10);
        return probe;
    }

    private Probe discoverLivenessProbe() {
        Probe probe = discoverSpringBootHealthCheck(180);
        if( probe!=null)
            return probe;
        probe = discoverKarafProbe("/health-check", 180);
        return probe;
    }

    private Probe discoverSpringBootHealthCheck(int initialDelay) {
        if (hasClass(project, "org.springframework.boot.actuate.health.HealthIndicator")) {
            Properties properties = MavenUtil.getSpringBootApplicationProperties(project);
            Integer port = getInteger(properties, SpringBootProperties.MANAGEMENT_PORT, getInteger(properties, SpringBootProperties.SERVER_PORT, DEFAULT_MANAGEMENT_PORT));

            // lets default to adding a spring boot actuator health check
            return new ProbeBuilder().withNewHttpGet().
                    withNewPort(port).withPath("/health").endHttpGet().withInitialDelaySeconds(initialDelay).build();
        }
        return null;
    }

    //
    // Karaf has a readiness/health URL exposed if the fabric8-karaf-check feature is installed.
    //
    private Probe discoverKarafProbe(String path, int initialDelay) {

        for (Plugin plugin : project.getBuildPlugins()) {
            if( "karaf-maven-plugin".equals(plugin.getArtifactId()) ) {
                Xpp3Dom configuration = (Xpp3Dom) plugin.getConfiguration();
                if( configuration == null )
                    return null;
                Xpp3Dom startupFeatures = configuration.getChild("startupFeatures");
                if( startupFeatures == null)
                    return null;

                for (Xpp3Dom feature : startupFeatures.getChildren("feature")) {
                    if( "fabric8-karaf-checks".equals(feature.getValue())  ) {
                        // Use the default 8181 port
                        // TODO: handle the case where the user changes the default port
                        return new ProbeBuilder().withNewHttpGet().
                                withNewPort(8181).withPath(path).endHttpGet().withInitialDelaySeconds(initialDelay).build();
                    }
                }
            }
        }
        return null;
    }


    private String getImagePullPolicy(ResourceConfig config) {
        String pullPolicy = config.getImagePullPolicy();
        String version = project.getVersion();
        if (Strings.isNullOrBlank(pullPolicy) &&
            version != null && version.endsWith("SNAPSHOT")) {
            // TODO: Is that what we want ?
            return "PullAlways";
        }
        return pullPolicy;
    }

    private SecurityContext createSecurityContext(ResourceConfig config) {
        return new SecurityContextBuilder()
            .withPrivileged(config.isContainerPrivileged())
            .build();
    }


    private List<VolumeMount> getVolumeMounts(ResourceConfig config) {
        List<VolumeConfig> volumeConfigs = config.getVolumes();

        List<VolumeMount> ret = new ArrayList<>();
        if (volumeConfigs != null) {
            for (VolumeConfig volumeConfig : volumeConfigs) {
                List<String> mounts = volumeConfig.getMounts();
                if (mounts != null) {
                    for (String mount : mounts) {
                        ret.add(new VolumeMountBuilder()
                                    .withName(volumeConfig.getName())
                                    .withMountPath(mount)
                                    .withReadOnly(false).build());
                    }
                }
            }
        }
        return ret;
    }

    private List<ContainerPort> getContainerPorts(ImageConfiguration imageConfig) {
        BuildImageConfiguration buildConfig = imageConfig.getBuildConfiguration();
        List<String> ports = buildConfig.getPorts();
        if (ports != null) {
            List<ContainerPort> ret = new ArrayList<>();
            PortMapping portMapping = new PortMapping(ports, project.getProperties());
            JSONArray portSpecs = portMapping.toJson();
            for (int i = 0; i < portSpecs.length(); i ++) {
                JSONObject portSpec = portSpecs.getJSONObject(i);
                ret.add(extractContainerPort(portSpec));
            }
            return ret;
        } else {
            return null;
        }
    }

    private ContainerPort extractContainerPort(JSONObject portSpec) {
        ContainerPortBuilder portBuilder = new ContainerPortBuilder()
            .withContainerPort(portSpec.getInt("containerPort"));
        if (portSpec.has("hostPort")) {
            portBuilder.withHostPort(portSpec.getInt("hostPort"));
        }
        if (portSpec.has("protocol")) {
            portBuilder.withProtocol(portSpec.getString("protocol").toUpperCase());
        }
        if (portSpec.has("hostIP")) {
            portBuilder.withHostIP(portSpec.getString("hostIP"));
        }
        return portBuilder.build();
    }

}
