/*
 * Copyright (c) 2016, 2022, Oracle and/or its affiliates. All rights reserved.
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
package jdk.jshell.execution;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import jdk.classfile.Classfile;
import jdk.classfile.ClassTransform;
import jdk.classfile.instruction.BranchInstruction;

/**
 * An implementation of {@link jdk.jshell.spi.ExecutionControl} which executes
 * in the same JVM as the JShell-core.
 *
 * @author Grigory Ptashko
 * @since 9
 */
public class LocalExecutionControl extends DirectExecutionControl {


    private static final ClassDesc CD_LocalExecutionControl = ClassDesc.ofDescriptor(LocalExecutionControl.class.descriptorString());
    private static final MethodTypeDesc MTD_void = MethodTypeDesc.ofDescriptor("()V");

    private static volatile boolean allStop = false;

    public static void stopCheck() {
        if (allStop) throw new ThreadDeath();
    }

    private final Object STOP_LOCK = new Object();
    private boolean userCodeRunning = false;
    private ThreadGroup execThreadGroup;

    /**
     * Creates an instance, delegating loader operations to the specified
     * delegate.
     *
     * @param loaderDelegate the delegate to handle loading classes
     */
    public LocalExecutionControl(LoaderDelegate loaderDelegate) {
        super(loaderDelegate);
    }

    /**
     * Create an instance using the default class loading.
     */
    public LocalExecutionControl() {
    }

    @Override
    public void load(ClassBytecodes[] cbcs)
            throws ClassInstallException, NotImplementedException, EngineTerminationException {
        super.load(Stream.of(cbcs).map(cbc ->
                new ClassBytecodes(cbc.name(), Classfile.parse(cbc.bytecodes())
                        .transform(ClassTransform.transformingMethodBodies((cob, coe) -> {
                            if (coe instanceof BranchInstruction br)
                                cob.invokestatic(CD_LocalExecutionControl, "stopCheck", MTD_void);
                            cob.with(coe);
                        })))).toArray(ClassBytecodes[]::new));
    }

    @Override
    protected String invoke(Method doitMethod) throws Exception {
        execThreadGroup = new ThreadGroup("JShell process local execution");

        AtomicReference<InvocationTargetException> iteEx = new AtomicReference<>();
        AtomicReference<IllegalAccessException> iaeEx = new AtomicReference<>();
        AtomicReference<NoSuchMethodException> nmeEx = new AtomicReference<>();
        AtomicReference<Boolean> stopped = new AtomicReference<>(false);

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (e instanceof InvocationTargetException) {
                if (e.getCause() instanceof ThreadDeath) {
                    stopped.set(true);
                } else {
                    iteEx.set((InvocationTargetException) e);
                }
            } else if (e instanceof IllegalAccessException) {
                iaeEx.set((IllegalAccessException) e);
            } else if (e instanceof NoSuchMethodException) {
                nmeEx.set((NoSuchMethodException) e);
            } else if (e instanceof ThreadDeath) {
                stopped.set(true);
            }
        });

        final Object[] res = new Object[1];
        Thread snippetThread = new Thread(execThreadGroup, () -> {
            try {
                res[0] = doitMethod.invoke(null, new Object[0]);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof ThreadDeath) {
                    stopped.set(true);
                } else {
                    iteEx.set(e);
                }
            } catch (IllegalAccessException e) {
                iaeEx.set(e);
            } catch (ThreadDeath e) {
                stopped.set(true);
            }
        });

        snippetThread.start();
        Thread[] threadList = new Thread[execThreadGroup.activeCount()];
        execThreadGroup.enumerate(threadList);
        for (Thread thread : threadList) {
            if (thread != null) {
                thread.join();
            }
        }

        if (stopped.get()) {
            throw new StoppedException();
        }

        if (iteEx.get() != null) {
            throw iteEx.get();
        } else if (nmeEx.get() != null) {
            throw nmeEx.get();
        } else if (iaeEx.get() != null) {
            throw iaeEx.get();
        }

        return valueString(res[0]);
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void stop() throws EngineTerminationException, InternalException {
        synchronized (STOP_LOCK) {
            if (!userCodeRunning) {
                return;
            }
            if (execThreadGroup == null) {
                throw new InternalException("Process-local code snippets thread group is null. Aborting stop.");
            }
            allStop = true;
        }
    }

    @Override
    protected void clientCodeEnter() {
        synchronized (STOP_LOCK) {
            userCodeRunning = true;
            allStop = false;
        }
    }

    @Override
    protected void clientCodeLeave() {
        synchronized (STOP_LOCK) {
            userCodeRunning = false;
        }
    }

}
