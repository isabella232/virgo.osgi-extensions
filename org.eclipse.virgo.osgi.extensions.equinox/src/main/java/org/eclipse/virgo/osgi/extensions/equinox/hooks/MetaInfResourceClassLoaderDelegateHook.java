/*******************************************************************************
 * Copyright (c) 2008, 2010 VMware Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   VMware Inc. - initial contribution
 *******************************************************************************/

package org.eclipse.virgo.osgi.extensions.equinox.hooks;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.osgi.framework.adaptor.BundleClassLoader;
import org.eclipse.osgi.framework.adaptor.BundleData;
import org.eclipse.osgi.framework.adaptor.ClassLoaderDelegateHook;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.service.packageadmin.ExportedPackage;
import org.osgi.service.packageadmin.PackageAdmin;

/**
 * A {@link ClassLoaderDelegateHook} which in {@link #postFindResource} and {@link #postFindResources} propagates the
 * attempt to get <code>META-INF</code> resource(s) to the principle bundle's dependencies, unless the request is being
 * driven through Spring DM's DelgatedNamespaceHandlerResolver.
 * 
 * <p />
 * 
 * The list of a bundle's dependencies are cached to avoid determining the dependencies every time. A bundle's entry
 * in the cached is cleared whenever an <code>UNRESOLVED</code> event is received for the bundle. <code>UNRESOLVED</code>
 * events are fired both during uninstall and during {@link PackageAdmin#refreshPackages(Bundle[]) refreshPackages}
 * processing.
 * 
 * <strong>Concurrent Semantics</strong><br />
 * 
 * Thread-safe.
 * 
 */
public class MetaInfResourceClassLoaderDelegateHook implements ClassLoaderDelegateHook {

    private static final String DELEGATED_NAMESPACE_HANDLER_RESOLVER_CLASS_NAME = "org.springframework.osgi.context.support.DelegatedNamespaceHandlerResolver";
    
    private static final String DELEGATED_ENTITY_RESOLVER_CLASS_NAME = "org.springframework.osgi.context.support.DelegatedEntityResolver";

    private static final String EXCLUDED_RESOURCE_MANIFEST = "MANIFEST.MF";

    private static final String EXCLUDED_RESOURCE_SPRING_DIR = "spring";

    private static final String EXCLUDED_RESOURCE_SPRING_DIR_SUFFIX = ".xml";

    private final BundleContext systemBundleContext;

    private final PackageAdmin packageAdmin;

    private final ThreadLocal<Object> resourceSearchInProgress = new ThreadLocal<Object>();

    private final Object SEARCH_IN_PROGRESS_MARKER = new Object();

    private final Object monitor = new Object();

    private final WeakHashMap<Bundle, Set<Bundle>> dependenciesCache = new WeakHashMap<Bundle, Set<Bundle>>();

    private final BundleListener cacheClearingBundleListener = new CacheClearingBundleListener();

    /**
     * Create a new hook that will use the supplied <code>systemBundleContext</code> to lookup bundles, and the supplied
     * <code>packageAdmin</code> to determine a bundle's dependencies.
     * 
     * @param systemBundleContext the {@link BundleContext} of the system bundle
     * @param packageAdmin the {@link PackageAdmin} to use to determine a bundle's dependencies
     */
    public MetaInfResourceClassLoaderDelegateHook(BundleContext systemBundleContext, PackageAdmin packageAdmin) {
        this.systemBundleContext = systemBundleContext;
        this.packageAdmin = packageAdmin;
    }

    public void init() {
        this.systemBundleContext.addBundleListener(this.cacheClearingBundleListener);
    }

    public void destroy() {
        this.systemBundleContext.removeBundleListener(this.cacheClearingBundleListener);
    }

    /**
     * {@inheritDoc}
     */
    public URL postFindResource(String name, BundleClassLoader classLoader, BundleData data) throws FileNotFoundException {
        if (isDelegatedResource(name)) {
            if (this.resourceSearchInProgress.get() == null) {
                try {
                    this.resourceSearchInProgress.set(SEARCH_IN_PROGRESS_MARKER);

                    Bundle[] bundles = getDependencyBundles(classLoader.getBundle());
                    for (Bundle dependency : bundles) {
                    	try {
                    		int state = dependency.getState();
							if (state == Bundle.ACTIVE || state == Bundle.RESOLVED) {
		                        URL resource = dependency.getResource(name);
		                        if (resource != null) {
		                            return resource;
		                        }
                    		} else {
                    			removeDependency(classLoader.getBundle(), dependency);
                    		}
                    	} catch (IllegalStateException _) {
                        	// Dependency now UNINSTALLED
                    		removeDependency(classLoader.getBundle(), dependency);
                    	}
                    }
                } finally {
                    this.resourceSearchInProgress.set(null);
                }
            }
        }
        return null;

    }

    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("unchecked")
    public Enumeration<?> postFindResources(String name, BundleClassLoader classLoader, BundleData data) throws FileNotFoundException {
        if (isDelegatedResource(name)) {
            if (this.resourceSearchInProgress.get() == null) {
                try {
                    this.resourceSearchInProgress.set(SEARCH_IN_PROGRESS_MARKER);

                    Set<URL> found = new HashSet<URL>();
                    Bundle[] bundles = getDependencyBundles(classLoader.getBundle());
                    for (Bundle dependency : bundles) {
                        try {
                        	int state = dependency.getState();
							if (state == Bundle.RESOLVED || state == Bundle.ACTIVE) {
                        		addAll(found, dependency.getResources(name));
                        	} else {
                        		removeDependency(classLoader.getBundle(), dependency);
                        	}
                        } catch (IOException _) {
                        } catch (IllegalStateException _) {
                        	// Dependency now UNINSTALLED
                        	removeDependency(classLoader.getBundle(), dependency);
                        }
                    }

                    if (!found.isEmpty()) {
                        return new IteratorEnumerationAdaptor<URL>(found.iterator());
                    }
                } finally {
                    this.resourceSearchInProgress.set(null);
                }
            }
        }

        return null;
    }

