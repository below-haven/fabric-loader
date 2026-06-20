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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.GameProviderHelper;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.game.spiralknights.getdown.GetdownConfig;
import net.fabricmc.loader.impl.game.spiralknights.patch.ConsoleLogMirrorPatch;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.util.Arguments;
import net.fabricmc.loader.impl.util.ExceptionUtil;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.mappingio.tree.MappingTree;

public final class SpiralKnightsGameProvider implements GameProvider {
	private static final Set<BuiltinTransform> TRANSFORMS = EnumSet.of(BuiltinTransform.STRIP_ENVIRONMENT, BuiltinTransform.CLASS_TWEAKS);
	private static final String INTERMEDIARY_GAME_DIR_NAME = "intermediaryGameJars";

	private final GameTransformer transformer = new GameTransformer(new ConsoleLogMirrorPatch());
	private final SpiralKnightsMappingResolver mappingResolver;
	private GetdownConfig config;
	private String version;
	private String entrypoint;
	private Arguments arguments;
	private String[] launchArguments;
	private final List<Path> gameJars = new ArrayList<>();
	private Collection<Path> validParentClassPath = Collections.emptyList();
	private SpiralKnightsMappingResolver.Result mappingResult;
	private FabricLauncher launcher;
	private Collection<Path> runtimeModRemapClasspath;

	public SpiralKnightsGameProvider() {
		this(new SpiralKnightsMappingResolver());
	}

	SpiralKnightsGameProvider(SpiralKnightsMappingResolver mappingResolver) {
		this.mappingResolver = mappingResolver;
	}

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

			mappingResult = mappingResolver.resolve(config.getAppDir(), version);
			installProviderMappings(launcher, mappingResult);

