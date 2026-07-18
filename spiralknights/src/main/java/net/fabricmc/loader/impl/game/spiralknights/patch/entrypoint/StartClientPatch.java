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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.loader.impl.game.spiralknights.hook.StartClientHook;
import net.fabricmc.loader.impl.game.spiralknights.patch.api.HookPatch;
import net.fabricmc.loader.impl.game.spiralknights.patch.api.HookPatchContext;

/**
 * Initializes Fabric mods after the game application has been constructed, but
 * before its startup method runs.
 *
 * <p>The unpatched entrypoint is expected to contain the equivalent of:</p>
 *
 * <pre>{@code
 * new ProjectXApp().startup();
 * }</pre>
 *
 * <p>In bytecode, the original {@code NEW/DUP/INVOKESPECIAL} sequence leaves the
 * constructed {@code ProjectXApp} instance on the operand stack for the following
 * {@code startup()} call. This patch duplicates that instance and passes one copy
 * to the method marked with {@code @Hook} in {@link StartClientHook}, leaving the
 * other copy in place so the original startup call can proceed unchanged.</p>
 */
final class StartClientPatch implements HookPatch {
	@Override
	public void apply(HookPatchContext context) {
		MethodNode mainMethod = context.getTargetMethod();
		String entrypoint = context.getTargetClass().replace('.', '/');
		MethodInsnNode constructor = findEntrypointConstructor(mainMethod, entrypoint);

		if (constructor == null) {
			throw new RuntimeException("Could not find Spiral Knights application construction in " + context.getTargetClass() + "!");
		}

		// Metadata nodes such as labels and line numbers may separate the constructor
		// from startup, so find the next instruction that the JVM actually executes.
		AbstractInsnNode next = constructor.getNext();

		while (next != null && next.getOpcode() < 0) {
			next = next.getNext();
		}

		if (!(next instanceof MethodInsnNode)
				|| next.getOpcode() != Opcodes.INVOKEVIRTUAL
				|| !entrypoint.equals(((MethodInsnNode) next).owner)
				|| !"()V".equals(((MethodInsnNode) next).desc)) {
			throw new RuntimeException("Spiral Knights application construction no longer flows directly into startup in " + context.getTargetClass() + "!");
		}

		// Stack after ProjectXApp.<init>: [app]
		// DUP:                            [app, app]
		// StartClientHook @Hook(Object):   [app]
		// The remaining reference is consumed by the original startup() invocation.
		InsnList hook = new InsnList();
		hook.add(new InsnNode(Opcodes.DUP));
		hook.add(context.createHookInvocation(StartClientHook.class));
		mainMethod.instructions.insert(constructor, hook);
	}

	/**
	 * Finds the constructor invocation for the entrypoint class. This returns the
	 * {@code INVOKESPECIAL} instruction marked below:
	 *
	 * <pre>{@code
	 * NEW           com/threerings/projectx/client/ProjectXApp
	 * DUP
	 * INVOKESPECIAL com/threerings/projectx/client/ProjectXApp.<init>()V  // returned
	 * INVOKEVIRTUAL com/threerings/projectx/client/ProjectXApp.startup()V
	 * }</pre>
	 */
	private static MethodInsnNode findEntrypointConstructor(MethodNode mainMethod, String entrypoint) {
		MethodInsnNode ret = null;

		// Match the constructor by owner rather than assuming a fixed instruction
		// offset. Refuse multiple matches because choosing one would make initialization
		// order dependent on game bytecode we do not understand.
		for (AbstractInsnNode instruction : mainMethod.instructions) {
			if (!(instruction instanceof MethodInsnNode)) continue;

			MethodInsnNode methodInstruction = (MethodInsnNode) instruction;

			if (methodInstruction.getOpcode() != Opcodes.INVOKESPECIAL
					|| !"<init>".equals(methodInstruction.name)
					|| !entrypoint.equals(methodInstruction.owner)) {
				continue;
			}

			if (ret != null) {
				throw new RuntimeException("Found multiple Spiral Knights application constructions");
			}

			ret = methodInstruction;
		}

		return ret;
	}
}
