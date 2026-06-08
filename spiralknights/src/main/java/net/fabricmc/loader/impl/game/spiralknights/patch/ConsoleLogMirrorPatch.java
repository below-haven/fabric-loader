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

package net.fabricmc.loader.impl.game.spiralknights.patch;

import java.util.function.Consumer;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.loader.impl.game.patch.GamePatch;
import net.fabricmc.loader.impl.game.spiralknights.hook.ConsoleLogMirrorHook;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public final class ConsoleLogMirrorPatch extends GamePatch {
	/**
	 * One of the first things the game does after its init is setup the logging system.
	 * <p>
	 * <code>
	 * public static void main(String[] args) throws Exception {
	 * ToolUtil.configureLog("projectx.log");
	 * }
	 * </code>
	 * <p>
	 * This grabs the System.out and redirects it to a log file.
	 * But we also want these logs to show in the terminal, so we need to do some trickery.
	 */
	@Override
	public void process(FabricLauncher launcher, Function<String, ClassNode> classSource, Consumer<ClassNode> classEmitter) {
		String entrypoint = launcher.getEntrypoint();
		ClassNode mainClass = classSource.apply(entrypoint);

		if (mainClass == null) {
			throw new RuntimeException("Could not load Spiral Knights main class " + entrypoint + "!");
		}

		MethodNode mainMethod = findMethod(mainClass, method -> method.name.equals("main")
				&& method.desc.equals("([Ljava/lang/String;)V")
				&& isPublicStatic(method.access));

		if (mainMethod == null) {
			throw new RuntimeException("Could not find main method in " + entrypoint + "!");
		}

		MethodInsnNode loggingInit = findLoggingInit(mainMethod);

		if (loggingInit == null) {
			throw new RuntimeException("Could not find Spiral Knights logging init call in " + entrypoint + "!");
		}

		Log.debug(LogCategory.GAME_PATCH, "Applying Spiral Knights console log mirror hook to %s::main", entrypoint);

		InsnList hook = new InsnList();
		hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ConsoleLogMirrorHook.INTERNAL_NAME,
				"installConsoleLogMirror", "()V", false));
		mainMethod.instructions.insert(loggingInit, hook);
		classEmitter.accept(mainClass);
	}

	/**
	 * The game sets up the logging system to redirect System.out to a log file.
	 * <p>
	 * <code>
	 * ToolUtil.configureLog("projectx.log");
	 * </code>
	 * <p>
	 * This has been the same since 2011 (!), so it's fair to assume it's stable
	 */
	private MethodInsnNode findLoggingInit(MethodNode mainMethod) {
		boolean foundLogName = false;

		for (AbstractInsnNode insn : mainMethod.instructions) {
			if (insn instanceof LdcInsnNode && "projectx.log".equals(((LdcInsnNode) insn).cst)) {
				foundLogName = true;
				continue;
			}

			if (foundLogName && insn instanceof MethodInsnNode) {
				MethodInsnNode methodInsn = (MethodInsnNode) insn;

				if (methodInsn.getOpcode() == Opcodes.INVOKESTATIC
						&& methodInsn.owner.startsWith("com/threerings/util/")
						&& methodInsn.desc.equals("(Ljava/lang/String;)V")) {
					return methodInsn;
				}

				return null;
			}
		}

		return null;
	}
}
