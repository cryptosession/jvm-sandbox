package com.alibaba.jvm.sandbox.core.manager.impl;

import com.alibaba.jvm.sandbox.api.event.Event;
import com.alibaba.jvm.sandbox.api.listener.EventListener;
import com.alibaba.jvm.sandbox.core.util.matcher.Matcher;
import com.google.common.collect.Maps;

import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author <a href="mailto:renyi.cry@alibaba-inc.com">renyi.cry<a/>
 * @date 2021/2/15 10:23 下午
 */
public class AsyncSandboxClassFileTransformerWrapper extends SandboxClassFileTransformer {

    private Map<Class<?>, byte[]> transformedClassBytesMap = Maps.newConcurrentMap();

    private static final ExecutorService executorService =
            Executors.newFixedThreadPool(1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    t.setName("Sandbox class file transformation task");
                    return t;
                }
            });

    public AsyncSandboxClassFileTransformerWrapper(Instrumentation inst,
                                                   final int watchId,
                                                   final String uniqueId,
                                                   final Matcher matcher,
                                                   final EventListener eventListener,
                                                   final boolean isEnableUnsafe,
                                                   final Event.Type[] eventTypeArray,
                                                   final String namespace) {

        super(inst, watchId, uniqueId, matcher, eventListener, isEnableUnsafe, eventTypeArray, namespace);

    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (!transformedClassBytesMap.containsKey(classBeingRedefined)) {
            executorService.submit(new TransformationTask(this, loader, className, classBeingRedefined, protectionDomain, classfileBuffer));
            return classfileBuffer;
        } else {
            return transformedClassBytesMap.get(classBeingRedefined);
        }

    }

    private void transformThenCache(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {

        byte[] transformedClassBytes = super.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);

        transformedClassBytesMap.put(classBeingRedefined,transformedClassBytes);

    }

    private static class TransformationTask implements Runnable {

        private AsyncSandboxClassFileTransformerWrapper classFileTransformer;
        private final ClassLoader loader;
        private final String className;
        private final Class<?> classBeingRedefined;
        private final ProtectionDomain protectionDomain;
        private final byte[] classfileBuffer;

        TransformationTask(AsyncSandboxClassFileTransformerWrapper classFileTransformer,
                           ClassLoader loader,
                           String className,
                           Class<?> classBeingRedefined,
                           ProtectionDomain protectionDomain,
                           byte[] classfileBuffer) {
            this.classFileTransformer = classFileTransformer;
            this.loader = loader;
            this.className = className;
            this.classBeingRedefined = classBeingRedefined;
            this.protectionDomain = protectionDomain;
            this.classfileBuffer = classfileBuffer;
        }


        @Override
        public void run() {
            try {
                // transform then cache
                classFileTransformer.transformThenCache(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
                // retransform classes
                classFileTransformer.getInstrument().retransformClasses(classBeingRedefined);
            } catch (IllegalClassFormatException | UnmodifiableClassException e) {
                e.printStackTrace();
            }

        }

    }


}