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

package net.fabricmc.loader.impl.discovery;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import org.objectweb.asm.commons.Remapper;

import net.fabricmc.classtweaker.api.ClassTweaker;
import net.fabricmc.classtweaker.api.ClassTweakerReader;
import net.fabricmc.classtweaker.api.ClassTweakerWriter;
import net.fabricmc.classtweaker.visitors.ClassTweakerRemapperVisitor;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.ManifestUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.TinyRemapperLoggerAdapter;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

public final class RuntimeModRemapper {
	private static final String REMAP_TYPE_MANIFEST_KEY = "Fabric-Loom-Mixin-Remap-Type";
	private static final String REMAP_TYPE_MIXIN = "mixin";
	private static final String REMAP_TYPE_STATIC = "static";
	private static final Pattern FILE_NAME_SANITIZING_PATTERN = Pattern.compile("[^\\w\\.\\-\\+]+");

	public static void remap(Collection<ModCandidateImpl> modCandidates, Path tmpDir, Path outputDir) {
		remap(modCandidates, tmpDir, outputDir, Collections.emptyList());
	}

	public static void remap(Collection<ModCandidateImpl> modCandidates, Path tmpDir, Path outputDir, Collection<Path> remapClasspath) {
		List<ModCandidateImpl> modsToRemap = new ArrayList<>();
		Set<InputTag> remapMixins = new HashSet<>();

		for (ModCandidateImpl mod : modCandidates) {
			if (mod.getRequiresRemap()) {
				modsToRemap.add(mod);
			}
		}

		if (modsToRemap.isEmpty()) return;

		MappingConfiguration config = FabricLauncherBase.getLauncher().getMappingConfiguration();
		String modNs = config.getDefaultModDistributionNamespace();
		String runtimeNs = config.getRuntimeNamespace();
		if (modNs.equals(runtimeNs)) return;

		requireMappings(config, modNs, runtimeNs);

		Map<ModCandidateImpl, RemapInfo> infoMap = new HashMap<>();

		TinyRemapper remapper = null;

		try {
			Files.createDirectories(tmpDir);
			Files.createDirectories(outputDir);

			FabricLauncher launcher = FabricLauncherBase.getLauncher();

			ClassTweaker mergedClassTweaker = ClassTweaker.newInstance();
			mergedClassTweaker.visitHeader(modNs);
			boolean needsRemapWork = false;

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = new RemapInfo();
				infoMap.put(mod, info);

				if (mod.hasPath()) {
					List<Path> paths = mod.getPaths();
					if (paths.size() != 1) throw new UnsupportedOperationException("multiple path for "+mod);

					info.inputPath = paths.get(0);
				} else {
					info.inputPath = mod.copyToDir(tmpDir, true);
					info.inputIsTemp = true;
				}

				String classTweaker = mod.getMetadata().getClassTweaker();

				if (classTweaker != null) {
					info.classTweakerPath = classTweaker;

					try (FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(info.inputPath, false)) {
						FileSystem fs = jarFs.get();
						info.classTweaker = Files.readAllBytes(fs.getPath(classTweaker));
					} catch (Throwable t) {
						throw new RuntimeException("Error reading class tweaker for mod '" +mod.getId()+ "'!", t);
					}

					ClassTweakerReader.create(mergedClassTweaker).read(info.classTweaker, modNs);
				}

				info.outputPath = outputDir.resolve(getRemappedFileName(mod, info.inputPath));

				if (isValidJar(info.outputPath)) {
					info.reuseOutput = true;
				} else {
					Files.deleteIfExists(info.outputPath);
					needsRemapWork = true;
				}
			}

			if (!needsRemapWork) {
				for (ModCandidateImpl mod : modsToRemap) {
					mod.setPaths(Collections.singletonList(infoMap.get(mod).outputPath));
				}

				return;
			}

			remapper = TinyRemapper.newRemapper(new TinyRemapperLoggerAdapter(LogCategory.MOD_REMAP))
					.withMappings(TinyUtils.createMappingProvider(launcher.getMappingConfiguration().getMappings(), modNs, runtimeNs))
					.renameInvalidLocals(false)
					.extension(new MixinExtension(remapMixins::contains))
					.extraAnalyzeVisitor((mrjVersion, className, next) ->
					mergedClassTweaker.createClassVisitor(FabricLoaderImpl.ASM_VERSION, next, null))
					.build();

			try {
				remapper.readClassPathAsync(getRemapClasspath(remapClasspath, modNs, runtimeNs).toArray(new Path[0]));
			} catch (IOException e) {
				throw new RuntimeException("Failed to populate remap classpath", e);
			}

			String defaultMixinRemapType = System.getProperty(SystemProperties.DEFAULT_MIXIN_REMAP_TYPE, REMAP_TYPE_MIXIN);

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);
				if (info.reuseOutput) continue;

