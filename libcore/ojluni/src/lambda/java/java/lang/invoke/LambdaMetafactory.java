/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.invoke;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import sun.invoke.util.Wrapper;

// art_standalone-changed: AOSP ships this class as a stub returning null, because the AOSP build
// desugars all lambdas. Here libcore is dexed with dx and keeps invoke-custom, so provide a working
// implementation. Instead of spinning a class, each lambda instance is a java.lang.reflect.Proxy
// that forwards the functional method to the implementation method handle (with the captured
// arguments prepended) and runs default methods of the implemented interfaces.
public class LambdaMetafactory {

    public static final int FLAG_SERIALIZABLE = 1 << 0;

    public static final int FLAG_MARKERS = 1 << 1;

    public static final int FLAG_BRIDGES = 1 << 2;

    public static CallSite metafactory(MethodHandles.Lookup caller,
                                       String invokedName,
                                       MethodType invokedType,
                                       MethodType samMethodType,
                                       MethodHandle implMethod,
                                       MethodType instantiatedMethodType)
            throws LambdaConversionException {
        return makeCallSite(invokedName, invokedType, implMethod,
                            new Class<?>[] { invokedType.returnType() });
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller,
                                          String invokedName,
                                          MethodType invokedType,
                                          Object... args)
            throws LambdaConversionException {
        MethodHandle implMethod = (MethodHandle) args[1];
        int flags = (Integer) args[3];
        List<Class<?>> interfaces = new ArrayList<>();
        interfaces.add(invokedType.returnType());
        int argIndex = 4;
        if ((flags & FLAG_MARKERS) != 0) {
            int markerCount = (Integer) args[argIndex++];
            for (int i = 0; i < markerCount; i++) {
                Class<?> marker = (Class<?>) args[argIndex++];
                if (!interfaces.contains(marker)) {
                    interfaces.add(marker);
                }
            }
        }
        // Bridges (FLAG_BRIDGES) need no extra handling: every abstract method with the
        // functional method's name is forwarded to the implementation method.
        if ((flags & FLAG_SERIALIZABLE) != 0 && !interfaces.contains(Serializable.class)) {
            interfaces.add(Serializable.class);
        }
        return makeCallSite(invokedName, invokedType, implMethod,
                            interfaces.toArray(new Class<?>[interfaces.size()]));
    }

    private static CallSite makeCallSite(String invokedName, MethodType invokedType,
                                         MethodHandle implMethod, Class<?>[] interfaces)
            throws LambdaConversionException {
        MethodHandle factory;
        try {
            // Lookup.findStatic() refuses private members for lookups in java.lang.invoke
            Method newInstance = LambdaMetafactory.class.getDeclaredMethod("newInstance",
                    Class[].class, String.class, MethodHandle.class, Object[].class);
            newInstance.setAccessible(true);
            factory = MethodHandles.Lookup.IMPL_LOOKUP.unreflect(newInstance);
        } catch (ReflectiveOperationException e) {
            throw new LambdaConversionException(e);
        }
        factory = MethodHandles.insertArguments(factory, 0, interfaces, invokedName, implMethod);
        factory = factory.asCollector(Object[].class, invokedType.parameterCount());
        // asType() on these combinators doesn't convert arguments, so box captured primitives explicitly
        for (int i = 0; i < invokedType.parameterCount(); i++) {
            Class<?> type = invokedType.parameterType(i);
            if (!type.isPrimitive())
                continue;
            Class<?> wrapper = Wrapper.forPrimitiveType(type).wrapperType();
            try {
                MethodHandle box = MethodHandles.Lookup.IMPL_LOOKUP.findStatic(wrapper, "valueOf",
                        MethodType.methodType(wrapper, type));
                factory = MethodHandles.filterArguments(factory, i,
                        box.asType(MethodType.methodType(Object.class, type)));
            } catch (ReflectiveOperationException e) {
                throw new LambdaConversionException(e);
            }
        }
        // asType() only changes the nominal type of such a handle, but invoke-custom requires the
        // call site target to have exactly the call site type. filterReturnValue() creates a
        // handle whose type is derived from the nominal type, i.e. exactly invokedType.
        factory = factory.asType(invokedType);
        factory = MethodHandles.filterReturnValue(factory,
                MethodHandles.identity(invokedType.returnType()));
        return new ConstantCallSite(factory);
    }

    private static Object newInstance(Class<?>[] interfaces, String samName,
                                      MethodHandle implMethod, Object[] captured) {
        return Proxy.newProxyInstance(interfaces[0].getClassLoader(), interfaces,
                new LambdaInvocationHandler(interfaces[0], samName, implMethod, captured));
    }

    private static final class LambdaInvocationHandler implements InvocationHandler {
        private final Class<?> samInterface;
        private final String samName;
        private final MethodHandle implMethod;
        private final Object[] captured;

        LambdaInvocationHandler(Class<?> samInterface, String samName, MethodHandle implMethod,
                                Object[] captured) {
            this.samInterface = samInterface;
            this.samName = samName;
            this.implMethod = implMethod;
            this.captured = captured;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.isDefault()) {
                // an accessible Method skips the special caller check
                method.setAccessible(true);
                return MethodHandles.Lookup.IMPL_LOOKUP
                        .unreflectSpecial(method, method.getDeclaringClass())
                        .bindTo(proxy)
                        .invokeWithArguments(args == null ? new Object[0] : args);
            }
            if (method.getName().equals(samName)) {
                if (args == null || args.length == 0) {
                    return implMethod.invokeWithArguments(captured);
                }
                Object[] all = Arrays.copyOf(captured, captured.length + args.length);
                System.arraycopy(args, 0, all, captured.length, args.length);
                return implMethod.invokeWithArguments(all);
            }
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return samInterface.getName() + "$$Lambda@"
                            + Integer.toHexString(System.identityHashCode(proxy));
                default:
                    throw new AbstractMethodError(method.toString());
            }
        }
    }
}
