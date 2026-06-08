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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.spiralknights.getdown.GetdownConfig;
import net.fabricmc.loader.impl.game.spiralknights.getdown.GetdownUtil;
import net.fabricmc.loader.impl.launch.knot.KnotClient;
import net.fabricmc.loader.impl.util.ExceptionUtil;
import net.fabricmc.loader.impl.util.SystemProperties;

public final class SpiralKnightsLauncher {
	static final String BOOTSTRAPPED_PROPERTY = "fabric.spiralknights.bootstrapped";
	private static final String KNOT_CLIENT = "net.fabricmc.loader.impl.launch.knot.KnotClient";
	private static final String LOG_CONFIG_CLASS_PROPERTY = "java.util.logging.config.class";

	private SpiralKnightsLauncher() {
	}

	public static void main(String[] args) {
		if (isBootstrapped()) {
			KnotClient.main(args);
			return;
		}

		String appDirProperty = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_APP_DIR);

		if (appDirProperty == null || appDirProperty.isEmpty()) {
			throw new FormattedException("Missing Spiral Knights app directory",
					"Set -D%s=<app dir> before launching %s.",
					SystemProperties.SPIRAL_KNIGHTS_APP_DIR, SpiralKnightsLauncher.class.getName());
		}

		try {
			GetdownConfig config = GetdownConfig.read(Paths.get(appDirProperty));
			ProcessBuilder builder = new ProcessBuilder(buildCommand(config, args));
			builder.directory(config.getAppDir().toFile());
			builder.inheritIO();

			int exitCode = builder.start().waitFor();
			System.exit(exitCode);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new FormattedException("Interrupted while launching Spiral Knights", e);
		} catch (IOException e) {
			throw ExceptionUtil.wrap(e);
		}
	}

	static boolean isBootstrapped() {
		return Boolean.getBoolean(BOOTSTRAPPED_PROPERTY);
	}

	/**
	 * Builds the bootstrapped Fabric command from Getdown's JVM arguments.
	 */
	static List<String> buildCommand(GetdownConfig config, String[] args) {
		List<String> command = new ArrayList<>();
		String version = System.getProperty(SystemProperties.GAME_VERSION, config.getVersion());
		command.add(config.getJavaBinaryPath().toString());

		for (String arg : config.getJvmArgs()) {
			command.add(GetdownUtil.expand(arg, config.getAppDir(), version));
		}

		addLogConfigSystemProperties(command);
		addCurrentSystemProperties(command);

		// Spoof being launched by Getdown.
		addSystemProperty(command, "com.threerings.getdown", "true");

		// Make sure we don't bootstrap again.
		addSystemProperty(command, BOOTSTRAPPED_PROPERTY, "true");

		// Init Fabric.
		command.add("-classpath");
		command.add(getAbsoluteClassPath());
		command.add(KNOT_CLIENT);
		Collections.addAll(command, args);

		return command;
	}

	private static void addLogConfigSystemProperties(List<String> command) {
		String level = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL);
		if (level == null || level.trim().isEmpty()) return;

		try {
			Level.parse(level.trim());
		} catch (IllegalArgumentException e) {
			throw new FormattedException("Invalid Spiral Knights log level",
					"%s must be a java.util.logging level, but got '%s'.",
					SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL, level);
		}

		addSystemProperty(command, LOG_CONFIG_CLASS_PROPERTY, SpiralKnightsLogConfig.class.getName());
	}

	/**
	 * Re-exports Fabric arguments to the provider.
	 */
	private static void addCurrentSystemProperties(List<String> command) {
		for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
			String key = String.valueOf(entry.getKey());

			if (key.startsWith("fabric.")) {
				addSystemProperty(command, key, String.valueOf(entry.getValue()));
			}
		}
	}

	private static void addSystemProperty(List<String> command, String key, String value) {
		command.add("-D" + key + "=" + value);
	}

	private static String getAbsoluteClassPath() {
		String classPath = System.getProperty("java.class.path", "");
		if (classPath.isEmpty()) return classPath;

		Path baseDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
		String[] entries = classPath.split(File.pathSeparator);
		List<String> absoluteEntries = new ArrayList<>(entries.length);

		for (String entry : entries) {
			Path path = Paths.get(entry);

			if (!path.isAbsolute()) {
				path = baseDir.resolve(path);
			}

			absoluteEntries.add(path.normalize().toString());
		}

		return String.join(File.pathSeparator, absoluteEntries);
	}
}
