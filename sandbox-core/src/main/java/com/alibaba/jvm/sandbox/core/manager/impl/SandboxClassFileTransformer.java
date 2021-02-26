package com.alibaba.jvm.sandbox.core.manager.impl;

import com.alibaba.jvm.sandbox.api.event.Event;
import com.alibaba.jvm.sandbox.api.event.Event.Type;
import com.alibaba.jvm.sandbox.api.listener.EventListener;
import com.alibaba.jvm.sandbox.core.enhance.EventEnhancer;
import com.alibaba.jvm.sandbox.core.enhance.weaver.asm.EventWeaver;
import com.alibaba.jvm.sandbox.core.manager.NativeMethodEnhanceAware;
import com.alibaba.jvm.sandbox.core.util.ObjectIDs;
import com.alibaba.jvm.sandbox.core.util.SandboxClassUtils;
import com.alibaba.jvm.sandbox.core.util.SandboxProtector;
import com.alibaba.jvm.sandbox.core.util.matcher.Matcher;
import com.alibaba.jvm.sandbox.core.util.matcher.MatchingResult;
import com.alibaba.jvm.sandbox.core.util.matcher.UnsupportedMatcher;
import com.alibaba.jvm.sandbox.core.util.matcher.structure.ClassStructure;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.alibaba.jvm.sandbox.core.util.matcher.structure.ClassStructureFactory.createClassStructure;

/**
 * 沙箱类形变器
 *
 * @author luanjia@taobao.com
 */
public class SandboxClassFileTransformer implements ClassFileTransformer, NativeMethodEnhanceAware {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final AtomicBoolean setNativeMethodPrefix = new AtomicBoolean(false);
    private final Instrumentation inst;
    private final int watchId;
    private final String uniqueId;
    private final Matcher matcher;
    private final EventListener eventListener;
    private final boolean isEnableUnsafe;
    private final Event.Type[] eventTypeArray;

    private final String namespace;
    private final int listenerId;
    private final AffectStatistic affectStatistic = new AffectStatistic();

    // triple: classloader - classname - isComeFromSandboxFamily
    private static final ThreadLocal<Triple<ClassLoader, String, Boolean>> isComeFromSandboxFamily =
            new ThreadLocal<Triple<ClassLoader, String, Boolean>>() {
                protected Triple<ClassLoader, String, Boolean> initialValue() {
                    return Triple.of(null, null, false);
                }
            };

    // triple: classloader - class - isComeFromSandboxFamily
    private static final ThreadLocal<Pair<? extends Triple<ClassLoader, ? extends Class<?>, byte[]>, ClassStructure>> classStructureCache =
            new ThreadLocal<Pair<? extends Triple<ClassLoader, ? extends Class<?>, byte[]>, ClassStructure>>() {
                protected Pair<? extends Triple<ClassLoader, ? extends Class<?>, byte[]>, ClassStructure> initialValue() {
                    return Pair.of(null, null);
                }
            };

    SandboxClassFileTransformer(Instrumentation inst, final int watchId,
        final String uniqueId,
        final Matcher matcher,
        final EventListener eventListener,
        final boolean isEnableUnsafe,
        final Type[] eventTypeArray,
        final String namespace) {
        this.inst = inst;
        this.watchId = watchId;
        this.uniqueId = uniqueId;
        this.matcher = matcher;
        this.eventListener = eventListener;
        this.isEnableUnsafe = isEnableUnsafe;
        this.eventTypeArray = eventTypeArray;
        this.namespace = namespace;
        this.listenerId = ObjectIDs.instance.identity(eventListener);
    }

    // 获取当前类结构
    private ClassStructure getClassStructure(final ClassLoader loader,
                                             final Class<?> classBeingRedefined,
                                             final byte[] srcByteCodeArray) {
        return null == classBeingRedefined
                ? createClassStructure(srcByteCodeArray, loader)
                : createClassStructure(classBeingRedefined);
    }

    @Override
    public byte[] transform(final ClassLoader loader,
                            final String internalClassName,
                            final Class<?> classBeingRedefined,
                            final ProtectionDomain protectionDomain,
                            final byte[] srcByteCodeArray) {
        SandboxProtector.getOrCreateInstance().enterProtecting();

        // 如果未开启unsafe开关，是不允许增强来自BootStrapClassLoader的类
        if (!isEnableUnsafe
                && null == loader) {
            logger.debug("transform ignore {}, class from bootstrap but unsafe.enable=false.", internalClassName);
            return null;
        }

        try {

            Triple<ClassLoader, String, Boolean> classBooleanPair = isComeFromSandboxFamily.get();

            ClassLoader classLoader = classBooleanPair.getLeft();
            String classname = classBooleanPair.getMiddle();

            if (Objects.equals(loader, classLoader) && Objects.equals(internalClassName, classname)) {
                if (!classBooleanPair.getRight()) {
                    return null;
                }
            } else {
                // 这里过滤掉Sandbox所需要的类|来自SandboxClassLoader所加载的类|来自ModuleJarClassLoader加载的类
                // 防止ClassCircularityError的发生
                if (SandboxClassUtils.isComeFromSandboxFamily(internalClassName, loader)) {
                    isComeFromSandboxFamily.set(Triple.of(loader, internalClassName, false));
                    return null;
                }
                isComeFromSandboxFamily.set(Triple.of(loader, internalClassName, true));
            }

            return _transform(
                    loader,
                    internalClassName,
                    classBeingRedefined,
                    srcByteCodeArray
            );


        } catch (Throwable cause) {
            logger.warn("sandbox transform {} in loader={}; failed, module={} at watch={}, will ignore this transform.",
                    internalClassName,
                    loader,
                    uniqueId,
                    watchId,
                    cause
            );
            return null;
        } finally {
            SandboxProtector.getOrCreateInstance().exitProtecting();
        }
    }

