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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.LogManager;

import net.fabricmc.loader.impl.util.SystemProperties;

public final class SpiralKnightsLogConfig {
	private static final String GLOBAL_LEVEL_PROPERTY = ".level";
	private static final String CONSOLE_HANDLER_LEVEL_PROPERTY = "java.util.logging.ConsoleHandler.level";
	private static final String HANDLERS_PROPERTY = "handlers";

	public SpiralKnightsLogConfig() throws IOException {
		apply();
	}

	static void apply() throws IOException {
		String level = getRequestedLevel();
		if (level == null) return;

		Properties properties = loadBaseConfiguration();
		overrideConfiguredLevels(properties, level);
		applyConfiguration(properties);
	}

	/**
	 * Get the configured Log level for Spiral Knights, if passed.
	 */
	private static String getRequestedLevel() {
		String level = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL);

		if (level == null || level.trim().isEmpty()) {
			return null;
		}

		return Level.parse(level.trim()).getName();
	}

	private static Properties loadBaseConfiguration() throws IOException {
		Properties properties = new Properties();

		try (InputStream input = Files.newInputStream(getConfigurationPath())) {
			properties.load(input);
		}

		return properties;
	}

	private static void overrideConfiguredLevels(Properties properties, String level) {
		properties.setProperty(GLOBAL_LEVEL_PROPERTY, level);
		properties.setProperty(CONSOLE_HANDLER_LEVEL_PROPERTY, level);

		String handlers = properties.getProperty(HANDLERS_PROPERTY);

		if (handlers != null) {
			for (String handler : handlers.split(",")) {
				handler = handler.trim();

				if (!handler.isEmpty()) {
					properties.setProperty(handler + ".level", level);
				}
			}
		}
	}

	private static void applyConfiguration(Properties properties) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		properties.store(output, "Spiral Knights logging configuration");
		LogManager.getLogManager().readConfiguration(new ByteArrayInputStream(output.toByteArray()));
	}

	static Path getConfigurationPath() {
		String configFile = System.getProperty("java.util.logging.config.file");

		if (configFile != null && !configFile.isEmpty()) {
			return Paths.get(configFile);
		}

		return Paths.get(System.getProperty("java.home"), "conf", "logging.properties");
	}
}
