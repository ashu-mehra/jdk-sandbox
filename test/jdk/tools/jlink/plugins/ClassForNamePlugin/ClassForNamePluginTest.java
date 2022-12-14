/*
 * Copyright (c) 2022 Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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


import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.tree.*;
import jdk.test.lib.compiler.CompilerUtils;
import jdk.test.lib.util.FileUtils;
import jdk.tools.jlink.internal.ResourcePoolManager;
import jdk.tools.jlink.internal.plugins.ClassForNamePlugin;
import jdk.tools.jlink.plugin.ResourcePool;
import jdk.tools.jlink.plugin.ResourcePoolEntry;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import static jdk.internal.org.objectweb.asm.Opcodes.*;
import static jdk.test.lib.Asserts.assertTrue;
import static jdk.test.lib.process.ProcessTools.executeProcess;
import static org.testng.Assert.*;

import static jdk.internal.org.objectweb.asm.ClassReader.SKIP_FRAMES;


/**
 * @test
 * @summary Test the --class-for-name plugin
 * @author Sonia Zaldana Calles
 * @library /test/lib
 * @compile ClassForNamePluginTest.java
 * @modules jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 *          jdk.jlink/jdk.tools.jlink.plugin
 *          java.base/jdk.internal.org.objectweb.asm
 *          java.base/jdk.internal.org.objectweb.asm.tree
 *          java.base/jdk.internal.org.objectweb.asm.tree.analysis
 * @run testng/othervm -ea -esa ClassForNamePluginTest
 */

public class ClassForNamePluginTest {

    private final String CLASS_NOT_FOUND_EXCEPTION = "java/lang/ClassNotFoundException";
    private final String PLUGIN_NAME = "class-for-name";
    private static final String MODULE_NAME = "mymodule";
    private static final String IMPOSSIBLE_TRANSFORMATION_FILE = "ImpossibleTransformation.class";
    private static final String DATAFLOW_FILE = "Dataflow.class";
    private static final String TEST_SRC = System.getProperty("test.src");
    private static final Path SRC_DIR = Paths.get(TEST_SRC, "src");
    private static final Path MODS_DIR = Paths.get("mods");
    private static final Path IMAGE = Paths.get("image");
    private static final Path EXTRACT = Paths.get("extract");
    private static final String JAVA_HOME = System.getProperty("java.home");
    private static final String MAIN_MID = "mymodule/mypackage.ClassForNameTest";
    static final String MODULE_PATH = Paths.get(JAVA_HOME, "modsPath").toString()
            + File.pathSeparator + MODS_DIR.toString();

    @BeforeTest
    public void setup() throws Throwable {
        Path moduleSource = SRC_DIR.resolve(MODULE_NAME);
        assertTrue(CompilerUtils.compile(moduleSource, MODS_DIR,
                "--module-source-path", SRC_DIR.toString(),
                "--add-exports", "java.base/jdk.internal.module=" + MODULE_NAME,
                "--add-exports", "java.base/jdk.internal.org.objectweb.asm=" + MODULE_NAME));

        if (Files.exists(IMAGE) || Files.exists(EXTRACT)) {
            throw new AssertionError("Directories should have been cleaned up in tear down");
        }

        createImage("module", IMAGE, MODULE_NAME);

        Path modules = IMAGE.resolve("lib").resolve("modules");
        assertTrue(executeProcess("jimage", EXTRACT.toString(),
                "--dir", EXTRACT.toString(), modules.toString())
                .outputTo(System.out)
                .errorTo(System.out)
                .getExitValue() == 0);
    }

    @AfterTest
    public void tearDown() {
        if (Files.exists(IMAGE)) FileUtils.deleteFileTreeUnchecked(IMAGE);
        if (Files.exists(EXTRACT)) FileUtils.deleteFileTreeUnchecked(EXTRACT);
    }

