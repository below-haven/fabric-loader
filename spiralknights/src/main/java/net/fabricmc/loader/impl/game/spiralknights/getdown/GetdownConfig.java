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

package net.fabricmc.loader.impl.game.spiralknights.getdown;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.fabricmc.loader.impl.util.LoaderUtil;

public final class GetdownConfig {
	private final Path appDir;
	private final String version;
	private final String mainClass;
	private final List<Path> classpathJars;
	private final List<String> jvmArgs;
	private final Path javaBinaryPath;

	private GetdownConfig(Path appDir, String version, String mainClass, List<Path> classpathJars, List<String> jvmArgs, Path javaBinaryPath) {
		this.appDir = appDir;
		this.version = version;
		this.mainClass = mainClass;
		this.classpathJars = Collections.unmodifiableList(classpathJars);
		this.jvmArgs = Collections.unmodifiableList(jvmArgs);
		this.javaBinaryPath = javaBinaryPath;
	}

	public static GetdownConfig read(Path appDir) throws IOException {
		appDir = LoaderUtil.normalizeExistingPath(appDir);
		Path configFile = appDir.resolve("getdown.txt");

		if (!Files.isRegularFile(configFile)) {
			throw new IOException("Missing getdown.txt in " + appDir);
		}

		String version = null;
		String mainClass = null;
		List<Path> classpathJars = new ArrayList<>();
		List<String> jvmArgs = new ArrayList<>();

		for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
			line = line.trim();

			//lines that start with # are comments
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}

			//try to parse the key-value
			int pos = line.indexOf('=');
			if (pos < 0) continue;

			String key = line.substring(0, pos).trim();
			String value = line.substring(pos + 1).trim();

			QualifiedValue qualifiedValue = QualifiedValue.parse(value);
			if (!qualifiedValue.appliesToCurrentPlatform()) continue;

			value = qualifiedValue.getValue();

			if (key.equals("version")) {
				version = value;
			} else if (key.equals("class")) {
				mainClass = value;
			} else if (key.equals("code")) {
				//classpath values always start with "code/", to point to the dir where jars are stored
				classpathJars.add(LoaderUtil.normalizePath(appDir.resolve(value)));
			} else if (key.equals("jvmarg")) {
				jvmArgs.add(value);
			} else if (key.equals("ui.mac_dock_icon") && CurrentOs.get() == CurrentOs.MACOS) {
				jvmArgs.add("-Xdock:icon=" + GetdownUtil.toAppRelativePlaceholderPath(appDir, value));
			}
		}

		if (version == null || version.isEmpty()) {
			throw new IOException("Missing version in " + configFile);
		}

		if (mainClass == null || mainClass.isEmpty()) {
			throw new IOException("Missing main class in " + configFile);
		}

		if (classpathJars.isEmpty()) {
			throw new IOException("Missing code entries in " + configFile);
		}

		return new GetdownConfig(appDir, version, mainClass, classpathJars, jvmArgs, GetdownUtil.resolveJavaBinaryPath(appDir));
	}

	public Path getAppDir() {
		return appDir;
	}

	public String getVersion() {
		return version;
	}

	public String getMainClass() {
		return mainClass;
	}

	public List<Path> getClasspathJars() {
		return classpathJars;
	}

	public List<String> getJvmArgs() {
		return jvmArgs;
	}

	public Path getJavaBinaryPath() {
		return javaBinaryPath;
	}
}
