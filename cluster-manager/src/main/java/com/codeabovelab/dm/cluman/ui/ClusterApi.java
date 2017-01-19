/*
 * Copyright 2016 Code Above Lab LLC
 *
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

package com.codeabovelab.dm.cluman.ui;

import com.codeabovelab.dm.cluman.cluster.docker.ClusterConfigImpl;
import com.codeabovelab.dm.cluman.cluster.application.ApplicationService;
import com.codeabovelab.dm.cluman.cluster.docker.management.DockerService;
import com.codeabovelab.dm.cluman.cluster.docker.management.DockerUtils;
import com.codeabovelab.dm.cluman.cluster.docker.management.argument.GetContainersArg;
import com.codeabovelab.dm.cluman.cluster.registry.RegistryRepository;
import com.codeabovelab.dm.cluman.ds.DockerServiceRegistry;
import com.codeabovelab.dm.cluman.ds.clusters.*;
import com.codeabovelab.dm.cluman.ds.container.ContainerStorage;
import com.codeabovelab.dm.cluman.ds.nodes.NodeStorage;
import com.codeabovelab.dm.cluman.job.JobInstance;
import com.codeabovelab.dm.cluman.model.*;
import com.codeabovelab.dm.cluman.security.AccessContext;
import com.codeabovelab.dm.cluman.security.AccessContextFactory;
import com.codeabovelab.dm.cluman.security.SecuredType;
import com.codeabovelab.dm.cluman.source.DeployOptions;
import com.codeabovelab.dm.cluman.source.SourceService;
import com.codeabovelab.dm.cluman.ui.model.*;
import com.codeabovelab.dm.cluman.validate.ExtendedAssert;
import com.codeabovelab.dm.cluman.yaml.YamlUtils;
import com.codeabovelab.dm.common.cache.DefineCache;
import com.codeabovelab.dm.common.cache.MessageBusCacheInvalidator;
import com.codeabovelab.dm.common.security.Authorities;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.annotation.Secured;
import org.springframework.util.Assert;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.*;

/**
 * Rest controller for UI
 */