    private byte[] _transform(final ClassLoader loader,
                              final String internalClassName,
                              final Class<?> classBeingRedefined,
                              final byte[] srcByteCodeArray) {


        Pair<? extends Triple<ClassLoader, ? extends Class<?>, byte[]>, ClassStructure> tripleClassStructurePair = classStructureCache.get();

        Triple<ClassLoader, ? extends Class<?>, byte[]> triple = Triple.of(loader, classBeingRedefined, srcByteCodeArray);

        final ClassStructure classStructure;
        Triple<ClassLoader, ? extends Class<?>, byte[]> theTriple = tripleClassStructurePair.getLeft();
        if (Objects.equals(theTriple, triple)) {
            classStructure = tripleClassStructurePair.getRight();
        } else {
            classStructure = getClassStructure(loader, classBeingRedefined, srcByteCodeArray);
            classStructureCache.set(Pair.of(triple, classStructure));
        }

        final MatchingResult matchingResult = new UnsupportedMatcher(loader, isEnableUnsafe).and(matcher).matching(classStructure);

        // 如果一个行为都没匹配上也不用继续了
        if (!matchingResult.isMatched()) {
            logger.debug("transform ignore {}, no behaviors matched in loader={}", internalClassName, loader);
            return null;
        }

        // 开始进行类匹配
        try {

            final Set<String> behaviorSignCodes = matchingResult.getBehaviorSignCodes();

            final byte[] toByteCodeArray = new EventEnhancer(this).toByteCodeArray(
                    loader,
                    srcByteCodeArray,
                    behaviorSignCodes,
                    namespace,
                    listenerId,
                    eventTypeArray
            );
            if (srcByteCodeArray == toByteCodeArray) {
                logger.debug("transform ignore {}, nothing changed in loader={}", internalClassName, loader);
                return null;
            }

            // statistic affect
            affectStatistic.statisticAffect(loader, internalClassName, behaviorSignCodes);

            logger.info("transform {} finished, by module={} in loader={}", internalClassName, uniqueId, loader);
            return toByteCodeArray;
        } catch (Throwable cause) {
            logger.warn("transform {} failed, by module={} in loader={}", internalClassName, uniqueId, loader, cause);
            return null;
        }
    }


    /**
     * 获取观察ID
     *
     * @return 观察ID
     */
    int getWatchId() {
        return watchId;
    }

    /**
     * 获取事件监听器
     *
     * @return 事件监听器
     */
    EventListener getEventListener() {
        return eventListener;
    }

    /**
     * 获取事件监听器ID
     *
     * @return 事件监听器ID
     */
    int getListenerId() {
        return listenerId;
    }

    /**
     * 获取本次匹配器
     *
     * @return 匹配器
     */
    Matcher getMatcher() {
        return matcher;
    }

    /**
     * 获取本次监听事件类型数组
     *
     * @return 本次监听事件类型数组
     */
    Event.Type[] getEventTypeArray() {
        return eventTypeArray;
    }

    /**
     * 获取本次增强的影响统计
     *
     * @return 本次增强的影响统计
     */
    public AffectStatistic getAffectStatistic() {
        return affectStatistic;
    }

    /**
     * Get Instrumentation
     *
     * @return instrumentation
     */
    public Instrumentation getInstrument() {
        return inst;
    }

    @Override
    public String getNativeMethodPrefix() {
        return EventWeaver.NATIVE_PREFIX;
    }

    @Override
    public void markNativeMethodEnhance() {
        if(setNativeMethodPrefix.compareAndSet(false,true)){
            if(inst.isNativeMethodPrefixSupported()){
                inst.setNativeMethodPrefix(this, getNativeMethodPrefix());
            }else{
                throw new UnsupportedOperationException("Native Method Prefix Unsupported");
            }
        }
    }

    public static void main(String[] args) {
        char[] a= "sss".toCharArray();
        char[] b= "sss".toCharArray();

        System.out.println(Objects.equals(a, b));
    }
}
