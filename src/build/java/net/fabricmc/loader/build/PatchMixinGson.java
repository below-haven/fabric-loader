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

package net.fabricmc.loader.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class PatchMixinGson {
	private static final String GSON_CLASS = "org/spongepowered/include/com/google/gson/Gson.class";

	private PatchMixinGson() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 1) {
			throw new IllegalArgumentException("Expected jar path");
		}

		Path jar = Paths.get(args[0]);
		Path tmp = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".tmp");
		boolean patched = false;

		try (JarInputStream input = new JarInputStream(Files.newInputStream(jar));
				JarOutputStream output = new JarOutputStream(Files.newOutputStream(tmp), input.getManifest())) {
			JarEntry entry;

			while ((entry = input.getNextJarEntry()) != null) {
				if (entry.getName().equals("META-INF/MANIFEST.MF")) {
					continue;
				}

				JarEntry newEntry = new JarEntry(entry.getName());
				newEntry.setTime(entry.getTime());
				output.putNextEntry(newEntry);

				if (entry.getName().equals(GSON_CLASS)) {
					output.write(patchGson(readAll(input)));
					patched = true;
				} else {
					copy(input, output);
				}

				output.closeEntry();
			}
		}

		if (!patched) {
			Files.deleteIfExists(tmp);
			throw new IOException("Unable to find " + GSON_CLASS + " in " + jar);
		}

		Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
	}

	private static byte[] patchGson(byte[] input) throws IOException {
		ClassNode classNode = new ClassNode();
		new ClassReader(input).accept(classNode, 0);
		int removed = 0;

		for (MethodNode method : classNode.methods) {
			if (!method.name.equals("<init>")) {
				continue;
			}

			removed += removeSqlAdapterRegistrations(method.instructions);
		}

		if (removed != 3) {
			throw new IOException("Expected to remove 3 Gson SQL adapter registrations, removed " + removed);
		}

		ClassWriter writer = new ClassWriter(0);
		classNode.accept(writer);
		return writer.toByteArray();
	}

	private static int removeSqlAdapterRegistrations(InsnList instructions) {
		int removed = 0;

		for (AbstractInsnNode insn = instructions.getFirst(); insn != null; ) {
			AbstractInsnNode next = insn.getNext();

			if (insn.getOpcode() == Opcodes.GETSTATIC && isSqlAdapterFactory((FieldInsnNode) insn)) {
				AbstractInsnNode add = nextReal(insn);
				AbstractInsnNode pop = nextReal(add);
				AbstractInsnNode start = previousReal(insn);

				if (start != null && start.getOpcode() == Opcodes.ALOAD
						&& add instanceof MethodInsnNode
						&& add.getOpcode() == Opcodes.INVOKEINTERFACE
						&& ((MethodInsnNode) add).owner.equals("java/util/List")
						&& ((MethodInsnNode) add).name.equals("add")
						&& pop != null
						&& pop.getOpcode() == Opcodes.POP) {
					next = pop.getNext();
					removeRange(instructions, start, pop);
					removed++;
				}
			}

			insn = next;
		}

		return removed;
	}

	private static boolean isSqlAdapterFactory(FieldInsnNode field) {
		return field.name.equals("FACTORY")
				&& (field.owner.equals("org/spongepowered/include/com/google/gson/internal/bind/TimeTypeAdapter")
						|| field.owner.equals("org/spongepowered/include/com/google/gson/internal/bind/SqlDateTypeAdapter"))
				|| field.name.equals("TIMESTAMP_FACTORY")
				&& field.owner.equals("org/spongepowered/include/com/google/gson/internal/bind/TypeAdapters");
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
		for (AbstractInsnNode ret = insn.getPrevious(); ret != null; ret = ret.getPrevious()) {
			if (ret.getOpcode() >= 0) return ret;
		}

		return null;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
		if (insn == null) return null;

		for (AbstractInsnNode ret = insn.getNext(); ret != null; ret = ret.getNext()) {
			if (ret.getOpcode() >= 0) return ret;
		}

		return null;
	}

	private static void removeRange(InsnList instructions, AbstractInsnNode start, AbstractInsnNode end) {
		AbstractInsnNode insn = start;

		while (true) {
			AbstractInsnNode next = insn.getNext();
			instructions.remove(insn);

			if (insn == end) {
				return;
			}

			insn = next;
		}
	}

	private static byte[] readAll(InputStream input) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		copy(input, output);
		return output.toByteArray();
	}

	private static void copy(InputStream input, OutputStream output) throws IOException {
		byte[] buffer = new byte[8192];
		int read;

		while ((read = input.read(buffer)) >= 0) {
			output.write(buffer, 0, read);
		}
	}
}