@RestController
@Slf4j
@RequestMapping(value = "/ui/api", produces = APPLICATION_JSON_VALUE)
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ClusterApi {

    private final DockerServiceRegistry dockerServiceRegistry;
    private final RegistryRepository registryRepository;

    private final NodeStorage nodeRegistry;
    private final SourceService sourceService;
    private final DiscoveryStorage discoveryStorage;
    private final ApplicationService applicationService;
    private final ContainerStorage containerStorage;
    private final FilterApi filterApi;
    private final AccessContextFactory aclContextFactory;

    @RequestMapping(value = "/clusters/", method = GET)
    public List<UiCluster> listClusters() {
        AccessContext ac = aclContextFactory.getContext();
        Collection<NodesGroup> clusters = this.discoveryStorage.getClusters();
        List<UiCluster> ucs = clusters.stream().map(c -> this.toUi(ac, c)).collect(Collectors.toList());
        ucs.sort(Comparator.naturalOrder());
        return ucs;
    }

    @RequestMapping(value = {
      "/cluster/{cluster}", // deprecated path
      "/clusters/{cluster}"
    }, method = GET)
    public UiCluster getCluster(@PathVariable("cluster") String cluster) {
        AccessContext ac = aclContextFactory.getContext();
        NodesGroup nodesGroup = discoveryStorage.getCluster(cluster);
        ExtendedAssert.notFound(nodesGroup, "Cluster was not found by " + cluster);
        return toUi(ac, nodesGroup);
    }

    private UiCluster toUi(AccessContext ac, NodesGroup cluster) {
        UiCluster uc = new UiCluster();
        final String name = cluster.getName();
        uc.setName(name);
        uc.getTitle().accept(cluster.getTitle());
        uc.getDescription().accept(cluster.getDescription());
        uc.getFilter().accept(cluster.getImageFilter());
        uc.setFeatures(cluster.getFeatures());
        if (cluster.getConfig() instanceof DockerBasedClusterConfig) {
            DockerBasedClusterConfig cfg = (DockerBasedClusterConfig) cluster.getConfig();
            uc.setConfig(ClusterConfigImpl.builder(cfg.getConfig()));
            if(cfg instanceof DockerClusterConfig) {
                uc.setManagers(((DockerClusterConfig)cfg).getManagers());
            }
        }
        try {
            DockerServiceInfo info = cluster.getDocker().getInfo();
            uc.setContainers(new UiCluster.Entry(info.getContainers(), info.getOffContainers()));
            uc.setNodes(new UiCluster.Entry(info.getNodeCount(), info.getOffNodeCount()));
        } catch (Exception e) {
            uc.setContainers(new UiCluster.Entry(0, 0));
            uc.setNodes(new UiCluster.Entry(0, 0));
            //nothing
        }
        try {
            Set<String> apps = uc.getApplications();
            List<Application> applications = applicationService.getApplications(name);
            applications.forEach(a -> apps.add(a.getName()));
        } catch (Exception e) {
            //nothing
        }
        UiPermission.inject(uc, ac, SecuredType.CLUSTER.id(name));
        return uc;
    }

    @RequestMapping(value = "/clusters/{cluster}/containers", method = GET)
    public ResponseEntity<Collection<UiContainer>> listContainers(@PathVariable("cluster") String cluster) {
        AccessContext ac = aclContextFactory.getContext();
        List<UiContainer> list = new ArrayList<>();
        NodesGroup nodesGroup = discoveryStorage.getCluster(cluster);
        ExtendedAssert.notFound(nodesGroup, "Cluster was not found by " + cluster);
        Collection<DockerContainer> containers = nodesGroup.getContainers().getContainers();
        Map<String, String> apps = UiUtils.mapAppContainer(applicationService, nodesGroup);
        for (DockerContainer container : containers) {
            UiContainer uic = UiContainer.from(container);
            uic.enrich(discoveryStorage, containerStorage);
            uic.setApplication(apps.get(uic.getId()));
            UiPermission.inject(uic, ac, SecuredType.CONTAINER.id(uic.getId()));
            list.add(uic);
        }
        Collections.sort(list);
        return new ResponseEntity<>(list, HttpStatus.OK);
    }

    @RequestMapping(value = "/clusters/{cluster}/containers", method = PUT)
    public ResponseEntity<Collection<UiContainer>> filteredListContainers(@PathVariable("cluster") String cluster,
                                                                          @RequestBody UISearchQuery searchQuery) {
        ResponseEntity<Collection<UiContainer>> listResponseEntity = listContainers(cluster);
        Collection<UiContainer> body = listResponseEntity.getBody();
        Collection<UiContainer> uiContainers = filterApi.listNodes(body, searchQuery);
        return new ResponseEntity<>(uiContainers, HttpStatus.OK);

    }

    @RequestMapping(value = "/clusters/{cluster}/services", method = GET)
    public ResponseEntity<Collection<UiContainerService>> listServices(@PathVariable("cluster") String cluster) {
        AccessContext ac = aclContextFactory.getContext();
        NodesGroup nodesGroup = discoveryStorage.getCluster(cluster);
        ExtendedAssert.notFound(nodesGroup, "Cluster was not found by " + cluster);
        Collection<ContainerService> services = nodesGroup.getContainers().getServices();
        Map<String, String> apps = UiUtils.mapAppContainer(applicationService, nodesGroup);
        List<UiContainerService> list = new ArrayList<>();
        for (ContainerService service : services) {
            UiContainerService uic = UiContainerService.from(service);
            uic.setApplication(apps.get(uic.getId()));
            UiPermission.inject(uic, ac, SecuredType.SERVICE.id(uic.getId()));
            list.add(uic);
        }
        Collections.sort(list);
        return new ResponseEntity<>(list, HttpStatus.OK);
    }

    @RequestMapping(value = "/clusters/{cluster}/info", method = GET)
    @Cacheable("SwarmInfo")
    @DefineCache(
            expireAfterWrite = 120_000,
            invalidator = MessageBusCacheInvalidator.class,
            invalidatorArgs = {
                    MessageBusCacheInvalidator.BUS_KEY, NodeEvent.BUS
            }
    )
    public DockerServiceInfo info(@PathVariable("cluster") String cluster) {
        return dockerServiceRegistry.getService(cluster).getInfo();
    }

    @RequestMapping(value = "/clusters/{cluster}/nodes-detailed", method = GET)
    @Cacheable("UINode")
    @DefineCache(
            expireAfterWrite = 120_000,
            invalidator = MessageBusCacheInvalidator.class,
            invalidatorArgs = {
                    MessageBusCacheInvalidator.BUS_KEY, NodeEvent.BUS
            }
    )
    public Collection<NodeInfo> listNodesDetailed(@PathVariable("cluster") String cluster) {
        return getNodesInternal(cluster);
    }

    private Collection<NodeInfo> getNodesInternal(String clusterName) {
        NodesGroup cluster = discoveryStorage.getCluster(clusterName);
        ExtendedAssert.notFound(cluster, "Can not find cluster: " + clusterName);
        return cluster.getNodes();
    }

    @RequestMapping(value = "/clusters/{cluster}/nodes", method = GET)
    public List<String> listNodes(@PathVariable("cluster") String cluster) {
        return DockerUtils.listNodes(getNodesInternal(cluster));
    }

    @Secured(Authorities.ADMIN_ROLE)
    @ApiOperation("Add node to specified cluster. Node must be present in same environment wit cluster.")
    @RequestMapping(value = "/clusters/{cluster}/nodes/{node}", method = POST)
    public ResponseEntity<?> addNode(@PathVariable("cluster") String clusterId, @PathVariable("node") String node) {
        // we setup cluster
        NodesGroup cluster = discoveryStorage.getOrCreateCluster(clusterId, null);
        //and then attach node to it
        if (cluster.getFeatures().contains(NodesGroup.Feature.FORBID_NODE_ADDITION)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Cluster: " + clusterId + " does not allow addition of nodes.");
        }
        nodeRegistry.setNodeCluster(node, clusterId);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("Remove node from specified cluster. Also you can use 'all' cluster or any other - node will be correctly removed anyway.")
    @RequestMapping(value = "/clusters/{cluster}/nodes/{node}", method = DELETE)
    public ResponseEntity<?> removeNode(@PathVariable("cluster") String clusterId, @PathVariable("node") String node) {
        nodeRegistry.setNodeCluster(node, null);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @RequestMapping(value = "/clusters/{cluster}/registries", method = GET)
    public List<String> getRegistriesForCluster(@PathVariable("cluster") String cluster) {
        Collection<String> availableRegistries = registryRepository.getAvailableRegistries();
        NodesGroup nodesGroup = discoveryStorage.getCluster(cluster);
        List<String> registries = new ArrayList<>();
        ExtendedAssert.notFound(nodesGroup, "Cluster was not found by " + cluster);
        if (nodesGroup.getConfig() instanceof SwarmNodesGroupConfig) {
            SwarmNodesGroupConfig swarmNodesGroupConfig = (SwarmNodesGroupConfig) nodesGroup.getConfig();
            registries.addAll(swarmNodesGroupConfig.getConfig().getRegistries());
        }
        registries.retainAll(availableRegistries);
        return registries;
    }

    @RequestMapping(value = "/clusters/{cluster}/source", method = GET, produces = YamlUtils.MIME_TYPE_VALUE)
    public ResponseEntity<RootSource> getClusterSource(@PathVariable("cluster") String cluster) {
        RootSource root = sourceService.getClusterSource(cluster);
        ExtendedAssert.notFound(root, "Can not find cluster with name: " + cluster);
        HttpHeaders headers = new HttpHeaders();
        String confName = com.codeabovelab.dm.common.utils.StringUtils.retainForFileName(cluster);
        if (confName.isEmpty()) {
            confName = "config";
        }
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + confName + ".json\"");
        return new ResponseEntity<>(root, headers, HttpStatus.OK);
    }

    @Secured(Authorities.ADMIN_ROLE)
    @RequestMapping(value = "/clusters/{cluster}/source", method = POST, consumes = YamlUtils.MIME_TYPE_VALUE)
    public UiJob setClusterSource(@PathVariable("cluster") String cluster,
                                  DeployOptions.Builder options,
                                  @RequestBody RootSource rootSource) {
        return setRootSrc(cluster, options, rootSource);
    }

    @Secured(Authorities.ADMIN_ROLE)
    @RequestMapping(value = "/clusters/{cluster}/source-upload", method = POST, consumes = MimeTypeUtils.MULTIPART_FORM_DATA_VALUE)
    public UiJob uploadClusterSource(@PathVariable("cluster") String cluster,
                                  DeployOptions.Builder options,
                                  @RequestPart("file") RootSource rootSource) {
        return setRootSrc(cluster, options, rootSource);
    }

    private UiJob setRootSrc(String cluster, DeployOptions.Builder options, RootSource rootSource) {
        List<ClusterSource> clusters = rootSource.getClusters();
        if (clusters.isEmpty()) {
            throw new IllegalArgumentException("No clusters in source");
        }
        if (clusters.size() > 1) {
            throw new IllegalArgumentException("Too many clusters in source, accept only one.");
        }
        // update name of cluster, because name from path more priority than from source
        clusters.get(0).setName(cluster);
        JobInstance jobInstance = sourceService.setRootSource(rootSource, options.build());
        return UiJob.toUi(jobInstance);
    }

    @Secured({Authorities.ADMIN_ROLE, SecuredType.CLUSTER_ADMIN})
    @RequestMapping(value = "/clusters/{cluster}", method = DELETE)
    public void deleteCluster(@PathVariable("cluster") String cluster) {
        discoveryStorage.deleteCluster(cluster);
    }

    /**
     * @param name
     * @param data
     */
    @Secured({Authorities.ADMIN_ROLE, SecuredType.CLUSTER_ADMIN})
    @RequestMapping(value = "/clusters/{cluster}", method = PUT)
    public void createCluster(@PathVariable("cluster") String name, @RequestBody(required = false) UiClusterEditablePart data) {
        log.info("about to create cluster: [{}], {}", name, data);
        AtomicBoolean flag = new AtomicBoolean(false);// we can not use primitives in closure
        NodesGroup cluster = discoveryStorage.getOrCreateCluster(name, (ccc) -> {
            String type = null;
            if (data != null) {
                type = data.getType();
            }
            if (type == null) {
                type = NodesGroupConfig.TYPE_SWARM;
            }
            AbstractNodesGroupConfig<?> gc = ccc.createConfig(type);
            if(data != null) {
                ClusterConfigImpl.Builder config = data.getConfig();
                if(gc instanceof DockerBasedClusterConfig && config != null) {
                    ((DockerBasedClusterConfig)gc).setConfig(config.build());
                }
                data.toCluster(gc);
            }
            flag.set(true);
            return gc;
        });
        if(!flag.get()) {
            throw new HttpException(HttpStatus.NOT_MODIFIED, "Cluster '" + name + "' is already exists.");
        }
        log.info("Cluster created: {}", cluster);
        cluster.flush();
    }

    @Secured({Authorities.ADMIN_ROLE, SecuredType.CLUSTER_ADMIN})
    @RequestMapping(value = "/clusters/{cluster}", method = PATCH)
    public void updateCluster(@PathVariable("cluster") String name, @RequestBody UiClusterEditablePart data) {
        log.info("Begin update cluster: [{}], {}", name, data);
        NodesGroup cluster = discoveryStorage.getCluster(name);
        ExtendedAssert.notFound(cluster, "can not find cluster: " + name);
        cluster.updateConfig((cc) -> {
            if(cc instanceof DockerBasedClusterConfig) {
                DockerBasedClusterConfig dbcc = (DockerBasedClusterConfig) cc;
                ClusterConfigImpl.Builder ccib = ClusterConfigImpl.builder(dbcc.getConfig());
                ccib.merge(data.getConfig());
                // update config
                dbcc.setConfig(ccib.build());
            }
            data.toCluster(cc);
        });
        cluster.flush();
        log.info("Cluster updated: {}", cluster);
    }
}
