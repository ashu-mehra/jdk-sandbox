/*
 * Copyright (c) 2016, 2022 Oracle and/or its affiliates. All rights reserved.
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
package jdk.tools.jlink.internal.plugins;

import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.*;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.tools.jlink.internal.*;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolModule;
import jdk.tools.jlink.plugin.*;

import static jdk.internal.org.objectweb.asm.ClassReader.*;

public final class ClassForNamePlugin implements Plugin {
    public static final String NAME = "class-for-name";
    private static final String GLOBAL = "global";
    private static final String MODULE = "module";
    private static final String CLASS_NOT_FOUND_EXCEPTION = "java/lang/ClassNotFoundException";
    private boolean isGlobalTransformation;
    private final DependencyPluginFactory factory;
    private final String REPORT_FILE_NAME = "cfn_report.txt";
    private final FileWriter reportWriter;

    public ClassForNamePlugin() {
        this.factory = new DependencyPluginFactory();

        try {
            reportWriter = new FileWriter(REPORT_FILE_NAME);
            SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
            Date date = new Date();
            reportWriter.write(String.format("------------ Class.forName Plugin Transformation " +
                    "Report generated On %s ------------\n", formatter.format(date)));

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private static String binaryClassName(String path) {
        return path.substring(path.indexOf('/', 1) + 1,
                path.length() - ".class".length());
    }

    private static int getAccess(ResourcePoolEntry resource) {
        ClassReader cr = new ClassReader(resource.contentBytes());

        return cr.getAccess();
    }

    private static String getPackage(String binaryName) {
        int index = binaryName.lastIndexOf("/");

        return index == -1 ? "" : binaryName.substring(0, index);
    }

    @Override
    public List<Plugin> requiredPlugins() {
        return factory.create();
    }

    private void updateHandlerMap(int index, Map<TryCatchBlockNode, TryCatchState> handlers,
                                  boolean isTransformed, InsnList il) {

        TryCatchState tightestHandler = null;
        int tightestStart = -1;
        int tightestEnd = -1;
        for (TryCatchBlockNode tryCatch : handlers.keySet()) {
            if (tryCatch.type != null && tryCatch.type.equals(CLASS_NOT_FOUND_EXCEPTION)) {
                TryCatchState tryCatchState = handlers.get(tryCatch);
                int currStart = il.indexOf(tryCatch.start);
                int currEnd = il.indexOf(tryCatch.end);
                if (currStart <= index && currEnd >= index) {
                    if (tightestHandler == null) {
                        tightestHandler = tryCatchState;
                        tightestStart = currStart;
                        tightestEnd = currEnd;
                    } else {
                        if (currStart > tightestStart
                                && currEnd < tightestEnd) {
                            tightestHandler = tryCatchState;
                            tightestStart = currStart;
                            tightestEnd = currEnd;
                        }
                    }
                }
            }
        }

        if (tightestHandler != null) {
            if (isTransformed) {
                tightestHandler.setTransformedCFN();
            } else {
                tightestHandler.setNotTransformedCFN();
            }
        }
    }

    private void modifyInstructions(LdcInsnNode ldc, InsnList il, MethodInsnNode min, String thatClassName) {


        Type type = Type.getObjectType(thatClassName);
        MethodInsnNode lookupInsn = new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "lookup","()Ljava/lang/invoke/MethodHandles$Lookup;");
        LdcInsnNode ldcInsn = new LdcInsnNode(type);
        MethodInsnNode ensureInitializedInsn = new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandles$Lookup",
                "ensureInitialized", "(Ljava/lang/Class;)Ljava/lang/Class;");

        il.remove(ldc);
        il.set(min, lookupInsn);
        il.insert(lookupInsn, ldcInsn);
        il.insert(ldcInsn, ensureInitializedInsn);
    }
    private ResourcePoolEntry transform(ResourcePoolEntry resource, ResourcePool pool) {
        byte[] inBytes = resource.contentBytes();
        ClassReader cr = new ClassReader(inBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        List<MethodNode> ms = cn.methods;
        boolean modified = false;
        LdcInsnNode ldc;
        String thisPackage = getPackage(binaryClassName(resource.path()));
        String moduleName = resource.moduleName();
        String path = resource.path();

        for (MethodNode mn : ms) {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new ConstantInterpreter());
            try {
                analyzer.analyze(cn.name, mn);
            } catch (AnalyzerException e) {
                throw new RuntimeException(e);
            }

            InsnList il = mn.instructions;
            /* Map of exception handlers and their covered ranges */
            Map<TryCatchBlockNode, TryCatchState> handlers = new HashMap<>();
            for (TryCatchBlockNode tryCatch : mn.tryCatchBlocks) {
                handlers.put(tryCatch, new TryCatchState());
            }

            int instructionIndex = 0;
            Frame<BasicValue>[] frames = analyzer.getFrames();
            ListIterator<AbstractInsnNode> iterator = il.iterator();
            while (iterator.hasNext()) {
                AbstractInsnNode insn = iterator.next();
                if (insn instanceof MethodInsnNode ) {
                    MethodInsnNode min = (MethodInsnNode)insn;
                    if (min.getOpcode() == Opcodes.INVOKESTATIC &&
                            min.name.equals("forName") &&
                            min.owner.equals("java/lang/Class")) {
                        boolean isTransformed = false;
                        int callIndex = il.indexOf(insn);
                        TransformationEntry entry = new TransformationEntry(path, mn.name, callIndex, moduleName);

                        if (min.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                            BasicValue arg;
                            try {
                                arg = getStackValue(instructionIndex, 0, frames);

                            } catch (AnalyzerException e) {
                                throw new RuntimeException(e);
                            }
                            if (arg instanceof StringValue value && value.getContents() != null) { // ((StringValue) arg)
                                ldc = value.getLdcNode();
                                String thatClassName = value.getContents().replaceAll("\\.", "/");
                                entry.addParameter(thatClassName);
                                if (isGlobalTransformation) {
                                    /* Blindly transform bytecode */
                                    modifyInstructions(ldc, il, min, thatClassName);
                                    modified = true;
                                    isTransformed = true;
                                } else {
                                    /* Transform calls for classes within the same module */
                                    Optional<ResourcePoolEntry> thatClass =
                                            pool.findEntryInContext(thatClassName + ".class", resource);

                                    if (thatClass.isPresent()) {
                                        int thatAccess = getAccess(thatClass.get());
                                        String thatPackage = getPackage(thatClassName);

                                        if ((thatAccess & Opcodes.ACC_PRIVATE) != Opcodes.ACC_PRIVATE &&
                                                ((thatAccess & Opcodes.ACC_PUBLIC) == Opcodes.ACC_PUBLIC ||
                                                        thisPackage.equals(thatPackage))) {
                                            modifyInstructions(ldc, il, min, thatClassName);
                                            modified = true;
                                            isTransformed = true;
                                        }
                                    } else {
                                        /* Check module graph to see if class is accessible */
                                        ResourcePoolModule targetModule = getTargetModule(pool, thatClassName);
                                        if (targetModule != null
                                                && ModuleGraphPlugin.isAccessible(thatClassName,
                                                resource.moduleName(), targetModule.name())) {
                                            modifyInstructions(ldc, il, min, thatClassName);
                                            modified = true;
                                            isTransformed = true;
                                        }
                                    }
                                }
                                updateHandlerMap(callIndex, handlers, isTransformed, il);
                            }
                            if (! isTransformed) {
                                if (arg != null && ! (arg instanceof StringValue)) {
                                    entry.addParameter("<Runtime Value>");
                                }
                                try {
                                    reportWriter.write(entry.toString());
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        } else if (min.desc.equals("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;")) {
                            // using the Class.forName with multiple trparameters.
                            for (int k = 2; k > -1; k--) {
                                BasicValue otherArg;
                                try {
                                    otherArg = getStackValue(instructionIndex, k, frames);
                                    if (otherArg instanceof BooleanValue value) {
                                        entry.addParameter(String.valueOf(value.getValue()));
                                    } else if (otherArg instanceof StringValue value) {
                                        entry.addParameter(value.getContents());
                                    } else {
                                        entry.addParameter("<Runtime Value>");
                                    }
                                } catch (AnalyzerException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            try {
                                reportWriter.write(entry.toString());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
                instructionIndex++;
            }
            removeUnreachableExceptionHandlers(handlers, mn);
        }

        if (modified) {
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);

            ClassReader cr2 = new ClassReader(cw.toByteArray());
            ClassWriter cw2 = new ClassWriter(cr2, 0);
            ClassAdaptor deadCodeAdaptor = new ClassAdaptor(cw2);
            cr2.accept(deadCodeAdaptor, EXPAND_FRAMES);

            return resource.copyWithContent(cw2.toByteArray());
        }

        return resource;
    }

    private BasicValue getStackValue(int instructionIndex, int frameIndex, Frame<BasicValue>[] frames) throws AnalyzerException {
        Frame<BasicValue> f = frames[instructionIndex];
        if (f == null) {
            return null;
        }
        int top = f.getStackSize() - 1;
        return frameIndex <= top ? f.getStack(top - frameIndex) : null;
    }

    private static void removeUnreachableExceptionHandlers(Map<TryCatchBlockNode, TryCatchState> handlers, MethodNode mn) {

        for (TryCatchBlockNode tryCatch : handlers.keySet()) {
            TryCatchState state = handlers.get(tryCatch);
            if (tryCatch.type != null && tryCatch.type.equals(CLASS_NOT_FOUND_EXCEPTION)
                    && state.allCallsTransformed()) {
                mn.tryCatchBlocks.remove(tryCatch);
            }
        }
    }

    private ResourcePoolModule getTargetModule(ResourcePool pool, String givenClass) {
        ResourcePoolModule targetModule = pool.moduleView().modules()
                .filter(m -> m.findEntry(givenClass.replace(".", "/") + ".class").isPresent())
                .findFirst().orElse(null);
        return targetModule;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        Objects.requireNonNull(in);
        Objects.requireNonNull(out);

        in.entries()
                .forEach(resource -> {
                    String path = resource.path();

                    if (path.endsWith(".class") && !path.endsWith("/module-info.class")) {
                        out.add(transform(resource, in));
                    } else {
                        out.add(resource);
                    }
                });

        try {
            reportWriter.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return out.build();
    }
    @Override
    public Category getType() {
        return Category.TRANSFORMER;
    }
    @Override
    public boolean hasArguments() {
        return true;
    }

    @Override
    public String getDescription() {
        return PluginsResourceBundle.getDescription(NAME);
    }

    @Override
    public String getArgumentsDescription() {
        return PluginsResourceBundle.getArgument(NAME);
    }

    @Override
    public void configure(Map<String, String> config) {
        String arg = config.get(getName());
        if (arg != null) {
            if (arg.equalsIgnoreCase(GLOBAL)) {
                isGlobalTransformation = true;
            } else if (! arg.equalsIgnoreCase(MODULE)){
                throw new IllegalArgumentException(getName() + ": " + arg);
            }
        }
    }

    class TryCatchState {
        boolean transformedCFN;
        boolean notTransformedCFN;
        void setTransformedCFN() {
            transformedCFN = true;
        }
        void setNotTransformedCFN() {
            notTransformedCFN = true;
        }
        boolean allCallsTransformed() {
            return transformedCFN && ! notTransformedCFN;
        }
    }

    public class ClassAdaptor extends ClassVisitor {

        String owner;

        public ClassAdaptor(ClassWriter cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            cv.visit(version, access, name, signature, superName, interfaces);
            owner = name;
        }
        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                         String[] exceptions) {
            MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
            return new RemoveDeadCodeAdapter(owner, access, name, desc, mv);
        }
    }

    public interface PluginFactory {
        List<Plugin> create();
    }

    private static class DependencyPluginFactory implements PluginFactory {

        @Override
        public List<Plugin> create() {
            return List.of(PluginRepository.getPlugin("jdk-tools-jlink-internal-plugins-ModuleGraphPlugin",
                    ModuleLayer.boot()));
        }

    }
}
