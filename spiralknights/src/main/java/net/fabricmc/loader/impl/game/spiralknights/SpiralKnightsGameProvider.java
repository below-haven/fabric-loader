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

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.game.spiralknights.getdown.GetdownConfig;
import net.fabricmc.loader.impl.game.spiralknights.patch.ConsoleLogMirrorPatch;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.ExceptionUtil;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public final class SpiralKnightsGameProvider implements GameProvider {
	private static final Set<BuiltinTransform> TRANSFORMS = EnumSet.of(BuiltinTransform.STRIP_ENVIRONMENT, BuiltinTransform.CLASS_TWEAKS);

	private final GameTransformer transformer = new GameTransformer(new ConsoleLogMirrorPatch());
	private GetdownConfig config;
	private String version;
	private String entrypoint;
	private Arguments arguments;
	private String[] launchArguments;
	private final List<Path> gameJars = new ArrayList<>();
	private Collection<Path> validParentClassPath = Collections.emptyList();

	/**
	 * Fabric uses the game id as the stable, machine-readable name for this provider.
	 * It becomes the id of the built-in "game mod", which lets mods depend on or
	 * query the currently running game the same way they would query another mod.
	 */
	@Override
	public String getGameId() {
		return "spiralknights";
	}

	/**
	 * This is the human-readable game name Fabric shows in logs, metadata, and
	 * diagnostics. It is separate from the id so user-facing text can be nicer
	 * without changing the stable identifier mods use.
	 */
	@Override
	public String getGameName() {
		return "Spiral Knights";
	}

	/**
	 * Returns the version string as Spiral Knights/Getdown reports it. Loader keeps
	 * both a raw and normalized version because some games need cleanup before their
	 * versions can participate in dependency checks.
	 */
	@Override
	public String getRawGameVersion() {
		return version;
	}

	/**
	 * Returns the version Fabric should expose through metadata and dependency
	 * resolution. Spiral Knights versions are already suitable here, so this provider
	 * uses the same value as the raw Getdown version.
	 */
	@Override
	public String getNormalizedGameVersion() {
		return version;
	}

	/**
	 * Fabric models the game itself as a built-in mod backed by the game jars. That
	 * makes APIs like mod metadata lookup and dependency declarations work uniformly:
	 * a mod can depend on "spiralknights" just like it can depend on another mod id.
	 */
	@Override
	public Collection<BuiltinMod> getBuiltinMods() {
		ModMetadata metadata = new BuiltinModMetadata.Builder(getGameId(), getNormalizedGameVersion())
				.setName(getGameName())
				.build();

		return Collections.singletonList(new BuiltinMod(gameJars, metadata));
	}

	/**
	 * Returns the class Fabric will eventually call to start the game. This is
	 * discovered from Getdown during locateGame, then later loaded through Fabric's
	 * target class loader so class transformation and mod injection can participate.
	 */
	@Override
	public String getEntrypoint() {
		return entrypoint;
	}

	/**
	 * Tells Fabric which directory should be treated as the game's working directory.
	 * For Spiral Knights that is the Getdown app directory, because the original
	 * launcher expects relative asset and config paths to resolve from there.
	 */
	@Override
	public Path getLaunchDirectory() {
		return config != null ? config.getAppDir() : Paths.get(".");
	}

	/**
	 * Tells Knot whether this game needs a URLClassLoader-compatible target loader.
	 * Spiral Knights does not rely on legacy URLClassLoader behavior here, so Fabric
	 * can use its normal transforming class loader.
	 */
	@Override
	public boolean requiresUrlClassLoader() {
		return false;
	}

	/**
	 * Returns the built-in tweaks that should be allowed on loaded classes.
	 */
	//TODO: maybe apply this only to com.threerings?
	@Override
	public Set<BuiltinTransform> getBuiltinTransforms(String className) {
		return TRANSFORMS;
	}

	@Override
	public boolean isEnabled() {
		return System.getProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR) != null
				&& !SystemProperties.isSet(SystemProperties.SKIP_SPIRAL_KNIGHTS_PROVIDER);
	}

	/**
	 * Detects and records the game before Fabric initializes mods. This reads the
	 * Getdown configuration, resolves the jars that contain the game code, finds the
	 * real main class, scans for any entrypoint patches, and captures the class path
	 * that should remain visible from Fabric's parent loader.
	 */
	@Override
	public boolean locateGame(FabricLauncher launcher, String[] args) {
		if (launcher.getEnvironmentType() != EnvType.CLIENT) {
			return false;
		}

		String appDirProperty = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR);
		if (appDirProperty == null || appDirProperty.isEmpty()) return false;

		arguments = new Arguments();
		arguments.parse(args);

		try {
			config = GetdownConfig.read(Paths.get(appDirProperty));
			entrypoint = config.getMainClass();

			version = System.getProperty(SystemProperties.GAME_VERSION, config.getVersion());
			gameJars.clear();
			gameJars.addAll(resolveCodePaths(config, entrypoint));

			if (findEntrypointJar(gameJars, entrypoint) == null) {
				return false;
			}

			launchArguments = arguments.toArray();
			validParentClassPath = launcher.getClassPath();
		} catch (IOException e) {
			throw ExceptionUtil.wrap(e);
		}

		// expose obfuscated jar locations for mods to more easily remap code from obfuscated to intermediary
		ObjectShare share = FabricLoaderImpl.INSTANCE.getObjectShare();
		share.put("fabric-loader:inputGameJars", Collections.unmodifiableList(new ArrayList<>(gameJars)));

		return true;
	}

	private static List<Path> resolveCodePaths(GetdownConfig config, String entrypoint) throws IOException {
		List<Path> ret = new ArrayList<>(config.getClasspathJars());

		for (int i = 0; i < ret.size(); i++) {
			Path path = ret.get(i);

			if (!Files.isRegularFile(path)) {
				throw new IOException("Missing Spiral Knights code jar: " + path);
			}

			ret.set(i, LoaderUtil.normalizeExistingPath(path));
		}

		String gameJarOverride = System.getProperty(SystemProperties.GAME_JAR_PATH);

		//special case, use the jar passed in args and not the one in the game dir
		if (gameJarOverride != null && !gameJarOverride.isEmpty()) {
			Path overridePath = Paths.get(gameJarOverride);

			if (!Files.isRegularFile(overridePath)) {
				throw new IOException("Configured game jar does not exist: " + overridePath);
			}

			Path entrypointJar = findEntrypointJar(ret, entrypoint);

			if (entrypointJar == null) {
				throw new IOException("Unable to replace Spiral Knights game jar: no getdown code jar contains " + entrypoint);
			}

			ret.set(ret.indexOf(entrypointJar), LoaderUtil.normalizeExistingPath(overridePath));
		}

		return ret;
	}

	/**
	 * Performs Spiral Knights-specific setup after the game has been located but
	 * before its classes are unlocked and loaded. This is where the provider sets the
	 * working directory, restricts parent class path visibility, and verifies that
	 * the JVM was started through the Spiral Knights bootstrap.
	 */
	@Override
	public void initialize(FabricLauncher launcher) {
		launcher.setValidParentClassPath(validParentClassPath);
		System.setProperty("user.dir", config.getAppDir().toAbsolutePath().normalize().toString());

		applyAppSystemProperties();
		warnIfBundledJavaIsNotInUse();

		if (!SpiralKnightsLauncher.isBootstrapped()) {
			throw new FormattedException("Spiral Knights must be launched through its bootstrap",
					"Launch net.fabricmc.loader.impl.game.spiralknights.SpiralKnightsLauncher instead of Knot directly.");
		}

		transformer.locateEntrypoints(launcher, gameJars);
	}

	private void warnIfBundledJavaIsNotInUse() {
		Path javaBinary = config.getJavaBinaryPath();

		if (!Files.isExecutable(javaBinary)) {
			return;
		}

		Path javaHome = LoaderUtil.normalizePath(Paths.get(System.getProperty("java.home")));

		if (!javaBinary.startsWith(javaHome)) {
			Log.warn(LogCategory.GAME_PROVIDER, "Spiral Knights normally runs with bundled Java at %s; start Fabric with that binary to match Getdown.", javaBinary);
		}
	}

	private static void applyAppSystemProperties() {
		Map<String, String> appProperties = new HashMap<>();

		for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
			String key = String.valueOf(entry.getKey());

			if (key.startsWith("app.")) {
				appProperties.put(key.substring("app.".length()), String.valueOf(entry.getValue()));
			}
		}

		for (Map.Entry<String, String> entry : appProperties.entrySet()) {
			System.setProperty(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Gives Fabric the transformer that can provide pre-launch patches for the game's
	 * entrypoint classes. The class loader asks this transformer for patched bytecode
	 * before defining affected classes.
	 */
	@Override
	public GameTransformer getEntrypointTransformer() {
		return transformer;
	}

	/**
	 * Adds the resolved game jars to Fabric's target class path. Fabric delays this
	 * until after discovery and initialization so it can control which classes are
	 * loaded early and ensure game classes pass through the transforming loader.
	 */
	@Override
	public void unlockClassPath(FabricLauncher launcher) {
		for (Path gameJar : gameJars) {
			launcher.addToClassPath(gameJar);
		}
	}

	/**
	 * Hands control from Fabric to Spiral Knights. By this point Fabric has found
	 * mods, prepared transformations, and exposed the game jars through the target
	 * loader, so invoking the game's static main method starts the normal game with
	 * Fabric already woven into class loading.
	 */
	@Override
	public void launch(ClassLoader loader) {
		MethodHandle invoker;

		try {
			Class<?> c = loader.loadClass(entrypoint);
			invoker = MethodHandles.lookup().findStatic(c, "main", MethodType.methodType(void.class, String[].class));
		} catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
			throw new FormattedException("Failed to start Spiral Knights!", e);
		}

		try {
			invoker.invokeExact(launchArguments);
		} catch (Throwable t) {
			throw new FormattedException("Spiral Knights has crashed!", t);
		}
	}

	/**
	 * Exposes the parsed launch arguments to Fabric internals and mods that need to
	 * inspect them. The provider owns this object because it knows how the target game
	 * expects its command line to be represented.
	 */
	@Override
	public Arguments getArguments() {
		return arguments;
	}

	/**
	 * Returns the exact command-line arguments that will be passed to the game. The
	 * sanitize flag exists so providers can hide secrets in crash reports; Spiral
	 * Knights does not currently classify any arguments as sensitive, so it returns a
	 * defensive copy of the captured launch arguments.
	 */
	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		return launchArguments != null ? launchArguments.clone() : new String[0];
	}

	List<Path> getGameJars() {
		return Collections.unmodifiableList(gameJars);
	}

	private static Path findEntrypointJar(List<Path> paths, String entrypoint) throws IOException {
		String entry = LoaderUtil.getClassFileName(entrypoint);

		for (Path path : paths) {
			try (ZipFile zipFile = new ZipFile(path.toFile())) {
				if (zipFile.getEntry(entry) != null) {
					return path;
				}
			}
		}

		return null;
	}
}
