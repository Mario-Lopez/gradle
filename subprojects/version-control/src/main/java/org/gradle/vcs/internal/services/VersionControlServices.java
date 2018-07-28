/*
 * Copyright 2018 the original author or authors.
 *
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
 */

package org.gradle.vcs.internal.services;

import org.gradle.StartParameter;
import org.gradle.api.internal.InstantiatorFactory;
import org.gradle.api.internal.artifacts.ResolveContext;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResolverProviderFactory;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionComparator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.LocalComponentRegistry;
import org.gradle.vcs.internal.DefaultSourceControl;
import org.gradle.vcs.internal.DefaultVcsMappingFactory;
import org.gradle.vcs.internal.DefaultVcsMappings;
import org.gradle.vcs.internal.DefaultVcsMappingsStore;
import org.gradle.vcs.internal.DefaultVersionControlSystemFactory;
import org.gradle.vcs.internal.VcsMappingFactory;
import org.gradle.vcs.internal.VcsMappingsStore;
import org.gradle.vcs.internal.VcsResolver;
import org.gradle.vcs.internal.VcsWorkingDirectoryRoot;
import org.gradle.vcs.internal.VersionControlSpecFactory;
import org.gradle.vcs.internal.VersionControlSystemFactory;
import org.gradle.vcs.internal.resolver.VcsDependencyResolver;
import org.gradle.vcs.internal.resolver.VcsVersionSelectionCache;
import org.gradle.api.invocation.Gradle;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.internal.CleanupActionFactory;
import org.gradle.initialization.layout.ProjectCacheDir;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;
import org.gradle.vcs.SourceControl;
import org.gradle.vcs.VcsMappings;

import java.io.File;
import java.util.Collection;

public class VersionControlServices extends AbstractPluginServiceRegistry {
    @Override
    public void registerBuildTreeServices(ServiceRegistration registration) {
        registration.addProvider(new VersionControlBuildTreeServices());
    }

    @Override
    public void registerBuildSessionServices(ServiceRegistration registration) {
        registration.addProvider(new VersionControlBuildSessionServices());
    }

    @Override
    public void registerBuildServices(ServiceRegistration registration) {
        registration.addProvider(new VersionControlBuildServices());
    }

    @Override
    public void registerSettingsServices(ServiceRegistration registration) {
        registration.addProvider(new VersionControlSettingsServices());
    }

    private static class VersionControlBuildTreeServices {
        VcsMappingFactory createVcsMappingFactory(InstantiatorFactory instantiatorFactory, StartParameter startParameter) {
            Instantiator decoratingInstantiator = instantiatorFactory.decorate();
            return new DefaultVcsMappingFactory(decoratingInstantiator, new VersionControlSpecFactory(decoratingInstantiator, startParameter));
        }

        VcsMappingsStore createVcsMappingsStore(VcsMappingFactory mappingFactory) {
            return new DefaultVcsMappingsStore(mappingFactory);
        }

        VcsResolver createVcsResolver(VcsMappingsStore mappingsStore) {
            return mappingsStore.asResolver();
        }

        VcsVersionSelectionCache createVersionSelectionCache() {
            return new VcsVersionSelectionCache();
        }
    }

    private static class VersionControlBuildSessionServices {
        VersionControlSystemFactory createVersionControlSystemFactory(VcsWorkingDirectoryRoot vcsWorkingDirectoryRoot, CleanupActionFactory cleanupActionFactory, CacheRepository cacheRepository) {
            return new DefaultVersionControlSystemFactory(vcsWorkingDirectoryRoot, cacheRepository, cleanupActionFactory);
        }

        VcsWorkingDirectoryRoot createVcsWorkingDirectoryRoot(ProjectCacheDir projectCacheDir) {
            return new VcsWorkingDirectoryRoot(new File(projectCacheDir.getDir(), "vcsWorkingDirs"));
        }
    }

    private static class VersionControlSettingsServices {
        VcsMappings createVcsMappings(Instantiator instantiator, VcsMappingsStore vcsMappingsStore, Gradle gradle) {
            return instantiator.newInstance(DefaultVcsMappings.class, vcsMappingsStore, gradle);
        }

        SourceControl createSourceControl(Instantiator instantiator, VcsMappings vcsMappings) {
            return instantiator.newInstance(DefaultSourceControl.class, vcsMappings);
        }
    }

    private static class VersionControlBuildServices {
        VcsDependencyResolver createVcsDependencyResolver(VcsWorkingDirectoryRoot vcsWorkingDirectoryRoot, LocalComponentRegistry localComponentRegistry, VcsResolver vcsResolver, VersionControlSystemFactory versionControlSystemFactory, VersionSelectorScheme versionSelectorScheme, VersionComparator versionComparator, BuildStateRegistry buildRegistry, VersionParser versionParser, VcsVersionSelectionCache versionSelectionCache) {
            return new VcsDependencyResolver(vcsWorkingDirectoryRoot, localComponentRegistry, vcsResolver, versionControlSystemFactory, versionSelectorScheme, versionComparator, buildRegistry, versionParser, versionSelectionCache);
        }

        ResolverProviderFactory createVcsResolverProviderFactory(VcsDependencyResolver vcsDependencyResolver, VcsResolver vcsResolver) {
            return new VcsResolverFactory(vcsDependencyResolver, vcsResolver);
        }
    }

    private static class VcsResolverFactory implements ResolverProviderFactory {
        private final VcsDependencyResolver vcsDependencyResolver;
        private final VcsResolver vcsResolver;

        private VcsResolverFactory(VcsDependencyResolver vcsDependencyResolver, VcsResolver vcsResolver) {
            this.vcsDependencyResolver = vcsDependencyResolver;
            this.vcsResolver = vcsResolver;
        }

        @Override
        public void create(ResolveContext context, Collection<ComponentResolvers> resolvers) {
            if (vcsResolver.hasRules()) {
                resolvers.add(vcsDependencyResolver);
            }
        }
    }
}
