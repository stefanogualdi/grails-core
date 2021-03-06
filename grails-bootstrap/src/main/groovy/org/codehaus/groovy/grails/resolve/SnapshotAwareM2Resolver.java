/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.codehaus.groovy.grails.resolve;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.ivy.core.IvyPatternHelper;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DefaultArtifact;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.resolve.ResolveData;
import org.apache.ivy.plugins.repository.Resource;
import org.apache.ivy.plugins.resolver.IBiblioResolver;
import org.apache.ivy.plugins.resolver.util.ResolvedResource;
import org.apache.ivy.util.ContextualSAXHandler;
import org.apache.ivy.util.Message;
import org.apache.ivy.util.XMLHelper;
import org.xml.sax.SAXException;

/**
 * Fixes the broken snapshot support in IBiblioResolver.
 */
public class SnapshotAwareM2Resolver extends IBiblioResolver {

    private static final String M2_PER_MODULE_PATTERN = "[revision]/[artifact]-[revision](-[classifier]).[ext]";
    private static final String M2_PATTERN = "[organisation]/[module]/" + M2_PER_MODULE_PATTERN;

    public SnapshotAwareM2Resolver() {
        setM2compatible(true);
        setAlwaysCheckExactRevision(true);
    }

    @Override
    public ResolvedResource findIvyFileRef(DependencyDescriptor dd, ResolveData data) {
        if (isM2compatible() && isUsepoms()) {
            ModuleRevisionId mrid = dd.getDependencyRevisionId();
            mrid = convertM2IdForResourceSearch(mrid);

            ResolvedResource rres = null;
            if (dd.getDependencyRevisionId().getRevision().endsWith("SNAPSHOT")) {
                rres = findSnapshotDescriptor(dd, data, mrid);
                if (rres != null) {
                    return rres;
                }
            }

            rres = findResourceUsingPatterns(mrid, getIvyPatterns(),
                DefaultArtifact.newPomArtifact(mrid, data.getDate()), getRMDParser(dd, data), data
                        .getDate());
            return rres;
        }
        return null;
    }

    @Override
    public ResolvedResource findArtifactRef(Artifact artifact, Date date) {
        ensureConfigured(getSettings());
        ModuleRevisionId mrid = artifact.getModuleRevisionId();
        if (isM2compatible()) {
            mrid = convertM2IdForResourceSearch(mrid);
        }
        ResolvedResource rres = null;
        if (artifact.getId().getRevision().endsWith("SNAPSHOT") && isM2compatible()) {
            rres = findSnapshotArtifact(artifact, date, mrid);
            if (rres != null) {
                return rres;
            }
        }
        return findResourceUsingPatterns(mrid, getArtifactPatterns(), artifact,
            getDefaultRMDParser(artifact.getModuleRevisionId().getModuleId()), date);
    }

    private ResolvedResource findSnapshotArtifact(Artifact artifact, Date date,
            ModuleRevisionId mrid) {
        SnapshotRevision rev = findSnapshotRevision(mrid);
        if (rev != null) {
            // replace the revision token in file name with the resolved revision
            String pattern = getWholePattern().replaceFirst("\\-\\[revision\\]", "-" + rev.uniqueRevision);
            ResolvedResource uniqueResource = findResourceUsingPattern(mrid, pattern, artifact,
                getDefaultRMDParser(artifact.getModuleRevisionId().getModuleId()), date);

            if (uniqueResource != null) {
                return new LastModifiedResolvedResource(uniqueResource.getResource(), rev.revision, rev.lastModified);
            }

            pattern = getWholePattern().replaceFirst("\\-\\[revision\\]", "-" + mrid.getRevision());
            ResolvedResource nonUnique = findResourceUsingPattern(mrid, pattern, artifact,
                getDefaultRMDParser(artifact.getModuleRevisionId().getModuleId()), date);

            if (nonUnique != null) {
                return new LastModifiedResolvedResource(nonUnique.getResource(), rev.revision, rev.lastModified);
            }
        }
        return null;
    }

