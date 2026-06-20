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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.spiralknights.getdown.GetdownConfig;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.SystemProperties;

public class SpiralKnightsGameProviderTest {
	@TempDir
	Path tempDir;

	private String oldAppDir;
	private String oldGameJar;
	private String oldGameVersion;
	private String oldSkipProvider;
	private String oldResourceDir;
	private String oldAppEndpoint;
	private String oldEndpoint;
	private String oldUserDir;
	private String oldBootstrapped;
	private String oldJavaClassPath;
	private String oldOsName;
	private String oldSpiralKnightsLogLevel;
	private String oldJavaLoggingConfigClass;
	private String oldMappingPath;
	private String oldSpiralKnightsMappingPath;
	private String oldSpiralKnightsMappingUrlTemplate;
	private String oldSpiralKnightsDisableMappingDownload;
	private String oldSpiralKnightsRefreshMappings;

	@BeforeEach
	public void setUp() {
		oldAppDir = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR);
		oldGameJar = System.getProperty(SystemProperties.GAME_JAR_PATH);
		oldGameVersion = System.getProperty(SystemProperties.GAME_VERSION);
		oldSkipProvider = System.getProperty(SystemProperties.SKIP_SPIRAL_KNIGHTS_PROVIDER);
		oldResourceDir = System.getProperty("resource_dir");
		oldAppEndpoint = System.getProperty("app.endpoint");
		oldEndpoint = System.getProperty("endpoint");
		oldUserDir = System.getProperty("user.dir");
		oldBootstrapped = System.getProperty(SpiralKnightsLauncher.BOOTSTRAPPED_PROPERTY);
		oldJavaClassPath = System.getProperty("java.class.path");
		oldOsName = System.getProperty("os.name");
		oldSpiralKnightsLogLevel = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL);
		oldJavaLoggingConfigClass = System.getProperty("java.util.logging.config.class");
		oldMappingPath = System.getProperty(SystemProperties.MAPPING_PATH);
		oldSpiralKnightsMappingPath = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_PATH);
		oldSpiralKnightsMappingUrlTemplate = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_URL_TEMPLATE);
		oldSpiralKnightsDisableMappingDownload = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_DISABLE_MAPPING_DOWNLOAD);
		oldSpiralKnightsRefreshMappings = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_REFRESH_MAPPINGS);

		System.clearProperty(SystemProperties.MAPPING_PATH);
		System.clearProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_PATH);
		System.clearProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_URL_TEMPLATE);
		System.clearProperty(SystemProperties.SPIRAL_KNIGHTS_REFRESH_MAPPINGS);
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_DISABLE_MAPPING_DOWNLOAD, "true");
	}

	@AfterEach
	public void tearDown() {
		restore(SystemProperties.SPIRAL_KNIGHTS_APP_DIR, oldAppDir);
		restore(SystemProperties.GAME_JAR_PATH, oldGameJar);
		restore(SystemProperties.GAME_VERSION, oldGameVersion);
		restore(SystemProperties.SKIP_SPIRAL_KNIGHTS_PROVIDER, oldSkipProvider);
		restore("resource_dir", oldResourceDir);
		restore("app.endpoint", oldAppEndpoint);
		restore("endpoint", oldEndpoint);
		restore("user.dir", oldUserDir);
		restore(SpiralKnightsLauncher.BOOTSTRAPPED_PROPERTY, oldBootstrapped);
		restore("java.class.path", oldJavaClassPath);
		restore("os.name", oldOsName);
		restore(SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL, oldSpiralKnightsLogLevel);
		restore("java.util.logging.config.class", oldJavaLoggingConfigClass);
		restore(SystemProperties.MAPPING_PATH, oldMappingPath);
		restore(SystemProperties.SPIRAL_KNIGHTS_MAPPING_PATH, oldSpiralKnightsMappingPath);
		restore(SystemProperties.SPIRAL_KNIGHTS_MAPPING_URL_TEMPLATE, oldSpiralKnightsMappingUrlTemplate);
		restore(SystemProperties.SPIRAL_KNIGHTS_DISABLE_MAPPING_DOWNLOAD, oldSpiralKnightsDisableMappingDownload);
		restore(SystemProperties.SPIRAL_KNIGHTS_REFRESH_MAPPINGS, oldSpiralKnightsRefreshMappings);
	}

	@Test
	public void locatesGameAndRecordsLaunchArguments() throws IOException {
		TestApp app = createApp();
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR, app.appDir.toString());

		SpiralKnightsGameProvider provider = new SpiralKnightsGameProvider();

		Assertions.assertTrue(provider.isEnabled());
		Assertions.assertTrue(provider.locateGame(createLauncher(), new String[] { "--foo", "bar", "extra" }));
		Assertions.assertEquals("spiralknights", provider.getGameId());
		Assertions.assertEquals("20260603121536", provider.getRawGameVersion());
		Assertions.assertEquals("com.threerings.projectx.client.ProjectXApp", provider.getEntrypoint());
		Assertions.assertEquals(Arrays.asList(app.configJar, app.gameJar, app.lwjglJar), provider.getGameJars());
		Assertions.assertEquals(Arrays.asList(app.configJar, app.gameJar, app.lwjglJar), provider.getRuntimeModRemapClasspath());
		Assertions.assertArrayEquals(new String[] { "--foo", "bar", "extra" }, provider.getLaunchArguments(false));
		Assertions.assertEquals("Spiral Knights", provider.getBuiltinMods().iterator().next().metadata.getName());
		Assertions.assertEquals(MappingConfiguration.OFFICIAL_NAMESPACE, provider.getRuntimeNamespace("named"));
		Assertions.assertEquals(MappingConfiguration.INTERMEDIARY_NAMESPACE, provider.getDefaultModDistributionNamespace("named"));
		Assertions.assertTrue(provider.requiresRuntimeModRemap());
	}

	@Test
	public void gameJarOverrideOnlyReplacesEntrypointJar() throws IOException {
		TestApp app = createApp();
		Path overrideJar = tempDir.resolve("override.jar");
		createJar(overrideJar, "com/threerings/projectx/client/ProjectXApp.class");
		overrideJar = overrideJar.toRealPath();
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR, app.appDir.toString());
		System.setProperty(SystemProperties.GAME_JAR_PATH, overrideJar.toString());

		SpiralKnightsGameProvider provider = new SpiralKnightsGameProvider();

		Assertions.assertTrue(provider.locateGame(createLauncher(), new String[0]));
		Assertions.assertEquals(Arrays.asList(app.configJar, overrideJar, app.lwjglJar), provider.getGameJars());
	}

	@Test
	public void locateGameInstallsLocalMappingOverride() throws IOException {
		TestApp app = createApp();
		Path mappingPath = tempDir.resolve("spiralknights.tiny");
		Files.write(mappingPath, Arrays.asList(
				"tiny\t2\t0\t" + MappingConfiguration.OFFICIAL_NAMESPACE + "\t" + MappingConfiguration.INTERMEDIARY_NAMESPACE,
				"c\tcom/threerings/example/GameThing\tcom/example/class_1"));
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR, app.appDir.toString());
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_PATH, mappingPath.toString());

		MappingConfiguration mappings = new MappingConfiguration();
		FabricLauncher launcher = createLauncher();
		when(launcher.getMappingConfiguration()).thenReturn(mappings);

		SpiralKnightsGameProvider provider = new SpiralKnightsGameProvider();

		Assertions.assertTrue(provider.locateGame(launcher, new String[0]));
		Assertions.assertEquals(mappingPath.toAbsolutePath().normalize(), provider.getMappingResult().mappingPath);
		Assertions.assertEquals(Arrays.asList(MappingConfiguration.OFFICIAL_NAMESPACE, MappingConfiguration.INTERMEDIARY_NAMESPACE), mappings.getNamespaces());
	}

	@Test
	public void initializeUsesBootstrappedSystemProperties() throws IOException {
		TestApp app = createApp();
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR, app.appDir.toString());
		System.setProperty(SpiralKnightsLauncher.BOOTSTRAPPED_PROPERTY, "true");
		System.setProperty("resource_dir", app.appDir + "/rsrc");
		System.setProperty("app.endpoint", "game.spiralknights.com");

		SpiralKnightsGameProvider provider = new SpiralKnightsGameProvider();
		FabricLauncher launcher = createLauncher();

		Assertions.assertTrue(provider.locateGame(launcher, new String[0]));
		provider.initialize(launcher);

		Assertions.assertEquals(app.appDir + "/rsrc", System.getProperty("resource_dir"));
		Assertions.assertEquals(app.appDir.toString(), System.getProperty("user.dir"));
		Assertions.assertEquals("game.spiralknights.com", System.getProperty("endpoint"));
	}

	@Test
	public void initializeRequiresBootstrap() throws IOException {
		TestApp app = createApp();
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR, app.appDir.toString());

		SpiralKnightsGameProvider provider = new SpiralKnightsGameProvider();
		FabricLauncher launcher = createLauncher();

		Assertions.assertTrue(provider.locateGame(launcher, new String[0]));
		Assertions.assertThrows(FormattedException.class, () -> provider.initialize(launcher));
	}

	@Test
	public void launcherBuildsBundledJavaCommandWithGetdownJvmArgs() throws IOException {
		System.setProperty("os.name", "Mac OS X");
		TestApp app = createApp();
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR, app.appDir.toString());
		System.setProperty("java.class.path", "fabric-loader.jar" + File.pathSeparator + tempDir.resolve("absolute.jar"));
		System.setProperty("user.dir", tempDir.toString());

		List<String> command = SpiralKnightsLauncher.buildCommand(GetdownConfig.read(app.appDir), new String[] { "--foo" });
		Path zipFsModule = app.appDir.resolve(FabricLoaderImpl.CACHE_DIR_NAME)
				.resolve("modules")
				.resolve("net.fabricmc.loader.zipfs.jar");

		Assertions.assertEquals(app.appDir.resolve("java_vm/bin/java").toString(), command.get(0));
		Assertions.assertTrue(command.contains("-Xdock:icon=" + app.appDir + "/rsrc/ui/icon/desktop.icns"));
		Assertions.assertTrue(command.contains("-Xdock:name=Spiral Knights"));
		Assertions.assertTrue(command.contains("-Dresource_dir=" + app.appDir + "/rsrc"));
		Assertions.assertTrue(command.contains("-XX:-CreateCoredumpOnCrash"));
		Assertions.assertTrue(command.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"));
		Assertions.assertTrue(command.contains("--add-opens=java.base/java.util=ALL-UNNAMED"));
		Assertions.assertTrue(command.contains("--module-path"));
		Assertions.assertTrue(command.contains(zipFsModule.toString()));
		Assertions.assertTrue(command.contains("--add-modules"));
		Assertions.assertTrue(command.contains("net.fabricmc.loader.zipfs"));
		Assertions.assertTrue(command.contains("--enable-native-access=ALL-UNNAMED"));
		Assertions.assertTrue(command.contains(tempDir.resolve("fabric-loader.jar").toString() + File.pathSeparator + tempDir.resolve("absolute.jar")));
		Assertions.assertTrue(command.contains("net.fabricmc.loader.impl.launch.knot.KnotClient"));
		Assertions.assertTrue(Files.isRegularFile(zipFsModule));
		Assertions.assertEquals("--foo", command.get(command.size() - 1));
	}

	@Test
	public void launcherAddsLogConfigClassWhenLogLevelIsSet() throws IOException {
		TestApp app = createApp();
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR, app.appDir.toString());
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL, "FINE");

		List<String> command = SpiralKnightsLauncher.buildCommand(GetdownConfig.read(app.appDir), new String[0]);

		Assertions.assertTrue(command.contains("-D" + SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL + "=FINE"));
		Assertions.assertTrue(command.contains("-Djava.util.logging.config.class=" + SpiralKnightsLogConfig.class.getName()));
	}

	@Test
	public void launcherRejectsInvalidLogLevel() throws IOException {
		TestApp app = createApp();
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR, app.appDir.toString());
		System.setProperty(SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL, "not-a-level");

		Assertions.assertThrows(FormattedException.class,
				() -> SpiralKnightsLauncher.buildCommand(GetdownConfig.read(app.appDir), new String[0]));
	}

	private TestApp createApp() throws IOException {
		Path appDir = tempDir.resolve("app");
		Path codeDir = appDir.resolve("code");
		Files.createDirectories(codeDir);

		Path configJar = codeDir.resolve("config.jar");
		Path gameJar = codeDir.resolve("projectx-pcode.jar");
		Path lwjglJar = codeDir.resolve("lwjgl.jar");

		createJar(configJar);
		createJar(gameJar, "com/threerings/projectx/client/ProjectXApp.class");
		createJar(lwjglJar);

		Files.write(appDir.resolve("getdown.txt"), Arrays.asList(
				"version = 20260603121536",
				"class = com.threerings.projectx.client.ProjectXApp",
				"code = code/config.jar",
				"code = code/projectx-pcode.jar",
				"code = code/lwjgl.jar",
				"jvmarg = [mac os x] -Xdock:name=Spiral Knights",
				"jvmarg = -XX:-CreateCoredumpOnCrash",
				"jvmarg = --add-opens=java.base/java.lang=ALL-UNNAMED",
				"jvmarg = --add-opens=java.base/java.util=ALL-UNNAMED",
				"jvmarg = -Dresource_dir=%APPDIR%/rsrc",
				"jvmarg = --enable-native-access=ALL-UNNAMED",
				"ui.mac_dock_icon = rsrc/ui/icon/desktop.icns"));

		return new TestApp(appDir.toRealPath(), configJar.toRealPath(), gameJar.toRealPath(), lwjglJar.toRealPath());
	}

	private static FabricLauncher createLauncher() {
		FabricLauncher launcher = mock();
		when(launcher.getEnvironmentType()).thenReturn(EnvType.CLIENT);
		when(launcher.getClassPath()).thenReturn(Collections.emptyList());
		when(launcher.getEntrypoint()).thenReturn("com.threerings.projectx.client.ProjectXApp");
		return launcher;
	}

	private static void createJar(Path path, String... entries) throws IOException {
		try (OutputStream os = Files.newOutputStream(path);
				JarOutputStream jos = new JarOutputStream(os)) {
			for (String entry : entries) {
				jos.putNextEntry(new JarEntry(entry));

				if (entry.equals("com/threerings/projectx/client/ProjectXApp.class")) {
					jos.write(createProjectXAppClass());
				}

				jos.closeEntry();
			}
		}
	}

	private static byte[] createProjectXAppClass() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/threerings/projectx/client/ProjectXApp", null, "java/lang/Object", null);

		MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		constructor.visitCode();
		constructor.visitVarInsn(Opcodes.ALOAD, 0);
		constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		constructor.visitInsn(Opcodes.RETURN);
		constructor.visitMaxs(1, 1);
		constructor.visitEnd();

		MethodVisitor main = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
		main.visitCode();
		main.visitLdcInsn("projectx.log");
		main.visitMethodInsn(Opcodes.INVOKESTATIC, "com/threerings/util/ToolUtil", "configureLog", "(Ljava/lang/String;)V", false);
		main.visitInsn(Opcodes.RETURN);
		main.visitMaxs(1, 1);
		main.visitEnd();

		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void restore(String key, String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}

	private static final class TestApp {
		final Path appDir;
		final Path configJar;
		final Path gameJar;
		final Path lwjglJar;

		TestApp(Path appDir, Path configJar, Path gameJar, Path lwjglJar) {
			this.appDir = appDir;
			this.configJar = configJar;
			this.gameJar = gameJar;
			this.lwjglJar = lwjglJar;
		}
	}
}
