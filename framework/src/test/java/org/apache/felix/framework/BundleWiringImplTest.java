/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.framework;

import org.apache.felix.framework.BundleWiringImpl.BundleClassLoader;
import org.apache.felix.framework.cache.Content;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.hooks.weaving.WeavingException;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.hooks.weaving.WovenClassListener;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BundleWiringImplTest
{

    private BundleWiringImpl bundleWiring;

    private StatefulResolver mockResolver;

    private BundleRevisionImpl mockRevisionImpl;

    private BundleImpl mockBundle;

    @SuppressWarnings("rawtypes")
    public void initializeSimpleBundleWiring() throws Exception
    {

        mockResolver = mock(StatefulResolver.class);
        mockRevisionImpl = mock(BundleRevisionImpl.class);
        mockBundle = mock(BundleImpl.class);

        Logger logger = new Logger();
        Map configMap = new HashMap();
        List<BundleRevision> fragments = new ArrayList<>();
        List<BundleWire> wires = new ArrayList<>();
        Map<String, BundleRevision> importedPkgs = new HashMap<>();
        Map<String, List<BundleRevision>> requiredPkgs = new HashMap<>();

        when(mockRevisionImpl.getBundle()).thenReturn(mockBundle);
        when(mockBundle.getBundleId()).thenReturn(Long.valueOf(1));

        bundleWiring = new BundleWiringImpl(logger, configMap, mockResolver,
                mockRevisionImpl, fragments, wires, importedPkgs, requiredPkgs);
    }

    @Test
    public void testBundleClassLoader() throws Exception
    {
        bundleWiring = mock(BundleWiringImpl.class);
        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void testFindClassNonExistant() throws Exception
    {
        initializeSimpleBundleWiring();

        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);
        Class foundClass = null;
        try
        {
            foundClass = bundleClassLoader
                    .findClass("org.apache.felix.test.NonExistant");
        } catch (ClassNotFoundException e)
        {
            fail("Class should not throw exception");
        }
        assertNull("Nonexistant Class Should be null", foundClass);
    }

    @SuppressWarnings("rawtypes")
    @Test
    public void testFindClassExistant() throws Exception
    {
        Felix mockFramework = mock(Felix.class);
        HookRegistry hReg = mock(HookRegistry.class);
        Mockito.when(mockFramework.getHookRegistry()).thenReturn(hReg);
        Content mockContent = mock(Content.class);
        Class testClass = TestClass.class;
        String testClassName = testClass.getName();
        String testClassAsPath = testClassName.replace('.', '/') + ".class";
        byte[] testClassBytes = createTestClassBytes(testClass, testClassAsPath);

        List<Content> contentPath = new ArrayList<>();
        contentPath.add(mockContent);
        initializeSimpleBundleWiring();

        when(mockBundle.getFramework()).thenReturn(mockFramework);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        when(mockRevisionImpl.getContentPath()).thenReturn(contentPath);
        when(mockContent.getEntryAsBytes(testClassAsPath)).thenReturn(
                testClassBytes);

        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);
        Class foundClass = null;
        try
        {

            foundClass = bundleClassLoader.findClass(TestClass.class.getName());
        } catch (ClassNotFoundException e)
        {
            fail("Class should not throw exception");
        }
        assertNotNull("Class Should be found in this classloader", foundClass);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Test
    public void testFindClassWeave() throws Exception
    {
        Felix mockFramework = mock(Felix.class);
        Content mockContent = mock(Content.class);
        ServiceReference<WeavingHook> mockServiceReferenceWeavingHook = mock(ServiceReference.class);
        ServiceReference<WovenClassListener> mockServiceReferenceWovenClassListener = mock(ServiceReference.class);

        Set<ServiceReference<WeavingHook>> hooks = new HashSet<>();
        hooks.add(mockServiceReferenceWeavingHook);

        DummyWovenClassListener dummyWovenClassListener = new DummyWovenClassListener();

        Set<ServiceReference<WovenClassListener>> listeners = new HashSet<>();
        listeners.add(mockServiceReferenceWovenClassListener);

        Class testClass = TestClass.class;
        String testClassName = testClass.getName();
        String testClassAsPath = testClassName.replace('.', '/') + ".class";
        byte[] testClassBytes = createTestClassBytes(testClass, testClassAsPath);

        List<Content> contentPath = new ArrayList<>();
        contentPath.add(mockContent);
        initializeSimpleBundleWiring();

        when(mockBundle.getFramework()).thenReturn(mockFramework);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        when(mockRevisionImpl.getContentPath()).thenReturn(contentPath);
        when(mockContent.getEntryAsBytes(testClassAsPath)).thenReturn(
                testClassBytes);

        HookRegistry hReg = mock(HookRegistry.class);
        when(hReg.getHooks(WeavingHook.class)).thenReturn(hooks);
        when(mockFramework.getHookRegistry()).thenReturn(hReg);
        when(
                mockFramework.getService(mockFramework,
                        mockServiceReferenceWeavingHook, false)).thenReturn(
                                new GoodDummyWovenHook());

        when(hReg.getHooks(WovenClassListener.class)).thenReturn(
                listeners);
        when(
                mockFramework.getService(mockFramework,
                        mockServiceReferenceWovenClassListener, false))
        .thenReturn(dummyWovenClassListener);

        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);
        Class foundClass = null;
        try
        {

            foundClass = bundleClassLoader.findClass(TestClass.class.getName());
        } catch (ClassNotFoundException e)
        {
            fail("Class should not throw exception");
        }
        assertNotNull("Class Should be found in this classloader", foundClass);
        assertEquals("Weaving should have added a field", 1,
                foundClass.getFields().length);
        assertEquals("There should be 2 state changes fired by the weaving", 2,
                dummyWovenClassListener.stateList.size());
        assertEquals("The first state change should transform the class",
                (Object)WovenClass.TRANSFORMED,
                dummyWovenClassListener.stateList.get(0));
        assertEquals("The second state change should define the class",
                (Object)WovenClass.DEFINED, dummyWovenClassListener.stateList.get(1));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Test
    public void testFindClassBadWeave() throws Exception
    {
        Felix mockFramework = mock(Felix.class);
        Content mockContent = mock(Content.class);
        ServiceReference<WeavingHook> mockServiceReferenceWeavingHook = mock(ServiceReference.class);
        ServiceReference<WovenClassListener> mockServiceReferenceWovenClassListener = mock(ServiceReference.class);

        Set<ServiceReference<WeavingHook>> hooks = new HashSet<>();
        hooks.add(mockServiceReferenceWeavingHook);

        DummyWovenClassListener dummyWovenClassListener = new DummyWovenClassListener();

        Set<ServiceReference<WovenClassListener>> listeners = new HashSet<>();
        listeners.add(mockServiceReferenceWovenClassListener);

        Class testClass = TestClass.class;
        String testClassName = testClass.getName();
        String testClassAsPath = testClassName.replace('.', '/') + ".class";
        byte[] testClassBytes = createTestClassBytes(testClass, testClassAsPath);

        List<Content> contentPath = new ArrayList<>();
        contentPath.add(mockContent);
        initializeSimpleBundleWiring();

        when(mockBundle.getFramework()).thenReturn(mockFramework);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        when(mockRevisionImpl.getContentPath()).thenReturn(contentPath);
        when(mockContent.getEntryAsBytes(testClassAsPath)).thenReturn(
                testClassBytes);

        HookRegistry hReg = mock(HookRegistry.class);
        when(hReg.getHooks(WeavingHook.class)).thenReturn(hooks);
        when(mockFramework.getHookRegistry()).thenReturn(hReg);
        when(
                mockFramework.getService(mockFramework,
                        mockServiceReferenceWeavingHook, false)).thenReturn(
                                new BadDummyWovenHook());

        when(hReg.getHooks(WovenClassListener.class)).thenReturn(
                listeners);
        when(
                mockFramework.getService(mockFramework,
                        mockServiceReferenceWovenClassListener, false))
        .thenReturn(dummyWovenClassListener);

        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);

        try
        {

            bundleClassLoader.findClass(TestClass.class.getName());
            fail("Class should throw exception");
        } catch (Error e)
        {
            // This is expected
        }

        assertEquals("There should be 1 state changes fired by the weaving", 1,
                dummyWovenClassListener.stateList.size());
        assertEquals(
                "The only state change should be a failed transform on the class",
                (Object)WovenClass.TRANSFORMING_FAILED,
                dummyWovenClassListener.stateList.get(0));

    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Test
    public void testFindClassWeaveDefineError() throws Exception
    {
        Felix mockFramework = mock(Felix.class);
        Content mockContent = mock(Content.class);
        ServiceReference<WeavingHook> mockServiceReferenceWeavingHook = mock(ServiceReference.class);
        ServiceReference<WovenClassListener> mockServiceReferenceWovenClassListener = mock(ServiceReference.class);

        Set<ServiceReference<WeavingHook>> hooks = new HashSet<>();
        hooks.add(mockServiceReferenceWeavingHook);

        DummyWovenClassListener dummyWovenClassListener = new DummyWovenClassListener();

        Set<ServiceReference<WovenClassListener>> listeners = new HashSet<>();
        listeners.add(mockServiceReferenceWovenClassListener);

        Class testClass = TestClass.class;
        String testClassName = testClass.getName();
        String testClassAsPath = testClassName.replace('.', '/') + ".class";
        byte[] testClassBytes = createTestClassBytes(testClass, testClassAsPath);

        List<Content> contentPath = new ArrayList<>();
        contentPath.add(mockContent);
        initializeSimpleBundleWiring();

        when(mockBundle.getFramework()).thenReturn(mockFramework);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        when(mockRevisionImpl.getContentPath()).thenReturn(contentPath);
        when(mockContent.getEntryAsBytes(testClassAsPath)).thenReturn(
                testClassBytes);

        HookRegistry hReg = mock(HookRegistry.class);
        when(hReg.getHooks(WeavingHook.class)).thenReturn(hooks);
        when(mockFramework.getHookRegistry()).thenReturn(hReg);
        when(
                mockFramework.getService(mockFramework,
                        mockServiceReferenceWeavingHook, false)).thenReturn(
                                new BadDefineWovenHook());

        when(hReg.getHooks(WovenClassListener.class)).thenReturn(
                listeners);
        when(
                mockFramework.getService(mockFramework,
                        mockServiceReferenceWovenClassListener, false))
        .thenReturn(dummyWovenClassListener);

        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);
        try
        {

            bundleClassLoader.findClass(TestClass.class.getName());
            fail("Class should throw exception");
        } catch (Throwable e)
        {

        }
        assertEquals("There should be 2 state changes fired by the weaving", 2,
                dummyWovenClassListener.stateList.size());
        assertEquals("The first state change should transform the class",
                (Object)WovenClass.TRANSFORMED,
                dummyWovenClassListener.stateList.get(0));
        assertEquals("The second state change failed the define on the class",
                (Object)WovenClass.DEFINE_FAILED,
                dummyWovenClassListener.stateList.get(1));
    }

    private ConcurrentHashMap<String, ClassLoader> getAccessorCache(BundleWiringImpl wiring) throws NoSuchFieldException, IllegalAccessException {
        Field m_accessorLookupCache = BundleWiringImpl.class.getDeclaredField("m_accessorLookupCache");
        m_accessorLookupCache.setAccessible(true);
        return (ConcurrentHashMap<String, ClassLoader>) m_accessorLookupCache.get(wiring);
    }

    @Test
    public void testFirstGeneratedAccessorSkipClassloading() throws Exception
    {

        String classToBeLoaded = "sun.reflect.GeneratedMethodAccessor21";

        Felix mockFramework = mock(Felix.class);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        initializeSimpleBundleWiring();

        when(bundleWiring.getBundle().getFramework()).thenReturn(mockFramework);

        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);

        try {
            bundleClassLoader.loadClass(classToBeLoaded, true);
            fail();
        } catch (ClassNotFoundException cnf) {
            //this is expected

            //make sure boot delegation was done before CNF was thrown
            verify(mockFramework).getBootPackages();

            //make sure the class is added to the skip class cache
            assertEquals(BundleWiringImpl.CNFE_CLASS_LOADER, getAccessorCache(bundleWiring).get(classToBeLoaded));
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    @SuppressWarnings("rawtypes")
    public void initializeBundleWiringWithImportsAndRequired(Map<String, BundleRevision> importedPkgs, Map<String, List<BundleRevision>> requiredPkgs) throws Exception
    {

        mockResolver = mock(StatefulResolver.class);
        mockRevisionImpl = mock(BundleRevisionImpl.class);
        mockBundle = mock(BundleImpl.class);

        Logger logger = new Logger();
        Map configMap = new HashMap();
        List<BundleRevision> fragments = new ArrayList<>();
        List<BundleWire> wires = new ArrayList<>();

        when(mockRevisionImpl.getBundle()).thenReturn(mockBundle);
        when(mockBundle.getBundleId()).thenReturn(Long.valueOf(1));

        bundleWiring = new BundleWiringImpl(logger, configMap, mockResolver,
                mockRevisionImpl, fragments, wires, importedPkgs, requiredPkgs);
    }

    @Test
    public void testAccessorFirstLoadFailed() throws Exception
    {

        String classToBeLoaded = "sun.reflect.GeneratedMethodAccessor21";

        Felix mockFramework = mock(Felix.class);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        Map<String, BundleRevision> importedPkgs = mock(Map.class);
        Map<String, List<BundleRevision>> requiredPkgs = mock(Map.class);

        initializeBundleWiringWithImportsAndRequired(importedPkgs, requiredPkgs);

        when(bundleWiring.getBundle().getFramework()).thenReturn(mockFramework);

        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);

        try {
            bundleClassLoader.loadClass(classToBeLoaded, true);
            fail();
        } catch (ClassNotFoundException cnf) {
            //this is expected

            //make sure boot delegation was done before CNF was thrown
            verify(mockFramework).getBootPackages();

            //make sure imported and required pkgs are searched
            verify(importedPkgs).values();
            verify(requiredPkgs).values();

            //make sure the class is added to the skip class cache
            assertEquals(BundleWiringImpl.CNFE_CLASS_LOADER, getAccessorCache(bundleWiring).get(classToBeLoaded));
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void testAccessorSubsequentLoadFailed() throws Exception
    {

        String classToBeLoaded = "sun.reflect.GeneratedMethodAccessor21";

        Felix mockFramework = mock(Felix.class);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        Map<String, BundleRevision> importedPkgs = mock(Map.class);
        Map<String, List<BundleRevision>> requiredPkgs = mock(Map.class);

        initializeBundleWiringWithImportsAndRequired(importedPkgs, requiredPkgs);

        when(bundleWiring.getBundle().getFramework()).thenReturn(mockFramework);

        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);

        //first attempt to populate the cache
        try {
            bundleClassLoader.loadClass(classToBeLoaded, true);
            fail();
        } catch (ClassNotFoundException cnf) {
            //this is expected
        }

        //now test that the subsequent class load throws CNF with out boot delegation and import/required packages
        try {

            importedPkgs = mock(Map.class);
            requiredPkgs = mock(Map.class);
            initializeBundleWiringWithImportsAndRequired(importedPkgs, requiredPkgs);
            mockFramework = mock(Felix.class);
            when(mockFramework.getBootPackages()).thenReturn(new String[0]);
            when(bundleWiring.getBundle().getFramework()).thenReturn(mockFramework);
            bundleClassLoader.loadClass(classToBeLoaded, true);
            fail();
        } catch (ClassNotFoundException cnf) {
            //this is expected

            //make sure boot delegation was not used
            verify(mockFramework, never()).getBootPackages();

            //make sure boot import and required packages were not searched
            verify(importedPkgs, never()).values();
            verify(requiredPkgs, never()).values();

        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    private BundleRevision getBundleRevision(String classToBeLoaded, BundleClassLoader pkgBundleClassLoader, Object value) throws ClassNotFoundException {
        BundleRevision bundleRevision = mock(BundleRevision.class);
        BundleWiring pkgBundleWiring = mock(BundleWiring.class);
        when(pkgBundleClassLoader.findLoadedClassInternal(classToBeLoaded)).thenAnswer(createAnswer(value));
        when(pkgBundleClassLoader.loadClass(classToBeLoaded)).thenAnswer(createAnswer(value));

        when(pkgBundleWiring.getClassLoader()).thenReturn(pkgBundleClassLoader);
        when(bundleRevision.getWiring()).thenReturn(pkgBundleWiring);
        return bundleRevision;
    }

    @Test
    public void testAccessorLoadImportPackage() throws Exception
    {

        String classToBeLoaded = "sun.reflect.GeneratedMethodAccessor21";

        Felix mockFramework = mock(Felix.class);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        Map<String, BundleRevision> importedPkgs = mock(Map.class);
        BundleClassLoader foundClassLoader = mock(BundleClassLoader.class);
        BundleClassLoader notFoundClassLoader = mock(BundleClassLoader.class);
        BundleRevision bundleRevision1 = getBundleRevision(classToBeLoaded, foundClassLoader, String.class);
        BundleRevision bundleRevision2 = getBundleRevision(classToBeLoaded, notFoundClassLoader, null);
        Map<String, BundleRevision> importedPkgsActual = new LinkedHashMap<>();
        importedPkgsActual.put("sun.reflect1", bundleRevision1);
        importedPkgsActual.put("sun.reflect2", bundleRevision2);
        when(importedPkgs.values()).thenReturn(importedPkgsActual.values());
        Map<String, List<BundleRevision>> requiredPkgs = mock(Map.class);

        initializeBundleWiringWithImportsAndRequired(importedPkgs, requiredPkgs);

        when(bundleWiring.getBundle().getFramework()).thenReturn(mockFramework);

        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);

        //call class load to populate the cache
        try {
            Object result = bundleClassLoader.loadClass(classToBeLoaded, true);
            assertNotNull(result);
            assertTrue(getAccessorCache(bundleWiring).containsKey(classToBeLoaded));
            assertEquals(getAccessorCache(bundleWiring).get(classToBeLoaded), foundClassLoader);
            verify(foundClassLoader, times(1)).findLoadedClassInternal(classToBeLoaded);
            verify(notFoundClassLoader, never()).findLoadedClassInternal(classToBeLoaded);
        } catch (Exception e) {
            fail();
        }

        //now make sure subsequent class load happens from cached revision
        Object result = bundleClassLoader.loadClass(classToBeLoaded, true);
        assertNotNull(result);
        //makes sure the look up cache is accessed and the class is loaded from cached revision
        verify(foundClassLoader, times(1)).findLoadedClassInternal(classToBeLoaded);
        verify(foundClassLoader, times(1)).loadClass(classToBeLoaded);
        verify(notFoundClassLoader, never()).findLoadedClassInternal(classToBeLoaded);
    }

    private static <T> Answer<T> createAnswer(final T value) {
        return invocation -> value;
    }

    @Test
    public void testAccessorBootDelegate() throws Exception
    {

        String classToBeLoaded = "sun.reflect.GeneratedMethodAccessor21";

        Felix mockFramework = mock(Felix.class);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        Map<String, BundleRevision> importedPkgs = mock(Map.class);
        BundleRevision bundleRevision1 = mock(BundleRevision.class);
        Map<String, BundleRevision> importedPkgsActual = new HashMap<>();
        importedPkgsActual.put("sun.reflect1", bundleRevision1);
        when(importedPkgs.values()).thenReturn(importedPkgsActual.values());
        Map<String, List<BundleRevision>> requiredPkgs = mock(Map.class);

        ClassLoader bootDelegateClassLoader = mock(ClassLoader.class);

        when(bootDelegateClassLoader.loadClass(classToBeLoaded)).thenAnswer(createAnswer(String.class));

        initializeBundleWiringWithImportsAndRequired(importedPkgs, requiredPkgs);

        when(bundleWiring.getBundle().getFramework()).thenReturn(mockFramework);

        Field field = bundleWiring.getClass().getDeclaredField("m_bootClassLoader");
        field.setAccessible(true);
        field.set(bundleWiring, bootDelegateClassLoader);

        BundleClassLoader bundleClassLoader = createBundleClassLoader(
                BundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);

        try {
            Object result = bundleClassLoader.loadClass(classToBeLoaded, true);
            assertNotNull(result);
            verify(importedPkgs, never()).values();
            verify(requiredPkgs, never()).values();
            assertTrue(getAccessorCache(bundleWiring).containsKey(classToBeLoaded));
            assertTrue(getAccessorCache(bundleWiring).get(classToBeLoaded) == bootDelegateClassLoader);
        } catch (Exception e) {
            fail();
        }

        //now make sure subsequent class loading happens from boot delegation
        Object result = bundleClassLoader.loadClass(classToBeLoaded, true);
        assertNotNull(result);
        //makes sure the look up cache is accessed and the class is loaded via boot delegation
        verify(importedPkgs, never()).values();
        verify(requiredPkgs, never()).values();
    }

    @Test
    public void testParallelClassload() throws Exception
    {


        Felix mockFramework = mock(Felix.class);
        HookRegistry hReg = mock(HookRegistry.class);
        Mockito.when(mockFramework.getHookRegistry()).thenReturn(hReg);
        Content mockContent = mock(Content.class);
        final Class testClass = TestClassSuper.class;
        final String testClassName = testClass.getName();
        final String testClassAsPath = testClassName.replace('.', '/') + ".class";
        byte[] testClassBytes = createTestClassBytes(testClass, testClassAsPath);

        final Class testClass2 = TestClassChild.class;
        final String testClassName2 = testClass2.getName();
        final String testClassAsPath2 = testClassName2.replace('.', '/') + ".class";
        byte[] testClassBytes2 = createTestClassBytes(testClass2, testClassAsPath2);

        final Class testClass3 = TestClass.class;
        final String testClassName3 = testClass3.getName();
        final String testClassAsPath3 = testClassName3.replace('.', '/') + ".class";
        byte[] testClassBytes3 = createTestClassBytes(testClass3, testClassAsPath3);

        List<Content> contentPath = new ArrayList<>();
        contentPath.add(mockContent);
        BundleWiringImpl bundleWiring;

        StatefulResolver mockResolver;

        BundleRevisionImpl mockRevisionImpl;

        BundleImpl mockBundle;

        mockResolver = mock(StatefulResolver.class);
        mockRevisionImpl = mock(BundleRevisionImpl.class);
        mockBundle = mock(BundleImpl.class);

        Logger logger = new Logger();
        Map configMap = new HashMap();
        List<BundleRevision> fragments = new ArrayList<>();
        List<BundleWire> wires = new ArrayList<>();
        Map<String, BundleRevision> importedPkgs = new HashMap<>();
        Map<String, List<BundleRevision>> requiredPkgs = new HashMap<>();

        when(mockRevisionImpl.getBundle()).thenReturn(mockBundle);
        when(mockBundle.getBundleId()).thenReturn(Long.valueOf(1));

        bundleWiring = new BundleWiringImpl(logger, configMap, mockResolver,
            mockRevisionImpl, fragments, wires, importedPkgs, requiredPkgs);

        when(mockBundle.getFramework()).thenReturn(mockFramework);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        when(mockRevisionImpl.getContentPath()).thenReturn(contentPath);
        when(mockContent.getEntryAsBytes(testClassAsPath)).thenReturn(
            testClassBytes);
        when(mockContent.getEntryAsBytes(testClassAsPath2)).thenReturn(
            testClassBytes2);
        when(mockContent.getEntryAsBytes(testClassAsPath3)).thenReturn(
            testClassBytes3);


        final TestBundleClassLoader bundleClassLoader = createBundleClassLoader(
            TestBundleClassLoader.class, bundleWiring);
        assertNotNull(bundleClassLoader);

        Field m_classLoader = bundleWiring.getClass().getDeclaredField("m_classLoader");
        m_classLoader.setAccessible(true);
        m_classLoader.set(bundleWiring, bundleClassLoader);

        assertTrue(bundleClassLoader.isParallel());

        final AtomicInteger loaded = new AtomicInteger();
        new Thread(() -> {
            try
            {
                loaded.set(bundleClassLoader.findClass(testClassName2) != null ? 1 : 2);
            }
            catch (Exception e)
            {
                e.printStackTrace();
                loaded.set(3);
            }
        }).start();

        while (bundleClassLoader.m_gate.getQueueLength() == 0)
        {
            Thread.sleep(1);
        }

        final AtomicInteger loaded2 = new AtomicInteger();
        new Thread(() -> {
            try
            {
                loaded2.set(bundleClassLoader.findClass(testClassName3) != null ? 1 : 2);
            }
            catch (ClassNotFoundException e)
            {
                e.printStackTrace();
                loaded2.set(3);
            }
        }).start();

        while (loaded2.get() == 0)
        {
            Thread.sleep(1);
        }

        assertEquals(0, loaded.get());
        assertEquals(1, bundleClassLoader.m_gate.getQueueLength());

        loaded2.set(0);
        Thread tester = new Thread(() -> {
            try
            {
                loaded2.set(bundleClassLoader.findClass(testClassName2) != null ? 1 : 2);
            }
            catch (ClassNotFoundException e)
            {
                e.printStackTrace();
                loaded2.set(3);
            }
        });
        tester.start();

        Thread.sleep(100);

        assertEquals(0, loaded2.get());
        assertEquals(1, bundleClassLoader.m_gate.getQueueLength());

        bundleClassLoader.m_gate.release();


        while (loaded.get() == 0)
        {
            Thread.sleep(1);
        }

        assertEquals(1, loaded.get());

        while (loaded2.get() == 0)
        {
            Thread.sleep(1);
        }
        assertEquals(1, loaded2.get());
    }

    @Test
    public void testClassloadStress() throws Exception
    {
        ExecutorService executors = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4);
        final List<Throwable> exceptionsNP = Collections.synchronizedList(new ArrayList<>());
        final List<Throwable> exceptionsP = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 100; i++) {
            executors.submit(i % 2 == 0 ? () -> {
                try
                {
                    testNotParallelClassload();
                }
                catch (Throwable e)
                {
                    exceptionsNP.add(e);
                }
            } : () -> {
                try
                {
                    testParallelClassload();
                }
                catch (Throwable e)
                {
                    exceptionsP.add(e);
                }
            });
        }
        executors.shutdown();
        executors.awaitTermination(10, TimeUnit.MINUTES);
        assertTrue(exceptionsNP.toString(), exceptionsNP.isEmpty());
        assertTrue(exceptionsP.toString(), exceptionsP.isEmpty());
    }

    @Test
    public void testNotParallelClassload() throws Exception
    {

        Felix mockFramework = mock(Felix.class);
        HookRegistry hReg = mock(HookRegistry.class);
        Mockito.when(mockFramework.getHookRegistry()).thenReturn(hReg);
        Content mockContent = mock(Content.class);
        final Class testClass = TestClassSuper.class;
        final String testClassName = testClass.getName();
        final String testClassAsPath = testClassName.replace('.', '/') + ".class";
        byte[] testClassBytes = createTestClassBytes(testClass, testClassAsPath);

        final Class testClass2 = TestClassChild.class;
        final String testClassName2 = testClass2.getName();
        final String testClassAsPath2 = testClassName2.replace('.', '/') + ".class";
        byte[] testClassBytes2 = createTestClassBytes(testClass2, testClassAsPath2);

        final Class testClass3 = TestClass.class;
        final String testClassName3 = testClass3.getName();
        final String testClassAsPath3 = testClassName3.replace('.', '/') + ".class";
        byte[] testClassBytes3 = createTestClassBytes(testClass3, testClassAsPath3);

        List<Content> contentPath = new ArrayList<>();
        contentPath.add(mockContent);
        BundleWiringImpl bundleWiring;

        StatefulResolver mockResolver;

        BundleRevisionImpl mockRevisionImpl;

        BundleImpl mockBundle;

        mockResolver = mock(StatefulResolver.class);
        mockRevisionImpl = mock(BundleRevisionImpl.class);
        mockBundle = mock(BundleImpl.class);

        Logger logger = new Logger();
        Map configMap = new HashMap();
        List<BundleRevision> fragments = new ArrayList<>();
        List<BundleWire> wires = new ArrayList<>();
        Map<String, BundleRevision> importedPkgs = new HashMap<>();
        Map<String, List<BundleRevision>> requiredPkgs = new HashMap<>();

        when(mockRevisionImpl.getBundle()).thenReturn(mockBundle);
        when(mockBundle.getBundleId()).thenReturn(Long.valueOf(1));

        bundleWiring = new BundleWiringImpl(logger, configMap, mockResolver,
            mockRevisionImpl, fragments, wires, importedPkgs, requiredPkgs);

        when(mockBundle.getFramework()).thenReturn(mockFramework);
        when(mockFramework.getBootPackages()).thenReturn(new String[0]);

        when(mockRevisionImpl.getContentPath()).thenReturn(contentPath);
        when(mockContent.getEntryAsBytes(testClassAsPath)).thenReturn(
            testClassBytes);
        when(mockContent.getEntryAsBytes(testClassAsPath2)).thenReturn(
            testClassBytes2);
        when(mockContent.getEntryAsBytes(testClassAsPath3)).thenReturn(
            testClassBytes3);


        final TestBundleClassLoader2 bundleClassLoader = createBundleClassLoader(
            TestBundleClassLoader2.class, bundleWiring);
        assertNotNull(bundleClassLoader);

        Field m_classLoader = bundleWiring.getClass().getDeclaredField("m_classLoader");
        m_classLoader.setAccessible(true);
        m_classLoader.set(bundleWiring, bundleClassLoader);

        assertFalse(bundleClassLoader.isParallel());

        final AtomicInteger loaded = new AtomicInteger();
        new Thread(() -> {
            try
            {
                loaded.set(bundleClassLoader.findClass(testClassName2) != null ? 1 : 2);
            }
            catch (Exception e)
            {
                e.printStackTrace();
                loaded.set(3);
            }
        }).start();

        while (bundleClassLoader.m_gate.getQueueLength() == 0)
        {
            Thread.sleep(1);
        }

        final AtomicInteger loaded2 = new AtomicInteger();
        new Thread(() -> {
            try
            {
                loaded2.set(bundleClassLoader.findClass(testClassName3) != null ? 1 : 2);
            }
            catch (ClassNotFoundException e)
            {
                e.printStackTrace();
                loaded2.set(3);
            }
        }).start();

        Thread.sleep(100);

        assertEquals(0, loaded.get());
        assertEquals(0, loaded2.get());
        assertEquals(1, bundleClassLoader.m_gate.getQueueLength());

        final AtomicInteger loaded3 = new AtomicInteger();
        Thread tester = new Thread(() -> {
            try
            {
                loaded3.set(bundleClassLoader.findClass(testClassName2) != null ? 1 : 2);
            }
            catch (ClassNotFoundException e)
            {
                e.printStackTrace();
                loaded3.set(3);
            }
        });
        tester.start();

        Thread.sleep(100);

        assertEquals(0, loaded3.get());
        assertEquals(0, loaded2.get());

        assertEquals(0, loaded.get());
        assertEquals(1, bundleClassLoader.m_gate.getQueueLength());

        bundleClassLoader.m_gate.release();


        while (loaded.get() == 0)
        {
            Thread.sleep(1);
        }

        assertEquals(1, loaded.get());

        while (loaded2.get() == 0)
        {
            Thread.sleep(1);
        }
        assertEquals(1, loaded2.get());

        while (loaded3.get() == 0)
        {
            Thread.sleep(1);
        }
        assertEquals(1, loaded3.get());
    }

    private static class TestBundleClassLoader extends BundleClassLoader
    {
        static {
            ClassLoader.registerAsParallelCapable();
        }

        Semaphore m_gate = new Semaphore(0);
        public TestBundleClassLoader(BundleWiringImpl wiring, ClassLoader parent, Logger logger)
        {
            super(wiring, parent, logger);
        }

        @Override
        protected Class loadClass(String name, boolean resolve) throws ClassNotFoundException
        {
            if (name.startsWith("java"))
            {
                return getClass().getClassLoader().loadClass(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class findClass(String name) throws ClassNotFoundException
        {
            if (name.startsWith("java"))
            {
                return getClass().getClassLoader().loadClass(name);
            }
            if (name.equals(TestClassSuper.class.getName()))
            {
                m_gate.acquireUninterruptibly();
            }
            return super.findClass(name);
        }
    }

    private static class TestBundleClassLoader2 extends BundleClassLoader
    {
        Semaphore m_gate = new Semaphore(0);
        public TestBundleClassLoader2(BundleWiringImpl wiring, ClassLoader parent, Logger logger)
        {
            super(wiring, parent, logger);
        }

        @Override
        protected Class loadClass(String name, boolean resolve) throws ClassNotFoundException
        {
            if (name.startsWith("java"))
            {
                return getClass().getClassLoader().loadClass(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        protected Class findClass(String name) throws ClassNotFoundException
        {
            if (name.startsWith("java"))
            {
                return getClass().getClassLoader().loadClass(name);
            }
            if (name.equals(TestClassSuper.class.getName()))
            {
                m_gate.acquireUninterruptibly();
            }
            return super.findClass(name);
        }

        @Override
        protected boolean isParallel()
        {
            return false;
        }
    }

    @SuppressWarnings("rawtypes")
    private byte[] createTestClassBytes(Class testClass, String testClassAsPath)
            throws IOException
    {
        InputStream testClassResourceStream = testClass.getClassLoader()
                .getResourceAsStream(testClassAsPath);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int curByte;
        while ((curByte = testClassResourceStream.read()) != -1)
        {
            baos.write(curByte);
        }
        return baos.toByteArray();
    }

    private <T> T createBundleClassLoader(
            Class<T> bundleClassLoaderClass, BundleWiringImpl bundleWiring)
                    throws Exception
    {
        Logger logger = new Logger();
        Constructor<T> ctor = BundleRevisionImpl.getSecureAction().getConstructor(
                bundleClassLoaderClass,
            BundleWiringImpl.class, ClassLoader.class,
            Logger.class);
        BundleRevisionImpl.getSecureAction().setAccesssible(ctor);
        return BundleRevisionImpl
                .getSecureAction().invoke(
                        ctor,
                        bundleWiring,
                                this.getClass().getClassLoader(), logger );
    }

    class TestClass
    {
        // An empty test class to weave.
    }

    class TestClassSuper
    {
        // An empty test class to weave.
    }

    class TestClassChild extends TestClassSuper
    {

    }

    class GoodDummyWovenHook implements WeavingHook
    {
        // Adds the awesomePublicField to a class
        @Override
        public void weave(WovenClass wovenClass)
        {
            byte[] wovenClassBytes = wovenClass.getBytes();
            ClassNode classNode = new ClassNode();
            ClassReader reader = new ClassReader(wovenClassBytes);
            reader.accept(classNode, 0);
            classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC,
                    "awesomePublicField", "Ljava/lang/String;", null, null));
            ClassWriter writer = new ClassWriter(reader, Opcodes.ASM4);
            classNode.accept(writer);
            wovenClass.setBytes(writer.toByteArray());
        }
    }

    class BadDefineWovenHook implements WeavingHook
    {
        // Adds the awesomePublicField twice to the class. This is bad java.
        @Override
        public void weave(WovenClass wovenClass)
        {
            byte[] wovenClassBytes = wovenClass.getBytes();
            ClassNode classNode = new ClassNode();
            ClassReader reader = new ClassReader(wovenClassBytes);
            reader.accept(classNode, 0);
            classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC,
                    "awesomePublicField", "Ljava/lang/String;", null, null));
            classNode.fields.add(new FieldNode(Opcodes.ACC_PUBLIC,
                    "awesomePublicField", "Ljava/lang/String;", null, null));
            ClassWriter writer = new ClassWriter(reader, Opcodes.ASM4);
            classNode.accept(writer);
            wovenClass.setBytes(writer.toByteArray());
        }
    }

    class BadDummyWovenHook implements WeavingHook
    {
        // Just Blow up
        @Override
        public void weave(WovenClass wovenClass)
        {
            throw new WeavingException("Bad Weaver!");
        }
    }

    class DummyWovenClassListener implements WovenClassListener
    {
        public List<Integer> stateList = new ArrayList<>();

        @Override
        public void modified(WovenClass wovenClass)
        {
            stateList.add(wovenClass.getState());
        }
    }
}