				InputTag tag = remapper.createInputTag();
				info.tag = tag;

				if (requiresMixinRemap(info.inputPath, defaultMixinRemapType)) {
					remapMixins.add(tag);
				}

				remapper.readInputsAsync(tag, info.inputPath);
			}

			//Done in a 2nd loop as we need to make sure all the inputs are present before remapping
			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);
				if (info.reuseOutput) continue;

				OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(info.outputPath).build();

				try (FileSystemUtil.FileSystemDelegate delegate = FileSystemUtil.getJarFileSystem(info.inputPath, false)) {
					if (delegate.get() == null) {
						throw new RuntimeException("Could not open JAR file " + info.inputPath.getFileName() + " for NIO reading!");
					}

					Path inputJar = delegate.get().getRootDirectories().iterator().next();
					outputConsumer.addNonClassFiles(inputJar, NonClassCopyMode.FIX_META_INF, remapper);

					info.outputConsumerPath = outputConsumer;

					remapper.apply(outputConsumer, info.tag);
				}
			}

			//Done in a 3rd loop as this can happen when the remapper is doing its thing.
			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);

				if (!info.reuseOutput && info.classTweaker != null) {
					info.classTweaker = remapClassTweaker(info.classTweaker, remapper.getEnvironment().getRemapper(), modNs, runtimeNs);
				}
			}

			remapper.finish();

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);

				if (info.outputConsumerPath != null) {
					info.outputConsumerPath.close();
				}

				if (!info.reuseOutput && info.classTweakerPath != null) {
					try (FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(info.outputPath, false)) {
						FileSystem fs = jarFs.get();

						Files.delete(fs.getPath(info.classTweakerPath));
						Files.write(fs.getPath(info.classTweakerPath), info.classTweaker);
					}
				}

				mod.setPaths(Collections.singletonList(info.outputPath));
			}
		} catch (Throwable t) {
			if (remapper != null) {
				remapper.finish();
			}

			for (RemapInfo info : infoMap.values()) {
				if (info.outputPath == null) {
					continue;
				}

				if (info.reuseOutput) {
					continue;
				}

				try {
					Files.deleteIfExists(info.outputPath);
				} catch (IOException e) {
					Log.warn(LogCategory.MOD_REMAP, "Error deleting failed output jar %s", info.outputPath, e);
				}
			}

			if (t instanceof FormattedException) {
				throw (FormattedException) t;
			}

			throw new FormattedException("Failed to remap mods!", t);
		} finally {
			for (RemapInfo info : infoMap.values()) {
				try {
					if (info.inputIsTemp) Files.deleteIfExists(info.inputPath);
				} catch (IOException e) {
					Log.warn(LogCategory.MOD_REMAP, "Error deleting temporary input jar %s", info.inputPath, e);
				}
			}
		}
	}

	private static void requireMappings(MappingConfiguration config, String modNs, String runtimeNs) {
		if (!config.hasAnyMappings()) {
			throw cannotRemap(modNs, runtimeNs, "no mappings are available");
		}

		List<String> namespaces = config.getNamespaces();

		if (namespaces == null || !namespaces.contains(modNs) || !namespaces.contains(runtimeNs)) {
			throw cannotRemap(modNs, runtimeNs, String.format(Locale.ROOT,
					"mapping namespaces %s do not include both %s and %s", namespaces, modNs, runtimeNs));
		}
	}

	private static FormattedException cannotRemap(String modNs, String runtimeNs, String reason) {
		String gameName = "the current game";
		String gameVersion = null;

		if (FabricLoaderImpl.INSTANCE.tryGetGameProvider() != null) {
			gameName = FabricLoaderImpl.INSTANCE.getGameProvider().getGameName();
			gameVersion = FabricLoaderImpl.INSTANCE.getGameProvider().getRawGameVersion();
		}

		String target = gameVersion == null ? gameName : gameName + " " + gameVersion;

		return new FormattedException("Cannot remap mods!",
				"Cannot remap mods from " + modNs + " to " + runtimeNs + " for " + target + ": " + reason + ".");
	}

	private static byte[] remapClassTweaker(byte[] input, Remapper remapper, String modNs, String runtimeNs) {
		ClassTweakerWriter writer = ClassTweakerWriter.create(ClassTweaker.CT_LATEST);
		ClassTweakerRemapperVisitor remappingDecorator = new ClassTweakerRemapperVisitor(writer, remapper, modNs, runtimeNs);
		ClassTweakerReader reader = ClassTweakerReader.create(remappingDecorator);
		reader.read(input, modNs);
		return writer.getOutput();
	}

	private static List<Path> getRemapClasspath(Collection<Path> providerClasspath, String modNs, String runtimeNs) throws IOException {
		Set<Path> ret = new LinkedHashSet<>();

		if (providerClasspath != null) {
			for (Path path : providerClasspath) {
				if (path != null) {
					ret.add(path.toAbsolutePath().normalize());
				}
			}
		}

		String remapClasspathFile = System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE);

		if (remapClasspathFile != null) {
			String content = new String(Files.readAllBytes(Paths.get(remapClasspathFile)), StandardCharsets.UTF_8);

			ret.addAll(Arrays.stream(content.split(File.pathSeparator))
					.filter(s -> !s.isEmpty())
					.map(Paths::get)
					.map(path -> path.toAbsolutePath().normalize())
					.collect(Collectors.toList()));
		}

		if (ret.isEmpty()) {
			throw cannotRemap(modNs, runtimeNs,
					"no remap classpath is available; provide fabric.remapClasspathFile or a game-provider remap classpath");
		}

		return new ArrayList<>(ret);
	}

	private static String getRemappedFileName(ModCandidateImpl mod, Path inputPath) throws IOException {
		String inputHash = hashFile(inputPath);
		String ret = String.format(Locale.ROOT, "%s-%s-%s-loader-%s.jar",
				sanitizeFileName(mod.getId()),
				sanitizeFileName(mod.getVersion().getFriendlyString()),
				inputHash.substring(0, 16),
				sanitizeFileName(FabricLoaderImpl.VERSION));

		if (ret.length() > 160) {
			ret = ret.substring(0, 80).concat(ret.substring(ret.length() - 80));
		}

		return ret;
	}

	private static String sanitizeFileName(String input) {
		String ret = FILE_NAME_SANITIZING_PATTERN.matcher(input).replaceAll("_");

		return ret.isEmpty() ? "_" : ret;
	}

	private static String hashFile(Path path) throws IOException {
		MessageDigest digest = newSha256Digest();
		byte[] buffer = new byte[8192];

		try (InputStream is = Files.newInputStream(path)) {
			int len;

			while ((len = is.read(buffer)) >= 0) {
				digest.update(buffer, 0, len);
			}
		}

		return toHex(digest.digest());
	}

	private static MessageDigest newSha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder ret = new StringBuilder(bytes.length * 2);

		for (byte b : bytes) {
			ret.append(Character.forDigit((b >>> 4) & 0xf, 16));
			ret.append(Character.forDigit(b & 0xf, 16));
		}

		return ret.toString();
	}

	private static boolean isValidJar(Path path) {
		if (!Files.isRegularFile(path)) return false;

		try (ZipFile ignored = new ZipFile(path.toFile())) {
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Determine whether a jar requires Mixin remapping with tiny remapper.
	 *
	 * <p>This is typically the case when a mod was built without the Mixin annotation processor generating refmaps.
	 */
	private static boolean requiresMixinRemap(Path inputPath, String defaultMixinRemapType) throws IOException, URISyntaxException {
		final Manifest manifest = ManifestUtil.readManifest(inputPath);
		if (manifest == null) return false;

		final Attributes mainAttributes = manifest.getMainAttributes();

		String remapType = mainAttributes.getValue(REMAP_TYPE_MANIFEST_KEY);
		if (remapType == null) remapType = defaultMixinRemapType;

		return REMAP_TYPE_STATIC.equalsIgnoreCase(remapType);
	}

	private static class RemapInfo {
		InputTag tag;
		Path inputPath;
		Path outputPath;
		boolean inputIsTemp;
		boolean reuseOutput;
		OutputConsumerPath outputConsumerPath;
		String classTweakerPath;
		byte[] classTweaker;
	}
}