    private ResolvedResource findSnapshotDescriptor(DependencyDescriptor dd, ResolveData data,
            ModuleRevisionId mrid) {
        SnapshotRevision rev = findSnapshotRevision(mrid);
        Message.verbose("[" + rev + "] " + mrid);
        if (rev != null) {
            // here it would be nice to be able to store the resolved snapshot version, to avoid
            // having to follow the same process to download artifacts

            Message.verbose("[" + rev + "] " + mrid);

            // replace the revision token in file name with the resolved revision
            String pattern = getWholePattern().replaceFirst("\\-\\[revision\\]", "-" + rev);
            ResolvedResource uniqueResource = findResourceUsingPattern(mrid, pattern,
                DefaultArtifact.newPomArtifact(
                    mrid, data.getDate()), getRMDParser(dd, data), data.getDate());

            if (uniqueResource != null) {
                return new LastModifiedResolvedResource(uniqueResource.getResource(), rev.revision, rev.lastModified);
            }

            pattern = getWholePattern().replaceFirst("\\-\\[revision\\]", "-" + mrid.getRevision());
            ResolvedResource nonUnique = findResourceUsingPattern(mrid, pattern,
                DefaultArtifact.newPomArtifact(
                    mrid, data.getDate()), getRMDParser(dd, data), data.getDate());

            if (nonUnique != null) {
                return new LastModifiedResolvedResource(nonUnique.getResource(), rev.revision, rev.lastModified);
            }
        }
        return null;
    }

    private SnapshotRevision findSnapshotRevision(ModuleRevisionId mrid) {
        if (!isM2compatible()) {
            return null;
        }

        if (shouldUseMavenMetadata()) {
            InputStream metadataStream = null;
            try {
                String metadataLocation = IvyPatternHelper.substitute(
                    getRoot() + "[organisation]/[module]/[revision]/maven-metadata.xml", mrid);
                Resource metadata = getRepository().getResource(metadataLocation);
                if (metadata.exists()) {
                    metadataStream = metadata.openStream();
                    final StringBuilder timestamp = new StringBuilder();
                    final StringBuilder buildNumber = new StringBuilder();
                    final StringBuilder lastUpdated = new StringBuilder();
                    XMLHelper.parse(metadataStream, null, new ContextualSAXHandler() {
                        @Override
                        public void endElement(String uri, String localName, String qName) throws SAXException {
                            if ("metadata/versioning/snapshot/timestamp".equals(getContext())) {
                                timestamp.append(getText());
                            } else if ("metadata/versioning/snapshot/buildNumber".equals(getContext())) {
                                buildNumber.append(getText());
                            } else if ("metadata/versioning/lastUpdated".equals(getContext())) {
                                lastUpdated.append(getText());
                            }
                            super.endElement(uri, localName, qName);
                        }
                    }, null);
                    if (timestamp.length() > 0) { // we have found a timestamp, so this is a snapshot unique version
                        String snapshotRevision = mrid.getRevision();
                        String baseRevision = snapshotRevision.substring(0, snapshotRevision.length() - "-SNAPSHOT".length());
                        String uniqueRevisionSuffix = new StringBuffer().append(timestamp).append("-").append(buildNumber).toString();

                        return new SnapshotRevision(baseRevision, uniqueRevisionSuffix, Long.parseLong(lastUpdated.toString()));
                    }
                } else {
                    Message.verbose("\tmaven-metadata not available: " + metadata);
                }
            } catch (IOException e) {
                Message.verbose(
                    "impossible to access maven metadata file, ignored: " + e.getMessage());
            } catch (SAXException e) {
                Message.verbose(
                    "impossible to parse maven metadata file, ignored: " + e.getMessage());
            } catch (ParserConfigurationException e) {
                Message.verbose(
                    "impossible to parse maven metadata file, ignored: " + e.getMessage());
            } finally {
                if (metadataStream != null) {
                    try {
                        metadataStream.close();
                    } catch (IOException e) {
                        // ignored
                    }
                }
            }
        }
        return null;
    }

    static private class SnapshotRevision {
        public final String revision;
        public final String uniqueRevision;
        public final long lastModified;

        private SnapshotRevision(String revision, String uniqueRevisionSuffix, long lastModified) {
            this.revision = revision + "-SNAPSHOT";
            this.uniqueRevision = revision + "-" + uniqueRevisionSuffix;
            this.lastModified = lastModified;
        }

        @Override
        public String toString() {
            return uniqueRevision;
        }
    }

    private String getWholePattern() {
        return getRoot() + getPattern();
    }

    private boolean shouldUseMavenMetadata() {
        return isUseMavenMetadata() && isM2compatible() && getPattern().endsWith(M2_PATTERN);
    }
}