    @Test
    public void testRunTransformedClass() throws Throwable {
        Path java = IMAGE.resolve("bin").resolve("java");
        assertTrue(executeProcess(java.toString(),
                "-m", MAIN_MID)
                .outputTo(System.out)
                .errorTo(System.out)
                .getExitValue() == 0);

    }

    /**
     * Verifies the correct ldc string value is carried through the Class.forName transformation
     * i.e. the ldc string corresponding to the Class.forName call.
     */
    @Test
    public void testCarryingCorrectLdc() throws Throwable, Exception {

        generateImpossibleTransformationClassFile();

        Path path = Paths.get(IMPOSSIBLE_TRANSFORMATION_FILE);
        byte[] arr = Files.readAllBytes(path);
        ResourcePoolManager resourcesMgr = new ResourcePoolManager();
        ResourcePoolEntry resource = ResourcePoolEntry.create("/test/" + IMPOSSIBLE_TRANSFORMATION_FILE, arr);
        resourcesMgr.add(resource);

        ClassForNamePlugin plugin = new ClassForNamePlugin();
        Map<String, String> prop = new HashMap<>();
        prop.put(plugin.getName(), "global");
        plugin.configure(prop);

        ResourcePoolManager resultMgr = new ResourcePoolManager();
        ResourcePool resPool = plugin.transform(resourcesMgr.resourcePool(),
                resultMgr.resourcePoolBuilder());

        resPool.entries()
                .forEach(r -> {
                    byte[] inBytes = r.contentBytes();
                    ClassReader cr = new ClassReader(inBytes);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, SKIP_FRAMES);


                    for (MethodNode mn : cn.methods) {
                        if (mn.name.equals("test")) {
                            
                            for (AbstractInsnNode insn : mn.instructions) {
                                if (insn instanceof MethodInsnNode) {

                                    MethodInsnNode methodInsn = (MethodInsnNode) insn;
                                    if (methodInsn.owner.equals("java/lang/invoke/MethodHandles$Lookup") &&
                                            methodInsn.name.equals("ensureInitialized") &&
                                            methodInsn.desc.equals("(Ljava/lang/Class;)Ljava/lang/Class;") &&
                                            methodInsn.getOpcode() == INVOKEVIRTUAL) {

                                        throw new AssertionError("Transformation shouldn't have happened. " +
                                                "Parameter at top of stack is only known at runtime - not statically known.");
                                    }
                                }
                            }
                        }
                    }
                });
    }

    /**
     * Generates a class file where the parameter for Class.forName is only known at runtime.
     * Bytecode reads roughly as follows
     * test(String s) {
     *      ldc "mypackage.ClassForNameTest" // a valid class for the Class.forName operation
     *      aload 0 // loads S
     *      invokestatic  // Method java/lang/Class.forName:(Ljava/lang/String;)Ljava/lang/Class;
     * }
     */
    private static void generateImpossibleTransformationClassFile() throws Throwable {
        ClassWriter cw = new ClassWriter(0);
        MethodVisitor mv;

        cw.visit(49, ACC_PUBLIC + ACC_SUPER,
                "LdcClass",
                null,
                "java/lang/Object",
                null);

        cw.visitSource("LdcClass.java", null);

        {
            mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL,
                    "java/lang/Object",
                    "<init>",
                    "()V",
                    false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }

        {
            mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    "main",
                    "([Ljava/lang/String;)V",
                    null,
                    new String[] { "java/lang/Exception" });

            mv.visitLdcInsn("Garbage");
            mv.visitMethodInsn(INVOKESTATIC,
                    "LdcClass",
                    "test",
                    "(Ljava/lang/String;)V",
                    false);

            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }

        {
            mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    "test",
                    "(Ljava/lang/String;)V",
                    null,
                    new String[] { "java/lang/Exception" });

            mv.visitLdcInsn("mypackage.ClassForNameTest");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC,
                    "java/lang/Class",
                    "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;",
                    false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            mv.visitMaxs(2, 2);
            mv.visitEnd();

        }

        cw.visitEnd();

        File generated = new File(IMPOSSIBLE_TRANSFORMATION_FILE);
        generated.createNewFile();
        FileOutputStream os = new FileOutputStream(IMPOSSIBLE_TRANSFORMATION_FILE);
        os.write(cw.toByteArray());
        os.close();

        assertTrue(executeProcess("javap", "-c", "-verbose",
                IMPOSSIBLE_TRANSFORMATION_FILE)
                .outputTo(System.out)
                .errorTo(System.out)
                .getExitValue() == 0); // For debugging
    }


    /**
     * Verifies the transformation is not dependent on a specific sequence of bytecode instructions.
     * Accomplishes that by verifying transformation is successful as the value at the top of the
     * stack for Class.forName call is an ldc'd value.
     */
    @Test
    public void testDataFlowAnalysis() throws Throwable, Exception {

        generateDataFlowClassFile();

        Path path = Paths.get(DATAFLOW_FILE);
        byte[] arr = Files.readAllBytes(path);
        ResourcePoolManager resourcesMgr = new ResourcePoolManager();
        ResourcePoolEntry resource = ResourcePoolEntry.create("/test/" + DATAFLOW_FILE, arr);
        resourcesMgr.add(resource);

        ClassForNamePlugin plugin = new ClassForNamePlugin();
        Map<String, String> prop = new HashMap<>();
        prop.put(plugin.getName(), "global");
        plugin.configure(prop);

        ResourcePoolManager resultMgr = new ResourcePoolManager();
        ResourcePool resPool = plugin.transform(resourcesMgr.resourcePool(),
                resultMgr.resourcePoolBuilder());

        resPool.entries()
                .forEach(r -> {
                    byte[] inBytes = r.contentBytes();
                    ClassReader cr = new ClassReader(inBytes);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, SKIP_FRAMES);


                    for (MethodNode mn : cn.methods) {
                        if (mn.name.equals("test")) {
                            for (AbstractInsnNode insn : mn.instructions) {
                                if (insn instanceof MethodInsnNode) {

                                    MethodInsnNode methodInsn = (MethodInsnNode) insn;
                                    if (methodInsn.owner.equals("java/lang/Class") &&
                                            methodInsn.name.equals("forName") &&
                                            methodInsn.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;") &&
                                            methodInsn.getOpcode() == INVOKESTATIC) {

                                        throw new AssertionError("Failed to transform Class.forName call" +
                                                "despite existing data flow analysis.");
                                    }
                                }
                            }
                        }
                    }
                });
    }

    /**
     * Generates a class file with instructions between ldc and CNF call, ultimately leaving an ldc
     * value at the top of stack for the CNF call. Relevant bytecode reads roughly as follows:
     * test(String s) {
     *      ldc "mypackage.ClassForNameTest" // a valid class for the Class.forName operation
     *      aload 0 // loads S
     *      pop
     *      invokestatic  // Method java/lang/Class.forName:(Ljava/lang/String;)Ljava/lang/Class;
     * }
     */
    private static void generateDataFlowClassFile() throws Throwable {
        ClassWriter cw = new ClassWriter(0);
        MethodVisitor mv;

        cw.visit(49, ACC_PUBLIC + ACC_SUPER,
                "LdcClass",
                null,
                "java/lang/Object",
                null);

        cw.visitSource("LdcClass.java", null);

        {
            mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL,
                    "java/lang/Object",
                    "<init>",
                    "()V",
                    false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }

        {
            mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    "main",
                    "([Ljava/lang/String;)V",
                    null,
                    new String[] { "java/lang/Exception" });

            mv.visitLdcInsn("Garbage");
            mv.visitMethodInsn(INVOKESTATIC,
                    "LdcClass",
                    "test",
                    "(Ljava/lang/String;)V",
                    false);

            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }

        {
            mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    "test",
                    "(Ljava/lang/String;)V",
                    null,
                    new String[] { "java/lang/Exception" });

            mv.visitLdcInsn("mypackage.ClassForNameTest");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(POP);
            mv.visitMethodInsn(INVOKESTATIC,
                    "java/lang/Class",
                    "forName",
                    "(Ljava/lang/String;)Ljava/lang/Class;",
                    false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            mv.visitMaxs(2, 2);
            mv.visitEnd();

        }

        cw.visitEnd();

        File generated = new File(DATAFLOW_FILE);
        generated.createNewFile();
        FileOutputStream os = new FileOutputStream(DATAFLOW_FILE);
        os.write(cw.toByteArray());
        os.close();

        assertTrue(executeProcess("javap", "-c", "-verbose",
                DATAFLOW_FILE)
                .outputTo(System.out)
                .errorTo(System.out)
                .getExitValue() == 0); // For debugging
    }

    @Test
    public void testPluginLoaded() throws Exception {
        List<String> output =
                JLink.run("--list-plugins").output();
        if (output.stream().anyMatch(s -> s.contains(PLUGIN_NAME))) {
            System.out.println("DEBUG: " + PLUGIN_NAME + " plugin loaded as expected.");
        } else {
            throw new AssertionError("class-for-name plugin not in " +
                    "--list-plugins output.");
        }
    }

    @Test
    public void testConfigureUnknownOptions() throws Exception {
        Map<String, String> config = Map.of(
                ClassForNamePlugin.NAME, "foobar"
        );
        ClassForNamePlugin plugin = new ClassForNamePlugin();
        try {
            plugin.configure(config);
            throw new AssertionError("Should have thrown IAE for unknown options");
        } catch (IllegalArgumentException e) {
            System.out.println("DEBUG: Test threw IAE as expected for unknown options");
        }
    }

    @Test
    public void testUnreachableExceptionRemoval() throws Exception, Throwable {

        Path path = EXTRACT.resolve("mymodule").resolve("mypackage").resolve("ClassForNameTest.class");
        assertTrue(executeProcess("javap", "-c", "-verbose",
                path.toString())
                .outputTo(System.out)
                .errorTo(System.out)
                .getExitValue() == 0); // This is in place for debugging.

        byte[] arr = Files.readAllBytes(path);
        ResourcePoolManager resourcesMgr = new ResourcePoolManager();
        ResourcePoolEntry resource = ResourcePoolEntry.create("/" + path, arr);
        resourcesMgr.add(resource);


        /* Verify try catch block removal against transformed method nodes */
        resourcesMgr.resourcePool().entries()
                .forEach(r -> {
                    String resourcePath = r.path();

                    if (resourcePath.endsWith("ClassForNameTest.class")) {

                        byte[] inBytes = r.contentBytes();
                        ClassReader cr = new ClassReader(inBytes);
                        ClassNode cn = new ClassNode();
                        cr.accept(cn, SKIP_FRAMES);

                        for (MethodNode mn : cn.methods) {
                            switch (mn.name) {
                                case "simpleTryCatchRemovalTest":
                                    simpleTryCatchRemovalTest(mn);
                                    break;
                                case "jointExceptionTest":
                                    jointExceptionTest(mn, "JointExceptionTest");
                                    break;
                                case "multipleCatchBlockTest":
                                    jointExceptionTest(mn, "MultipleCatchBlockTest");
                                    break;
                                case "simpleTryCatchFinallyRemovalTest":
                                    simpleTryCatchFinallyRemovalTest(mn);
                                    break;
                                case "nestedAllCallsTransformedTest":
                                    nestedAllCallsTransformedTest(mn);
                                    break;
                                case "nestedAllFinallyCallsTransformedTest":
                                    nestedAllFinallyCallsTransformedTest(mn);
                                    break;
                                case "nestedSomeCallsTransformedTest":
                                    nestedSomeCallsTransformedTest(mn);
                                    break;
                                case "preserveExceptionTest":
                                    preserveExceptionTest(mn);
                                    break;
                            }
                        }
                    }
                });
    }

    private void createImage(String mode, Path outputDir, String... modules) throws Throwable {
        assertTrue(JLink.JLINK_TOOL.run(System.out, System.out,
                "--output", outputDir.toString(),
                "--add-modules", Arrays.stream(modules).collect(Collectors.joining(",")),
                "--module-path", MODULE_PATH,
                "--class-for-name", mode) == 0);
    }

    private String expectedVsActualMessage(int expected, int actual) {
        return " Expected exceptions: " + expected + ". Actual exceptions: " + actual;
    }

    private void simpleTryCatchRemovalTest(MethodNode mn) {
        assert(mn.tryCatchBlocks.isEmpty()) : "Failure to remove simple try catch block."
                + expectedVsActualMessage(0, mn.tryCatchBlocks.size());
    }

    private void jointExceptionTest(MethodNode mn, String test) {
        assert(mn.tryCatchBlocks.size() == 1
                && ! mn.tryCatchBlocks.get(0).type.equals(CLASS_NOT_FOUND_EXCEPTION)) :
                test + ": Failure to remove exception in joint exception handler."
                        + expectedVsActualMessage(1, mn.tryCatchBlocks.size());
    }

    private void simpleTryCatchFinallyRemovalTest(MethodNode mn) {
        assert(mn.tryCatchBlocks.size() == 1 && mn.tryCatchBlocks.get(0).type == null): "Failure to " +
                "remove ClassNotFound exception" +
                "in simple try catch finally block." + expectedVsActualMessage(1, mn.tryCatchBlocks.size());
    }

    private void nestedAllCallsTransformedTest(MethodNode mn) {
        assert(mn.tryCatchBlocks.size() == 0) : "Exceptions for nested transformed calls were not removed." +
                expectedVsActualMessage(0, mn.tryCatchBlocks.size());
    }

    private void nestedAllFinallyCallsTransformedTest(MethodNode mn) {
        assert(mn.tryCatchBlocks.size() == 2) : "Exceptions for nested try catch finally calls were not removed." +
                expectedVsActualMessage(2, mn.tryCatchBlocks.size());
    }

    private void nestedSomeCallsTransformedTest(MethodNode mn) {
        assert(mn.tryCatchBlocks.size() == 1) : " Exceptions removed in nested Class.forName calls where not all " +
                "are transformed." + expectedVsActualMessage(1, mn.tryCatchBlocks.size());
    }

    private void preserveExceptionTest(MethodNode mn) {
        assert(mn.tryCatchBlocks.size() == 1
                && mn.tryCatchBlocks.get(0).type.equals(CLASS_NOT_FOUND_EXCEPTION)) : " Exception was " +
                "removed when it should be preserved as not all calls are transformed" +
                expectedVsActualMessage(1, mn.tryCatchBlocks.size());
    }

    static class JLink {
        static final ToolProvider JLINK_TOOL = ToolProvider.findFirst("jlink")
                .orElseThrow(() ->
                        new RuntimeException("jlink tool not found")
                );

        static JLink run(String... options) {
            JLink jlink = new JLink();
            if (jlink.execute(options) != 0) {
                throw new AssertionError("Jlink expected to exit with 0 return code");
            }
            return jlink;
        }

        final List<String> output = new ArrayList<>();
        private int execute(String... options) {
            System.out.println("jlink " +
                    Stream.of(options).collect(Collectors.joining(" ")));

            StringWriter writer = new StringWriter();
            PrintWriter pw = new PrintWriter(writer);
            int rc = JLINK_TOOL.run(pw, pw, options);
            System.out.println(writer.toString());
            Stream.of(writer.toString().split("\\v"))
                    .map(String::trim)
                    .forEach(output::add);
            return rc;
        }

        List<String> output() {
            return output;
        }
    }

}