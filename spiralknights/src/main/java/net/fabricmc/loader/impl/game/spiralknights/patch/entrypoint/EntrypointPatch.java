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

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.loader.impl.game.patch.GamePatch;
import net.fabricmc.loader.impl.game.spiralknights.patch.api.HookPatch;
import net.fabricmc.loader.impl.game.spiralknights.patch.api.HookPatchContext;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Installs Spiral Knights startup hooks.
 */
public final class EntrypointPatch extends GamePatch {
	private static final List<HookPatch> HOOK_PATCHES = Arrays.asList(
			new ConsoleLogMirrorPatch(),
			new StartClientPatch());

	@Override
	public void process(FabricLauncher launcher, Function<String, ClassNode> classSource, Consumer<ClassNode> classEmitter) {
		String entrypoint = launcher.getEntrypoint();
		ClassNode mainClass = classSource.apply(entrypoint);

		if (mainClass == null) {
			throw new RuntimeException("Could not load Spiral Knights main class " + entrypoint + "!");
		}

		//public static void main
		MethodNode mainMethod = findMethod(mainClass, method -> method.name.equals("main")
				&& method.desc.equals("([Ljava/lang/String;)V")
				&& isPublicStatic(method.access));

		if (mainMethod == null) {
			throw new RuntimeException("Could not find main method in " + entrypoint + "!");
		}

		Log.debug(LogCategory.GAME_PATCH, "Applying Spiral Knights startup hooks to %s::main", entrypoint);
		HookPatchContext context = new HookPatchContext(entrypoint, mainMethod);

		for (HookPatch hookPatch : HOOK_PATCHES) {
			hookPatch.apply(context);
		}

		classEmitter.accept(mainClass);
	}
}
