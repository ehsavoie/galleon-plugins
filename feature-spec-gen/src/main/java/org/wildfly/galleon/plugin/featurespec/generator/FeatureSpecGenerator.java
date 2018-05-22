/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.galleon.plugin.featurespec.generator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.galleon.ProvisioningException;

/**
 *
 * @author Alexey Loubyansky
 */
public class FeatureSpecGenerator {

    private Map<String, FeatureSpecNode> nodesBySpecName = new HashMap<>();
    private Map<String, Map<String, FeatureSpecNode>> referencedSpecs = new HashMap<>();
    private Map<String, FeatureSpecNode> capProviders = new HashMap<>();
    private int specsGenerated;

    final Path outputDir;
    private final boolean fork;
    private final boolean debug;
    private Set<String> inheritedSpecs;

    String getBranchId(String spec, int dots) {
        int i = 0;
        int index = 0;
        while(i <= dots) {
            index = spec.indexOf('.', index + 1);
            if(index < 0) {
                return spec;
            }
            ++i;
        }
        return spec.substring(0, index);
    }

    void addSpec(String name, FeatureSpecNode node) {
        nodesBySpecName.put(name, node);
    }

    FeatureSpecNode getSpec(String name) {
        return nodesBySpecName.get(name);
    }

    void addReferencedSpec(String referencedSpecName, String referencingSpec, FeatureSpecNode referencingNode) {
        Map<String, FeatureSpecNode> specs = referencedSpecs.get(referencedSpecName);
        if(specs != null) {
            if(specs.size() == 1) {
                specs = new HashMap<>(specs);
                referencedSpecs.put(referencedSpecName, specs);
            }
            specs.put(referencingSpec, referencingNode);
        } else {
            referencedSpecs.put(referencedSpecName, Collections.singletonMap(referencingSpec, referencingNode));
        }
    }

    Map<String, FeatureSpecNode> getReferencingSpecs(String referencedSpecName) {
        final Map<String, FeatureSpecNode> refs = referencedSpecs.get(referencedSpecName);
        return refs == null ? Collections.emptyMap() : refs;
    }

    void clearReferencingSpecs(String referencedSpecName) {
        referencedSpecs.remove(referencedSpecName);
    }

    void addCapProvider(String cap, FeatureSpecNode spec) {
        capProviders.put(cap, spec);
    }

    FeatureSpecNode getCapProvider(String cap) {
        return capProviders.get(cap);
    }

    boolean isInherited(String name) {
        return inheritedSpecs.contains(name);
    }

    void increaseSpecCount() {
        ++specsGenerated;
    }

    public FeatureSpecGenerator(Path outputDir, Set<String> inheritedSpecs, boolean fork, boolean debug) {
        this.outputDir = outputDir;
        this.fork = fork;
        this.debug = debug;
        this.inheritedSpecs = inheritedSpecs;
    }

    public int generateSpecs(Path installationHome) throws ProvisioningException {
        final Map<Object, Object> originalProps = new HashMap<>(System.getProperties());
        try {
            doGenerate(installationHome.toString());
        } finally {
            final List<String> toClear = new ArrayList<>();
            for(Map.Entry<Object, Object> prop : System.getProperties().entrySet()) {
                final Object value = originalProps.get(prop.getKey());
                if (value != null) {
                    System.setProperty(prop.getKey().toString(), value.toString());
                } else {
                    toClear.add(prop.getKey().toString());
                }
            }
            for(String prop : toClear) {
                System.clearProperty(prop);
            }
        }
        return specsGenerated;
    }

    private void doGenerate(String installationHome) throws ProvisioningException {

        final ModelNode standaloneFeatures = FeatureSpecDescriptionReader.readStandalone(installationHome, fork);
        final FeatureSpecNode rootNode = new FeatureSpecNode(this, FeatureSpecNode.STANDALONE_MODEL, standaloneFeatures.require("name").asString(), standaloneFeatures);

        final ModelNode domainRoots = FeatureSpecDescriptionReader.readDomain(installationHome, fork);
        rootNode.setDomainDescr("domain", new ModelNode());
        rootNode.generateDomain = false;
        for(Property child : domainRoots.get("children").asPropertyList()) {
            final String specName = child.getName();
            if(specName.equals("host")) {
                rootNode.setHostDescr(specName, child.getValue());
            } else if(specName.equals("profile")) {
                rootNode.setProfileDescr(specName, child.getValue());
            } else {
                rootNode.domainDescr.get("children").add(specName, child.getValue());
            }
        }

        rootNode.processChildren(FeatureSpecNode.STANDALONE_MODEL);
        rootNode.processChildren(FeatureSpecNode.PROFILE_MODEL);
        rootNode.processChildren(FeatureSpecNode.DOMAIN_MODEL);
        rootNode.processChildren(FeatureSpecNode.HOST_MODEL);

        rootNode.buildSpecs();
    }

   void warn(String str) {
       System.out.println("WARN: " + str);
   }

   void debug(String str, Object... args) {
       if(debug) {
           System.out.println(String.format(str, args));
       }
   }
}
