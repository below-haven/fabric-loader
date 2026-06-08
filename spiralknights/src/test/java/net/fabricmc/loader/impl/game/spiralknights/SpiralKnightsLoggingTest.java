/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.game.spiralknights;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.loader.impl.game.spiralknights.hook.ConsoleLogMirrorHook;
import net.fabricmc.loader.impl.game.spiralknights.patch.ConsoleLogMirrorPatch;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.util.SystemProperties;

public class SpiralKnightsLoggingTest {
	private static final String ENTRYPOINT = "com.threerings.projectx.client.ProjectXApp";

	@TempDir
	Path tempDir;

	private PrintStream oldOut;
	private PrintStream oldErr;
	private String oldSpiralKnightsLogLevel;
	private String oldJavaLoggingConfigFile;
	private String oldJavaLoggingConfigClass;
	private String oldNoLogRedir;

	@BeforeEach
	public void setUp() {
		oldOut = System.out;
		oldErr = System.err;
		oldSpiralKnightsLogLevel = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL);
		oldJavaLoggingConfigFile = System.getProperty("java.util.logging.config.file");
		oldJavaLoggingConfigClass = System.getProperty("java.util.logging.config.class");
		oldNoLogRedir = System.getProperty("no_log_redir");
	}

	@AfterEach
	public void tearDown() throws IOException {
		System.setOut(oldOut);
		System.setErr(oldErr);
		restore(SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL, oldSpiralKnightsLogLevel);
		restore("java.util.logging.config.file", oldJavaLoggingConfigFile);
		restore("java.util.logging.config.class", oldJavaLoggingConfigClass);
		restore("no_log_redir", oldNoLogRedir);
		LogManager.getLogManager().readConfiguration();
	}

	@Test
	public void logConfigAppliesRequestedLevel() throws IOException {
		Path config = tempDir.resolve("logging.properties");
		Files.write(config, Arrays.asList(
				"handlers= java.util.logging.ConsoleHandler",
				".level= INFO",
				"java.util.logging.ConsoleHandler.level = INFO",
				"java.util.logging.ConsoleHandler.formatter = java.util.logging.SimpleFormatter"));
		System.setProperty("java.util.logging.config.file", config.toString());
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL, "FINE");

		SpiralKnightsLogConfig.apply();

		Logger rootLogger = LogManager.getLogManager().getLogger("");
		Assertions.assertEquals(Level.FINE, rootLogger.getLevel());
		Assertions.assertTrue(hasHandlerWithLevel(rootLogger, Level.FINE));
	}

	@Test
	public void consoleLogMirrorTeesStreamsAndMirrorsJul() {
		ByteArrayOutputStream logOutBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream logErrBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream realOutBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream realErrBytes = new ByteArrayOutputStream();

		PrintStream logOut = new PrintStream(logOutBytes, true);
		PrintStream logErr = new PrintStream(logErrBytes, true);
		PrintStream realOut = new PrintStream(realOutBytes, true);
		PrintStream realErr = new PrintStream(realErrBytes, true);

		System.setOut(logOut);
		System.setErr(logErr);
		prepareRootLogger();

		installConsoleLogMirror(realOut, realErr);
		System.out.print("out");
		System.err.print("err");
		installConsoleLogMirror(realOut, realErr);
		System.out.print("-again");
		System.err.print("-again");

		Logger.getLogger("spiralknights-test-" + UUID.randomUUID()).info("jul-message");

		Assertions.assertEquals("out-again", logOutBytes.toString());
		Assertions.assertEquals("out-again", realOutBytes.toString());
		Assertions.assertTrue(logErrBytes.toString().contains("err-again"));
		Assertions.assertTrue(realErrBytes.toString().contains("err-again"));
		Assertions.assertTrue(realErrBytes.toString().contains("jul-message"));
	}

	@Test
	public void consoleLogMirrorSkipsWhenGameLogRedirectIsDisabled() {
		ByteArrayOutputStream logOutBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream realOutBytes = new ByteArrayOutputStream();
		System.setOut(new PrintStream(logOutBytes, true));
		System.setErr(new PrintStream(new ByteArrayOutputStream(), true));
		System.setProperty("no_log_redir", "true");

		installConsoleLogMirror(
				new PrintStream(realOutBytes, true),
				new PrintStream(new ByteArrayOutputStream(), true));
		System.out.print("out");

		Assertions.assertEquals("out", logOutBytes.toString());
		Assertions.assertEquals("", realOutBytes.toString());
	}

	@Test
	public void patchInjectsConsoleLogMirrorAfterLoggingInit() {
		ClassNode entrypointClass = createEntrypointClass();
		FabricLauncher launcher = mock();
		when(launcher.getEntrypoint()).thenReturn(ENTRYPOINT);
		final ClassNode[] emittedClass = new ClassNode[1];

		new ConsoleLogMirrorPatch().process(launcher,
				name -> ENTRYPOINT.equals(name) ? entrypointClass : null,
				node -> emittedClass[0] = node);

		Assertions.assertSame(entrypointClass, emittedClass[0]);

		MethodNode mainMethod = findMain(entrypointClass);
		MethodInsnNode loggingInit = null;
		MethodInsnNode hook = null;

		for (AbstractInsnNode insn : mainMethod.instructions) {
			if (insn instanceof MethodInsnNode) {
				MethodInsnNode methodInsn = (MethodInsnNode) insn;

				if (methodInsn.owner.equals("com/threerings/util/ToolUtil")) {
					loggingInit = methodInsn;
				} else if (methodInsn.owner.equals(ConsoleLogMirrorHook.INTERNAL_NAME)
						&& methodInsn.name.equals("installConsoleLogMirror")) {
					hook = methodInsn;
				}
			}
		}

		Assertions.assertNotNull(loggingInit);
		Assertions.assertNotNull(hook);
		Assertions.assertEquals(mainMethod.instructions.indexOf(loggingInit) + 1, mainMethod.instructions.indexOf(hook));
	}

	private static boolean hasHandlerWithLevel(Logger logger, Level level) {
		for (Handler handler : logger.getHandlers()) {
			if (level.equals(handler.getLevel())) {
				return true;
			}
		}

		return false;
	}

	private static void installConsoleLogMirror(PrintStream realOut, PrintStream realErr) {
		try {
			Method method = ConsoleLogMirrorHook.class.getDeclaredMethod("installConsoleLogMirror", PrintStream.class, PrintStream.class);
			method.setAccessible(true);
			method.invoke(null, realOut, realErr);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static void prepareRootLogger() {
		Logger rootLogger = LogManager.getLogManager().getLogger("");
		rootLogger.setLevel(Level.INFO);

		for (Handler handler : rootLogger.getHandlers()) {
			handler.setLevel(Level.INFO);

			if (handler.getFormatter() == null) {
				handler.setFormatter(new SimpleFormatter());
			}
		}
	}

	private static ClassNode createEntrypointClass() {
		ClassNode classNode = new ClassNode();
		classNode.version = Opcodes.V1_8;
		classNode.access = Opcodes.ACC_PUBLIC;
		classNode.name = "com/threerings/projectx/client/ProjectXApp";
		classNode.superName = "java/lang/Object";

		MethodNode main = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
		InsnList instructions = main.instructions;
		instructions.add(new LdcInsnNode("projectx.log"));
		instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/threerings/util/ToolUtil", "configureLog", "(Ljava/lang/String;)V", false));
		instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		classNode.methods.add(main);

		return classNode;
	}

	private static MethodNode findMain(ClassNode classNode) {
		for (MethodNode method : classNode.methods) {
			if (method.name.equals("main")) {
				return method;
			}
		}

		throw new AssertionError("missing main method");
	}

	private static void restore(String key, String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}
}
