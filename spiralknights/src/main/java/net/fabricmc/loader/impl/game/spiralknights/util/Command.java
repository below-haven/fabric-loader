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

package net.fabricmc.loader.impl.game.spiralknights.util;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.spiralknights.SpiralKnightsLogConfig;
import net.fabricmc.loader.impl.util.SystemProperties;

public class Command {
	/**
	 * Override the global log level, if the option to do so is set.
	 */
	public static void addLogConfigSystemProperties(List<String> command) {
		String level = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL);
		if (level == null || level.trim().isEmpty()) return;

		try {
			Level.parse(level.trim());
		} catch (IllegalArgumentException e) {
			throw new FormattedException("Invalid Spiral Knights log level",
					"%s must be a java.util.logging level, but got '%s'.",
					SystemProperties.SPIRAL_KNIGHTS_LOG_LEVEL, level);
		}

		//https://docs.oracle.com/en/java/javase/25/docs/api/java.logging/java/util/logging/LogManager.html
		//This is kinda hacky, but we need to set up our log level before everything else
		addSystemProperty(command, "java.util.logging.config.class", SpiralKnightsLogConfig.class.getName());
	}

	/**
	 * Re-exports Fabric arguments to the GameProvider.
	 */
	public static void addCurrentSystemProperties(List<String> command) {
		for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
			String key = String.valueOf(entry.getKey());

			if (key.startsWith("fabric.")) {
				addSystemProperty(command, key, String.valueOf(entry.getValue()));
			}
		}
	}

	public static void addSystemProperty(List<String> command, String key, String value) {
		command.add("-D" + key + "=" + value);
	}
}