    private boolean isDelegatedResource(String name) {
        return isMetaInfResource(name) && !isSpringDmDelegatedResolverCall();
    }

    /**
     * Queries whether or not the supplied resource name is a META-INF resource.
     * 
     * @param name the resource name.
     * @return <code>true</code> if the resource is a META-INF resource.
     */
    private boolean isMetaInfResource(String name) {
        if (!name.startsWith("/META-INF") && !name.startsWith("META-INF")) {
            return false;
        }
        if (name.contains(EXCLUDED_RESOURCE_MANIFEST)) {
            return false;
        }
        if (name.contains(EXCLUDED_RESOURCE_SPRING_DIR) && name.endsWith(EXCLUDED_RESOURCE_SPRING_DIR_SUFFIX)) {
            return false;
        }
        return true;
    }

    private boolean isSpringDmDelegatedResolverCall() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            String className = element.getClassName();
            if (DELEGATED_NAMESPACE_HANDLER_RESOLVER_CLASS_NAME.equals(className) || DELEGATED_ENTITY_RESOLVER_CLASS_NAME.equals(className)) {
                return true;
            }
        }
        return false;
    }        

    private void addAll(Collection<URL> target, Enumeration<URL> source) {
        while (source != null && source.hasMoreElements()) {
            target.add(source.nextElement());
        }
    }

    private Bundle[] getDependencyBundles(Bundle bundle) {
        synchronized (this.monitor) {
            Set<Bundle> dependencies = this.dependenciesCache.get(bundle);
            if (dependencies != null) {
                return dependencies.toArray(new Bundle[dependencies.size()]);
            }
        }

        Set<Bundle> dependencies = determineDependencies(bundle);
        synchronized (this.monitor) {
            this.dependenciesCache.put(bundle, dependencies);
            return dependencies.toArray(new Bundle[dependencies.size()]);
        }
    }
    
    private void removeDependency(Bundle bundle, Bundle dependency) {
    	synchronized (this.monitor) {
    		Set<Bundle> dependencies = this.dependenciesCache.get(bundle);
    		if (dependencies != null) {
    			dependencies.remove(dependency);
    		}
    	}
    }

    private Set<Bundle> determineDependencies(Bundle bundle) {
        Set<Bundle> bundles = new HashSet<Bundle>();
        for (Bundle candidate : this.systemBundleContext.getBundles()) {
            if (!candidate.equals(bundle)) {
                ExportedPackage[] exportedPackages = getExportedPackages(candidate);
                if (exportedPackages != null) {
                    for (ExportedPackage exportedPackage : exportedPackages) {
                        Bundle[] importingBundles = exportedPackage.getImportingBundles();
                        if (importingBundles != null) {
                            for (Bundle importer : importingBundles) {
                                if (importer.equals(bundle)) {
                                    bundles.add(candidate);
                                }
                            }
                        }
                    }
                }
            }
        }
        return bundles;
    }

    protected ExportedPackage[] getExportedPackages(Bundle bundle) {
        return this.packageAdmin.getExportedPackages(bundle);
    }

    private static class IteratorEnumerationAdaptor<T> implements Enumeration<T> {

        private final Iterator<T> iterator;

        private IteratorEnumerationAdaptor(Iterator<T> iterator) {
            this.iterator = iterator;
        }

        /**
         * {@inheritDoc}
         */
        public boolean hasMoreElements() {
            return this.iterator.hasNext();
        }

        /**
         * {@inheritDoc}
         */
        public T nextElement() {
            return this.iterator.next();
        }
    }

    private final class CacheClearingBundleListener implements BundleListener {

        /**
         * {@inheritDoc}
         */
        public void bundleChanged(BundleEvent event) {
            if (BundleEvent.UNRESOLVED == event.getType()) {
                synchronized (monitor) {
                    dependenciesCache.remove(event.getBundle());
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public Class<?> postFindClass(String name, BundleClassLoader classLoader, BundleData data) throws ClassNotFoundException {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public String postFindLibrary(String name, BundleClassLoader classLoader, BundleData data) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public Class<?> preFindClass(String name, BundleClassLoader classLoader, BundleData data) throws ClassNotFoundException {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public String preFindLibrary(String name, BundleClassLoader classLoader, BundleData data) throws FileNotFoundException {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public URL preFindResource(String name, BundleClassLoader classLoader, BundleData data) throws FileNotFoundException {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public Enumeration<?> preFindResources(String name, BundleClassLoader classLoader, BundleData data) throws FileNotFoundException {
        return null;
    }
}
