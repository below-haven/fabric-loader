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

package net.fabricmc.loader.impl.game.spiralknights.patch.entrypoint;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.loader.impl.game.spiralknights.hook.ConsoleLogMirrorHook;
import net.fabricmc.loader.impl.game.spiralknights.patch.api.HookPatch;
import net.fabricmc.loader.impl.game.spiralknights.patch.api.HookPatchContext;

final class ConsoleLogMirrorPatch implements HookPatch {
	@Override
	public void apply(HookPatchContext context) {
		MethodNode mainMethod = context.getTargetMethod();
		MethodInsnNode loggingInit = findLoggingInit(mainMethod);

		if (loggingInit == null) {
			throw new RuntimeException("Could not find Spiral Knights logging init call in " + context.getTargetClass() + "!");
		}

		context.setInsertionPoint(loggingInit);
		context.insertHook(ConsoleLogMirrorHook.class);
	}

	/**
	 * The game sets up the logging system to redirect System.out to a log file.
	 * <p>
	 * <code>
	 * ToolUtil.configureLog("projectx.log");
	 * </code>
	 * <p>
	 * This has been the same since 2011 (!), so it's fair to assume it's stable.
	 */
	private static MethodInsnNode findLoggingInit(MethodNode mainMethod) {
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