			launchArguments = arguments.toArray();
			validParentClassPath = launcher.getClassPath();
			this.launcher = launcher;
		} catch (IOException e) {
			throw ExceptionUtil.wrap(e);
		}

		// expose obfuscated jar locations for mods to more easily remap code from obfuscated to intermediary
		ObjectShare share = FabricLoaderImpl.INSTANCE.getObjectShare();
		share.put("fabric-loader:inputGameJars", Collections.unmodifiableList(new ArrayList<>(gameJars)));
		exposeMappingDiagnostics(share);

		return true;
	}

	private void installProviderMappings(FabricLauncher launcher, SpiralKnightsMappingResolver.Result result) {
		if (result.systemProperty) {
			Log.info(LogCategory.MAPPINGS, "Using Spiral Knights mappings from %s", SystemProperties.MAPPING_PATH);
			return;
		}

		if (result.isAvailable()) {
			boolean installed = launcher.getMappingConfiguration().setProviderMappingPath(result.mappingPath);

			if (installed) {
				Log.info(LogCategory.MAPPINGS, "Using Spiral Knights %s mappings for %s from %s", result.source, result.version, result.mappingPath);
			}
		} else {
			Log.warn(LogCategory.MAPPINGS, "No Spiral Knights mappings available for %s: %s. Expected URL: %s. Cache path: %s",
					result.version, result.unavailableReason, result.url, result.cachePath);
		}
	}

	private void exposeMappingDiagnostics(ObjectShare share) {
		SpiralKnightsMappingResolver.Result result = mappingResult;
		if (result == null) return;

		share.put("spiralknights:mappingVersion", result.version);

		if (result.mappingPath != null) {
			share.put("spiralknights:mappingPath", result.mappingPath);
		}

		if (result.url != null) {
			share.put("spiralknights:mappingUrl", result.url.toString());
		}

		if (result.unavailableReason != null) {
			share.put("spiralknights:mappingUnavailableReason", result.unavailableReason);
		}
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

		defaultMixinRemapTypeToStatic();
		applyAppSystemProperties();
		warnIfBundledJavaIsNotInUse();

		if (!SpiralKnightsLauncher.isBootstrapped()) {
			throw new FormattedException("Spiral Knights must be launched through its bootstrap",
					"Launch net.fabricmc.loader.impl.game.spiralknights.SpiralKnightsLauncher instead of Knot directly.");
		}

		transformer.locateEntrypoints(launcher, gameJars);
	}

	/**
	 * Spiral Knights mods are distributed in the intermediary namespace with their mixin annotations
	 * already statically remapped (named -> intermediary, by shuttle) and carry no refmap. The runtime
	 * remap therefore has to statically rewrite mixin targets and {@code @Shadow} members the rest of
	 * the way (intermediary -> official); the refmap-based "mixin" mode has nothing to resolve against.
	 *
	 * <p>{@link net.fabricmc.loader.impl.discovery.RuntimeModRemapper} only enables tiny-remapper's
	 * {@code MixinExtension} for a mod when its remap type resolves to {@code static}, defaulting to
	 * {@code mixin} otherwise. Flip that default to {@code static} for SK so mixins are remapped like
	 * everything else. A mod can still opt back into refmap mode via its {@code Fabric-Loom-Mixin-Remap-Type}
	 * manifest attribute, and an explicit system property still wins.
	 */
	private static void defaultMixinRemapTypeToStatic() {
		if (System.getProperty(SystemProperties.DEFAULT_MIXIN_REMAP_TYPE) == null) {
			System.setProperty(SystemProperties.DEFAULT_MIXIN_REMAP_TYPE, "static");
		}
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

	@Override
	public String getRuntimeNamespace(String defaultNs) {
		return MappingConfiguration.OFFICIAL_NAMESPACE;
	}

	@Override
	public String getDefaultModDistributionNamespace(String defaultNs) {
		return MappingConfiguration.INTERMEDIARY_NAMESPACE;
	}

	@Override
	public boolean requiresRuntimeModRemap() {
		return true;
	}

	@Override
	public Collection<Path> getRuntimeModRemapClasspath() {
		if (runtimeModRemapClasspath != null) return runtimeModRemapClasspath;

		Set<Path> ret = new LinkedHashSet<>();

		// The game jars are official (obfuscated), but mods are distributed in the intermediary namespace and
		// get remapped intermediary->official. tiny-remapper's mixin extension resolves a mixin's @Mixin target
		// (and its members) through the class path, so the class path has to be in the mods' source namespace
		// (intermediary). Feeding the official jars leaves every mixin target unresolved, which is why mixin
		// targets and @Shadow members were previously left in intermediary form. Hand it an intermediary view
		// of the game instead.
		for (Path gameJar : resolveIntermediaryGameJars()) {
			ret.add(LoaderUtil.normalizePath(gameJar));
		}

		for (Path path : validParentClassPath) {
			ret.add(LoaderUtil.normalizePath(path));
		}

		runtimeModRemapClasspath = Collections.unmodifiableList(new ArrayList<>(ret));
		return runtimeModRemapClasspath;
	}

	/**
	 * Produces an intermediary-namespace view of the game class path for use when remapping mods. The obfuscated
	 * game jars are remapped official-&gt;intermediary (cached per game version under {@code .fabric}); jars without
	 * any mapped classes (libraries, native bundles) are passed through unchanged, and also act as the remap class
	 * path so the obfuscated jars' hierarchy resolves.
	 */
	private List<Path> resolveIntermediaryGameJars() {
		String officialNs = MappingConfiguration.OFFICIAL_NAMESPACE;
		String intermediaryNs = MappingConfiguration.INTERMEDIARY_NAMESPACE;

		// No launcher/mappings means mod remapping is skipped anyway, so the class path namespace is moot.
		if (launcher == null) return new ArrayList<>(gameJars);

		MappingConfiguration mappingConfig = launcher.getMappingConfiguration();
		if (mappingConfig == null) return new ArrayList<>(gameJars);

		MappingTree mappings = mappingConfig.getMappings();
		if (mappings == null) return new ArrayList<>(gameJars);

		List<String> namespaces = new ArrayList<>();
		namespaces.add(mappings.getSrcNamespace());
		if (mappings.getDstNamespaces() != null) namespaces.addAll(mappings.getDstNamespaces());

		if (!namespaces.contains(officialNs) || !namespaces.contains(intermediaryNs)) {
			Log.warn(LogCategory.GAME_PROVIDER, "Spiral Knights mappings are missing the %s or %s namespace; mixin targets in mods will not be remapped", officialNs, intermediaryNs);
			return new ArrayList<>(gameJars);
		}

		Set<String> mappedClasses = new HashSet<>();

		for (MappingTree.ClassMapping cls : mappings.getClasses()) {
			String name = cls.getName(officialNs);
			if (name != null) mappedClasses.add(name);
		}

		List<Path> toRemap = new ArrayList<>();
		List<Path> passthrough = new ArrayList<>();

		for (Path jar : gameJars) {
			if (jarContainsAnyClass(jar, mappedClasses)) {
				toRemap.add(jar);
			} else {
				passthrough.add(jar);
			}
		}

		Path outputDir = config.getAppDir()
				.resolve(FabricLoaderImpl.CACHE_DIR_NAME)
				.resolve(INTERMEDIARY_GAME_DIR_NAME)
				.resolve(version);

		List<Path> ret = new ArrayList<>();

		try {
			ret.addAll(GameProviderHelper.remapJars(toRemap, officialNs, intermediaryNs, mappings, passthrough, outputDir));
		} catch (IOException e) {
			throw ExceptionUtil.wrap(e);
		}

		ret.addAll(passthrough);
		return ret;
	}

	private static boolean jarContainsAnyClass(Path jar, Set<String> classNames) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();

				if (name.endsWith(".class") && classNames.contains(name.substring(0, name.length() - ".class".length()))) {
					return true;
				}
			}
		} catch (IOException e) {
			Log.warn(LogCategory.GAME_PROVIDER, "Could not inspect game jar %s while preparing the mod remap class path: %s", jar, e.toString());
		}

		return false;
	}

	List<Path> getGameJars() {
		return Collections.unmodifiableList(gameJars);
	}

	SpiralKnightsMappingResolver.Result getMappingResult() {
		return mappingResult;
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
