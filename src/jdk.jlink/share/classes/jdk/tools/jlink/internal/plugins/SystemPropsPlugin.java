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

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.InsnList;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.tools.jlink.internal.ConstantInterpreter;
import jdk.tools.jlink.internal.RemoveDeadCodeAdapter;
import jdk.tools.jlink.internal.StringValue;
import jdk.tools.jlink.internal.TransformationEntry;
import jdk.tools.jlink.plugin.PluginException;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolBuilder;
import jdk.tools.jlink.plugin.ResourcePoolEntry;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static jdk.internal.org.objectweb.asm.ClassReader.EXPAND_FRAMES;

public final class SystemPropsPlugin extends AbstractPlugin {
    public static final String NAME = "system-props";
    Properties knownProperties = new Properties();
    private final String REPORT_FILE_NAME = "sys-props-report.txt";
    private final FileWriter reportWriter;

    public SystemPropsPlugin() {
        super(NAME);
        try {
            reportWriter = new FileWriter(REPORT_FILE_NAME);
            SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
            Date date = new Date();
            reportWriter.write(String.format("------------ Class.forName Plugin Transformation " +
                    "Report generated On %s ------------\n", formatter.format(date)));

        } catch (IOException e) {
            throw new PluginException(e);
        }
    }

    private boolean modifyInstructions(LdcInsnNode ldc, InsnList il, MethodInsnNode min, String key) {
        String value = knownProperties.getProperty(key);
        if (value != null) {
            LdcInsnNode ldcInsn = new LdcInsnNode(value);
            //il.remove(ldc);
            il.set(min, ldcInsn);
            return true;
        }
        return false;
    }

    private ResourcePoolEntry transform(ResourcePoolEntry resource) {
        byte[] inBytes = resource.contentBytes();
        ClassReader cr = new ClassReader(inBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        List<MethodNode> ms = cn.methods;
        boolean modified = false;
        LdcInsnNode ldc;
        String moduleName = resource.moduleName();
        String path = resource.path();

        for (MethodNode mn : ms) {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new ConstantInterpreter());
            try {
                analyzer.analyze(cn.name, mn);
            } catch (AnalyzerException e) {
                throw new PluginException(e);
            }
            InsnList il = mn.instructions;
            int instructionIndex = 0;
            Frame<BasicValue>[] frames = analyzer.getFrames();
            ListIterator<AbstractInsnNode> iterator = il.iterator();
            while (iterator.hasNext()) {
                AbstractInsnNode insn = iterator.next();
                if (insn instanceof MethodInsnNode min &&
                        min.getOpcode() == Opcodes.INVOKESTATIC &&
                        min.name.equals("getProperty") &&
                        min.owner.equals("java/lang/System") &&
                        (min.desc.equals("(Ljava/lang/String;)Ljava/lang/String;") ||
                        min.desc.equals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))) {
                    boolean isTransformed = false;
                    int callIndex = il.indexOf(insn);
                    TransformationEntry entry = new TransformationEntry(path, mn.name, callIndex, moduleName);
                    BasicValue arg = getStackValue(instructionIndex, 0, frames);
                    if (arg instanceof StringValue value && value.getContents() != null) {
                        ldc = value.getLdcNode();
                        String key = value.getContents();
                        entry.addParameter(key);
                        /* transform bytecode */
                        isTransformed = modifyInstructions(ldc, il, min, key);
                        if (isTransformed) {
                            modified = true;
                        }
                    }
                    if (!isTransformed) {
                        if (arg != null && !(arg instanceof StringValue)) {
                            entry.addParameter("<Runtime Value>");
                        }
                        try {
                            reportWriter.write(entry.toString());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
                instructionIndex++;
            }
        }

        if (modified) {
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return resource.copyWithContent(cw.toByteArray());
        }

        return resource;
    }

    private BasicValue getStackValue(int instructionIndex, int frameIndex, Frame<BasicValue>[] frames) {
        Frame<BasicValue> f = frames[instructionIndex];
        if (f == null) {
            return null;
        }
        int top = f.getStackSize() - 1;
        return frameIndex <= top ? f.getStack(top - frameIndex) : null;
    }

    @Override
    public ResourcePool transform(ResourcePool in, ResourcePoolBuilder out) {
        Objects.requireNonNull(in);
        Objects.requireNonNull(out);

        in.entries().forEach(resource -> {
            String path = resource.path();
            if (path.endsWith(".class") && !path.endsWith("/module-info.class")) {
                out.add(transform(resource));
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
    public boolean hasArguments() {
        return true;
    }

    private void initKnownProperties(Path propFile) {
        try (InputStream input = new FileInputStream(propFile.toString())) {
            knownProperties.load(input);
            knownProperties.list(System.out);
        } catch (FileNotFoundException e) {
            throw new PluginException("properties file " + propFile + " not found");
        } catch (IOException e) {
            throw new PluginException("error in reading properties file " + propFile);
        }
    }

    @Override
    public void configure(Map<String, String> config) {
        if (!config.containsKey(NAME)) {
            throw new PluginException("properties file not specified as plugin argument");
        }
        String propFile = config.get(NAME);
        Path propFilePath = Paths.get(propFile);
        initKnownProperties(propFilePath);
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
}
