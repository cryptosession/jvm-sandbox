package com.alibaba.jvm.sandbox.core.manager.async;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.concurrent.*;

/**
 * @author <a href="mailto:renyi.cry@alibaba-inc.com">renyi.cry<a/>
 * @date 2021/2/15 10:23 下午
 */
public class AsyncSandboxClassFileTransformerWrapper implements ClassFileTransformer {

    private final ClassFileTransformer classFileTransformer;

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

    public AsyncSandboxClassFileTransformerWrapper(ClassFileTransformer classFileTransformer) {
        this.classFileTransformer = classFileTransformer;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {

        executorService.submit(new TransformationTask(classFileTransformer, loader, className, classBeingRedefined, protectionDomain, classfileBuffer));

        return classfileBuffer;
    }

    private static class TransformationTask implements Runnable {

        private ClassFileTransformer classFileTransformer;
        private final ClassLoader loader;
        private final String className;
        private final Class<?> classBeingRedefined;
        private final ProtectionDomain protectionDomain;
        private final byte[] classfileBuffer;

        TransformationTask(ClassFileTransformer classFileTransformer,
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
                classFileTransformer.transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
            } catch (IllegalClassFormatException e) {
                e.printStackTrace();
            }

        }

    }

}
