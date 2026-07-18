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

package net.fabricmc.loader.impl.game.spiralknights.patch.api;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Carries the target method and current insertion point shared by ordered hook patches.
 */
public final class HookPatchContext {
	private final String targetClass;
	private final MethodNode targetMethod;
	private AbstractInsnNode insertionPoint;

	public HookPatchContext(String targetClass, MethodNode targetMethod) {
		this.targetClass = targetClass;
		this.targetMethod = targetMethod;
	}

	public String getTargetClass() {
		return targetClass;
	}

	public MethodNode getTargetMethod() {
		return targetMethod;
	}

	public void setInsertionPoint(AbstractInsnNode insertionPoint) {
		this.insertionPoint = insertionPoint;
	}

	public void insertHook(Class<?> hookClass) {
		if (insertionPoint == null) {
			throw new IllegalStateException("No insertion point is available for " + hookClass.getName());
		}

		MethodInsnNode hookInsn = createHookInvocation(hookClass);

		if (Type.getArgumentTypes(hookInsn.desc).length != 0) {
			throw new IllegalArgumentException("Cannot insert a hook with arguments without preparing its operand stack: " + hookClass.getName());
		}

		targetMethod.instructions.insert(insertionPoint, hookInsn);
		insertionPoint = hookInsn;
	}

	/**
	 * Creates an invocation of the method marked with {@link Hook}. The caller is
	 * responsible for placing any method arguments on the operand stack before
	 * inserting the returned instruction.
	 */
	public MethodInsnNode createHookInvocation(Class<?> hookClass) {
		Method hookMethod = findHookMethod(hookClass);

		return new MethodInsnNode(Opcodes.INVOKESTATIC,
				Type.getInternalName(hookMethod.getDeclaringClass()),
				hookMethod.getName(),
				Type.getMethodDescriptor(hookMethod),
				false);
	}

	private static Method findHookMethod(Class<?> hookClass) {
		Method ret = null;

		for (Method method : hookClass.getDeclaredMethods()) {
			if (!method.isAnnotationPresent(Hook.class)) continue;

			if (ret != null) {
				throw new IllegalArgumentException("Multiple @Hook methods found in " + hookClass.getName());
			}

			ret = method;
		}

		if (ret == null) {
			throw new IllegalArgumentException("No @Hook method found in " + hookClass.getName());
		}

		int modifiers = ret.getModifiers();

		if (!Modifier.isPublic(modifiers)
				|| !Modifier.isStatic(modifiers)
				|| ret.getReturnType() != void.class) {
			throw new IllegalArgumentException("@Hook method must be public static void: " + ret);
		}

		return ret;
	}
}
