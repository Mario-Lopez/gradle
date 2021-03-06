/*
 * Copyright 2010 the original author or authors.
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
package org.gradle.api.internal.file.copy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NonExtensible;
import org.gradle.api.Transformer;
import org.gradle.api.file.CopyProcessingSpec;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.ChainingTransformer;
import org.gradle.api.internal.ClosureBackedAction;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.pattern.PatternMatcherFactory;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.typeconversion.NotationParser;
import org.gradle.util.ConfigureUtil;
import org.gradle.util.DeprecationLogger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FilterReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@NonExtensible
public class DefaultCopySpec implements CopySpecInternal {
    private static final NotationParser<Object, String> PATH_NOTATION_PARSER = PathNotationConverter.parser();
    protected final FileResolver fileResolver;
    private final Set<Object> sourcePaths = new LinkedHashSet<Object>();
    private Object destDir;
    private final PatternSet patternSet;
    private final List<CopySpecInternal> childSpecs = new LinkedList<CopySpecInternal>();
    private final List<CopySpecInternal> childSpecsInAdditionOrder = new LinkedList<CopySpecInternal>();
    protected final Instantiator instantiator;
    private final List<Action<? super FileCopyDetails>> copyActions = new LinkedList<Action<? super FileCopyDetails>>();
    private boolean hasCustomActions;
    private Integer dirMode;
    private Integer fileMode;
    private Boolean caseSensitive;
    private Boolean includeEmptyDirs;
    private DuplicatesStrategy duplicatesStrategy;
    private String filteringCharset;
    private final List<CopySpecListener> listeners = Lists.newLinkedList();

    public DefaultCopySpec(FileResolver resolver, Instantiator instantiator) {
        this.fileResolver = resolver;
        this.instantiator = instantiator;
        PatternSet patternSet = resolver.getPatternSetFactory().create();
        assert patternSet != null;
        this.patternSet = patternSet;
    }

    @Override
    public boolean hasCustomActions() {
        if (hasCustomActions) {
            return true;
        }
        for (CopySpecInternal childSpec : childSpecs) {
            if (childSpec.hasCustomActions()) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    List<Action<? super FileCopyDetails>> getCopyActions() {
        return copyActions;
    }

    @Override
    public CopySpec with(CopySpec... copySpecs) {
        for (CopySpec copySpec : copySpecs) {
            CopySpecInternal copySpecInternal;
            if (copySpec instanceof CopySpecSource) {
                CopySpecSource copySpecSource = (CopySpecSource) copySpec;
                copySpecInternal = copySpecSource.getRootSpec();
            } else {
                copySpecInternal = (CopySpecInternal) copySpec;
            }
            addChildSpec(copySpecInternal);
        }
        return this;
    }

    @Override
    public CopySpec from(Object... sourcePaths) {
        Collections.addAll(this.sourcePaths, sourcePaths);
        return this;
    }

    @Override
    public CopySpec from(Object sourcePath, final Closure c) {
        return from(sourcePath, new ClosureBackedAction<CopySpec>(c));
    }

    @Override
    public CopySpec from(Object sourcePath, Action<? super CopySpec> configureAction) {
        //noinspection ConstantConditions
        if (configureAction == null) {
            DeprecationLogger.nagUserOfDeprecatedBehaviour("Gradle does not allow passing null for the configuration action for CopySpec.from().");
            from(sourcePath);
            return this;
        } else {
            CopySpecInternal child = addChild();
            child.from(sourcePath);
            CopySpecWrapper wrapper = instantiator.newInstance(CopySpecWrapper.class, child);
            configureAction.execute(wrapper);
            return wrapper;
        }
    }

    @Override
    public CopySpecInternal addFirst() {
        return addChildAtPosition(0);
    }

    protected CopySpecInternal addChildAtPosition(int position) {
        DefaultCopySpec child = instantiator.newInstance(SingleParentCopySpec.class, fileResolver, instantiator, buildRootResolver());
        addChildSpec(position, child);
        return child;
    }

    @Override
    public CopySpecInternal addChild() {
        DefaultCopySpec child = new SingleParentCopySpec(fileResolver, instantiator, buildRootResolver());
        addChildSpec(child);
        return child;
    }

    @Override
    public CopySpecInternal addChildBeforeSpec(CopySpecInternal childSpec) {
        int position = childSpecs.indexOf(childSpec);
        return position != -1 ? addChildAtPosition(position) : addChild();
    }

    protected void addChildSpec(CopySpecInternal childSpec) {
        addChildSpec(childSpecs.size(), childSpec);
    }

    protected void addChildSpec(int index, CopySpecInternal childSpec) {
        childSpecs.add(index, childSpec);

        // We need a consistent index here
        final int additionIndex = childSpecsInAdditionOrder.size();
        childSpecsInAdditionOrder.add(childSpec);

        // In case more descendants are added to downward hierarchy, make sure they'll notify us
        childSpec.addChildSpecListener(new CopySpecListener() {
            @Override
            public void childSpecAdded(CopySpecAddress path, CopySpecInternal spec) {
                CopySpecAddress childPath = new DefaultCopySpecAddress(null, DefaultCopySpec.this, additionIndex).append(path);
                fireChildSpecListeners(childPath, spec);
            }
        });

        // Notify upwards of currently existing descendant spec hierarchy
        childSpec.visit(new DefaultCopySpecAddress(null, this, additionIndex), new CopySpecVisitor() {
            @Override
            public void visit(final CopySpecAddress parentPath, CopySpecInternal spec) {
                fireChildSpecListeners(parentPath, spec);
            }
        });
    }

    private void fireChildSpecListeners(CopySpecAddress path, CopySpecInternal spec) {
        for (CopySpecListener listener : listeners) {
            listener.childSpecAdded(path, spec);
        }
    }

    @Override
    public void visit(CopySpecAddress parentPath, CopySpecVisitor visitor) {
        visitor.visit(parentPath, this);
        int childIndex = 0;
        for (CopySpecInternal childSpec : childSpecsInAdditionOrder) {
            CopySpecAddress childPath = parentPath.append(this, childIndex);
            childSpec.visit(childPath, visitor);
            childIndex++;
        }
    }

    @Override
    public void addChildSpecListener(CopySpecListener copySpecListener) {
        this.listeners.add(copySpecListener);
    }

    @VisibleForTesting
    public Set<Object> getSourcePaths() {
        return sourcePaths;
    }


    @Override
    public CopySpec into(Object destDir) {
        this.destDir = destDir;
        return this;
    }

    @Override
    public CopySpec into(Object destPath, Closure configureClosure) {
        return into(destPath, new ClosureBackedAction<CopySpec>(configureClosure));
    }

    @Override
    public CopySpec into(Object destPath, Action<? super CopySpec> copySpec) {
        //noinspection ConstantConditions
        if (copySpec == null) {
            DeprecationLogger.nagUserOfDeprecatedBehaviour("Gradle does not allow passing null for the configuration action for CopySpec.into().");
            into(destPath);
            return this;
        } else {
            CopySpecInternal child = addChild();
            child.into(destPath);
            CopySpecWrapper wrapper = instantiator.newInstance(CopySpecWrapper.class, child);
            copySpec.execute(wrapper);
            return wrapper;
        }
    }

    @Override
    public boolean isCaseSensitive() {
        return buildRootResolver().isCaseSensitive();
    }

    @Override
    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    @Override
    public boolean getIncludeEmptyDirs() {
        return buildRootResolver().getIncludeEmptyDirs();
    }

    @Override
    public void setIncludeEmptyDirs(boolean includeEmptyDirs) {
        this.includeEmptyDirs = includeEmptyDirs;
    }

    @Override
    public DuplicatesStrategy getDuplicatesStrategy() {
        return buildRootResolver().getDuplicatesStrategy();
    }

    @Override
    public void setDuplicatesStrategy(@Nullable DuplicatesStrategy strategy) {
        this.duplicatesStrategy = strategy;
    }

    @Override
    public CopySpec filesMatching(String pattern, Action<? super FileCopyDetails> action) {
        Spec<RelativePath> matcher = PatternMatcherFactory.getPatternMatcher(true, isCaseSensitive(), pattern);
        return eachFile(new MatchingCopyAction(matcher, action));
    }

    @Override
    public CopySpec filesMatching(Iterable<String> patterns, Action<? super FileCopyDetails> action) {
        if (!patterns.iterator().hasNext()) {
            throw new InvalidUserDataException("must provide at least one pattern to match");
        }
        List<Spec<RelativePath>> matchers = new ArrayList<Spec<RelativePath>>();
        for (String pattern : patterns) {
            matchers.add(PatternMatcherFactory.getPatternMatcher(true, isCaseSensitive(), pattern));
        }
        return eachFile(new MatchingCopyAction(Specs.union(matchers), action));
    }

    @Override
    public CopySpec filesNotMatching(String pattern, Action<? super FileCopyDetails> action) {
        Spec<RelativePath> matcher = PatternMatcherFactory.getPatternMatcher(true, isCaseSensitive(), pattern);
        return eachFile(new MatchingCopyAction(Specs.negate(matcher), action));
    }

    @Override
    public CopySpec filesNotMatching(Iterable<String> patterns, Action<? super FileCopyDetails> action) {
        if (!patterns.iterator().hasNext()) {
            throw new InvalidUserDataException("must provide at least one pattern to not match");
        }
        List<Spec<RelativePath>> matchers = new ArrayList<Spec<RelativePath>>();
        for (String pattern : patterns) {
            matchers.add(PatternMatcherFactory.getPatternMatcher(true, isCaseSensitive(), pattern));
        }
        return eachFile(new MatchingCopyAction(Specs.negate(Specs.union(matchers)), action));
    }

    @Override
    public CopySpec include(String... includes) {
        patternSet.include(includes);
        return this;
    }

    @Override
    public CopySpec include(Iterable<String> includes) {
        patternSet.include(includes);
        return this;
    }

    @Override
    public CopySpec include(Spec<FileTreeElement> includeSpec) {
        patternSet.include(includeSpec);
        return this;
    }

    @Override
    public CopySpec include(Closure includeSpec) {
        patternSet.include(includeSpec);
        return this;
    }

    @Override
    public Set<String> getIncludes() {
        return patternSet.getIncludes();
    }

    @Override
    public CopySpec setIncludes(Iterable<String> includes) {
        patternSet.setIncludes(includes);
        return this;
    }

    @Override
    public CopySpec exclude(String... excludes) {
        patternSet.exclude(excludes);
        return this;
    }

    @Override
    public CopySpec exclude(Iterable<String> excludes) {
        patternSet.exclude(excludes);
        return this;
    }

    @Override
    public CopySpec exclude(Spec<FileTreeElement> excludeSpec) {
        patternSet.exclude(excludeSpec);
        return this;
    }

    @Override
    public CopySpec exclude(Closure excludeSpec) {
        patternSet.exclude(excludeSpec);
        return this;
    }

    @Override
    public Set<String> getExcludes() {
        return patternSet.getExcludes();
    }

    @Override
    public CopySpec setExcludes(Iterable<String> excludes) {
        patternSet.setExcludes(excludes);
        return this;
    }

    @Override
    public CopySpec rename(String sourceRegEx, String replaceWith) {
        appendCopyAction(new RenamingCopyAction(new RegExpNameMapper(sourceRegEx, replaceWith)));
        return this;
    }

    @Override
    public CopySpec rename(Pattern sourceRegEx, String replaceWith) {
        appendCopyAction(new RenamingCopyAction(new RegExpNameMapper(sourceRegEx, replaceWith)));
        return this;
    }

    @Override
    public CopySpec filter(final Class<? extends FilterReader> filterType) {
        appendCopyAction(new Action<FileCopyDetails>() {
            public void execute(FileCopyDetails fileCopyDetails) {
                fileCopyDetails.filter(filterType);
            }
        });
        return this;
    }

    @Override
    public CopySpec filter(final Closure closure) {
        return filter(new ClosureBackedTransformer(closure));
    }

    @Override
    public CopySpec filter(final Transformer<String, String> transformer) {
        appendCopyAction(new Action<FileCopyDetails>() {
            public void execute(FileCopyDetails fileCopyDetails) {
                fileCopyDetails.filter(transformer);
            }
        });
        return this;
    }

    @Override
    public CopySpec filter(final Map<String, ?> properties, final Class<? extends FilterReader> filterType) {
        appendCopyAction(new Action<FileCopyDetails>() {
            public void execute(FileCopyDetails fileCopyDetails) {
                fileCopyDetails.filter(properties, filterType);
            }
        });
        return this;
    }

    @Override
    public CopySpec expand(final Map<String, ?> properties) {
        appendCopyAction(new Action<FileCopyDetails>() {
            public void execute(FileCopyDetails fileCopyDetails) {
                fileCopyDetails.expand(properties);
            }
        });
        return this;
    }

    @Override
    public CopySpec rename(Closure closure) {
        return rename(new ClosureBackedTransformer(closure));
    }

    @Override
    public CopySpec rename(Transformer<String, String> renamer) {
        ChainingTransformer<String> transformer = new ChainingTransformer<String>(String.class);
        transformer.add(renamer);
        appendCopyAction(new RenamingCopyAction(transformer));
        return this;
    }

    @Override
    public Integer getDirMode() {
        return buildRootResolver().getDirMode();
    }

    @Override
    public Integer getFileMode() {
        return buildRootResolver().getFileMode();
    }

    @Override
    public CopyProcessingSpec setDirMode(@Nullable Integer mode) {
        dirMode = mode;
        return this;
    }

    @Override
    public CopyProcessingSpec setFileMode(@Nullable Integer mode) {
        fileMode = mode;
        return this;
    }

    @Override
    public CopySpec eachFile(Action<? super FileCopyDetails> action) {
        appendCopyAction(action);
        return this;
    }

    private void appendCopyAction(Action<? super FileCopyDetails> action) {
        hasCustomActions = true;
        copyActions.add(action);
    }

    @Override
    public void appendCachingSafeCopyAction(Action<? super FileCopyDetails> action) {
        copyActions.add(action);
    }

    @Override
    public CopySpec eachFile(Closure closure) {
        appendCopyAction(ConfigureUtil.configureUsing(closure));
        return this;
    }

    @Override
    public Iterable<CopySpecInternal> getChildren() {
        return childSpecs;
    }

    @Override
    public void walk(Action<? super CopySpecResolver> action) {
        buildRootResolver().walk(action);
    }

    @Override
    public CopySpecResolver buildResolverRelativeToParent(CopySpecResolver parent) {
        return this.new DefaultCopySpecResolver(parent);
    }

    @Override
    public CopySpecResolver buildRootResolver() {
        //noinspection ConstantConditions
        return this.new DefaultCopySpecResolver(null);
    }

    @Override
    public String getFilteringCharset() {
        return buildRootResolver().getFilteringCharset();
    }

    @Override
    public void setFilteringCharset(String charset) {
        Preconditions.checkNotNull(charset, "filteringCharset must not be null");
        if (!Charset.isSupported(charset)) {
            throw new InvalidUserDataException(String.format("filteringCharset %s is not supported by your JVM", charset));
        }
        this.filteringCharset = charset;
    }

    public class DefaultCopySpecResolver implements CopySpecResolver {

        private CopySpecResolver parentResolver;

        // Not marked as @Nullable because of Groovy compiler bug: https://issues.apache.org/jira/browse/GROOVY-8505
        @SuppressWarnings("NullableProblems")
        private DefaultCopySpecResolver(/* @Nullable */ CopySpecResolver parent) {
            this.parentResolver = parent;
        }

        @Override
        public RelativePath getDestPath() {

            RelativePath parentPath;
            if (parentResolver == null) {
                parentPath = new RelativePath(false);
            } else {
                parentPath = parentResolver.getDestPath();
            }

            if (destDir == null) {
                return parentPath;
            }

            String path = PATH_NOTATION_PARSER.parseNotation(destDir);
            if (path.startsWith("/") || path.startsWith(File.separator)) {
                return RelativePath.parse(false, path);
            }

            return RelativePath.parse(false, parentPath, path);
        }

        @Override
        public FileTree getSource() {
            return fileResolver.resolveFilesAsTree(sourcePaths).matching(this.getPatternSet());
        }

        @Override
        public FileTree getAllSource() {
            final ImmutableList.Builder<FileTree> builder = ImmutableList.builder();
            walk(new Action<CopySpecResolver>() {
                public void execute(CopySpecResolver copySpecResolver) {
                    builder.add(copySpecResolver.getSource());
                }
            });

            return fileResolver.compositeFileTree(builder.build());
        }

        @Override
        public Collection<? extends Action<? super FileCopyDetails>> getAllCopyActions() {
            if (parentResolver == null) {
                return copyActions;
            }
            List<Action<? super FileCopyDetails>> allActions = new ArrayList<Action<? super FileCopyDetails>>();
            allActions.addAll(parentResolver.getAllCopyActions());
            allActions.addAll(copyActions);
            return allActions;
        }

        @Override
        public List<String> getAllIncludes() {
            List<String> result = new ArrayList<String>();
            if (parentResolver != null) {
                result.addAll(parentResolver.getAllIncludes());
            }
            result.addAll(patternSet.getIncludes());
            return result;
        }

        @Override
        public List<String> getAllExcludes() {
            List<String> result = new ArrayList<String>();
            if (parentResolver != null) {
                result.addAll(parentResolver.getAllExcludes());
            }
            result.addAll(patternSet.getExcludes());
            return result;
        }


        @Override
        public List<Spec<FileTreeElement>> getAllExcludeSpecs() {
            List<Spec<FileTreeElement>> result = new ArrayList<Spec<FileTreeElement>>();
            if (parentResolver != null) {
                result.addAll(parentResolver.getAllExcludeSpecs());
            }
            result.addAll(patternSet.getExcludeSpecs());
            return result;
        }

        @Override
        public DuplicatesStrategy getDuplicatesStrategy() {
            if (duplicatesStrategy != null) {
                return duplicatesStrategy;
            }
            if (parentResolver != null) {
                return parentResolver.getDuplicatesStrategy();
            }
            return DuplicatesStrategy.INCLUDE;
        }

        @Override
        public boolean isCaseSensitive() {
            if (caseSensitive != null) {
                return caseSensitive;
            }
            if (parentResolver != null) {
                return parentResolver.isCaseSensitive();
            }
            return true;
        }

        @Override
        public Integer getFileMode() {
            if (fileMode != null) {
                return fileMode;
            }
            if (parentResolver != null) {
                return parentResolver.getFileMode();
            }
            return null;
        }

        @Override
        public Integer getDirMode() {
            if (dirMode != null) {
                return dirMode;
            }
            if (parentResolver != null) {
                return parentResolver.getDirMode();
            }
            return null;
        }

        public boolean getIncludeEmptyDirs() {
            if (includeEmptyDirs != null) {
                return includeEmptyDirs;
            }
            if (parentResolver != null) {
                return parentResolver.getIncludeEmptyDirs();
            }
            return true;
        }

        public List<Spec<FileTreeElement>> getAllIncludeSpecs() {
            List<Spec<FileTreeElement>> result = new ArrayList<Spec<FileTreeElement>>();
            if (parentResolver != null) {
                result.addAll(parentResolver.getAllIncludeSpecs());
            }
            result.addAll(patternSet.getIncludeSpecs());
            return result;
        }

        public PatternSet getPatternSet() {
            PatternSet patterns = fileResolver.getPatternSetFactory().create();
            assert patterns != null;
            patterns.setCaseSensitive(isCaseSensitive());
            patterns.include(this.getAllIncludes());
            patterns.includeSpecs(getAllIncludeSpecs());
            patterns.exclude(this.getAllExcludes());
            patterns.excludeSpecs(getAllExcludeSpecs());
            return patterns;
        }

        public void walk(Action<? super CopySpecResolver> action) {
            action.execute(this);
            for (CopySpecInternal child : getChildren()) {
                child.buildResolverRelativeToParent(this).walk(action);
            }
        }

        public String getFilteringCharset() {
            if (filteringCharset != null) {
                return filteringCharset;
            }
            if (parentResolver != null) {
                return parentResolver.getFilteringCharset();
            }
            return Charset.defaultCharset().name();
        }


    }

    private class DefaultCopySpecAddress implements CopySpecAddress {
        private final DefaultCopySpecAddress parent;
        private final CopySpecInternal spec;
        private final int additionIndex;

        public DefaultCopySpecAddress(@Nullable DefaultCopySpecAddress parent, CopySpecInternal spec, int additionIndex) {
            this.parent = parent;
            this.spec = spec;
            this.additionIndex = additionIndex;
        }

        @Override
        public CopySpecAddress getParent() {
            return parent;
        }

        @Override
        public CopySpecInternal getSpec() {
            return spec;
        }

        @Override
        public int getAdditionIndex() {
            return additionIndex;
        }

        @Override
        public DefaultCopySpecAddress append(CopySpecInternal spec, int additionIndex) {
            return new DefaultCopySpecAddress(this, spec, additionIndex);
        }

        @Override
        public DefaultCopySpecAddress append(CopySpecAddress relativeAddress) {
            CopySpecAddress parent = relativeAddress.getParent();
            DefaultCopySpecAddress newParent;
            if (parent == null) {
                newParent = this;
            } else {
                newParent = append(parent);
            }
            return new DefaultCopySpecAddress(newParent, relativeAddress.getSpec(), relativeAddress.getAdditionIndex());
        }

        @Override
        public CopySpecResolver unroll(StringBuilder path) {
            CopySpecResolver resolver;
            if (parent != null) {
                resolver = spec.buildResolverRelativeToParent(parent.unroll(path));
            } else {
                resolver = spec.buildRootResolver();
            }
            path.append("$").append(additionIndex + 1);
            return resolver;
        }

        @Override
        public String toString() {
            String parentPath = parent == null
                ? ""
                : parent.toString();
            return parentPath + "$" + (additionIndex + 1);
        }
    }
}

