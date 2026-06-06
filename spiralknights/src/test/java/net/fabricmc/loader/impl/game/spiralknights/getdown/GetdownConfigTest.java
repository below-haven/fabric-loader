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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GetdownConfigTest {
	@TempDir
	Path tempDir;

	private String oldOsName;

	@AfterEach
	public void tearDown() {
		if (oldOsName != null) {
			System.setProperty("os.name", oldOsName);
		}
	}

	@Test
	public void preservesCodeOrderAndResolvesThroughCodeDir() throws IOException {
		writeGetdown("version = 20260603121536",
				"class = com.threerings.projectx.client.ProjectXApp",
				"code = code/config.jar",
				"code = code/projectx-pcode.jar",
				"code = code/lwjgl.jar");

		GetdownConfig config = GetdownConfig.read(tempDir);
		Path appDir = tempDir.toRealPath();

		Assertions.assertEquals(Arrays.asList(
				appDir.resolve("code/config.jar"),
				appDir.resolve("code/projectx-pcode.jar"),
				appDir.resolve("code/lwjgl.jar")), config.getClasspathJars());
	}

	@Test
	public void filtersPlatformQualifiedValues() throws IOException {
		setOsName("Mac OS X");
		writeGetdown("version = 1",
				"class = com.threerings.projectx.client.ProjectXApp",
				"code = code/common.jar",
				"code = [mac os x] code/macos.jar",
				"code = [linux] code/linux.jar",
				"code = [windows] code/windows.jar");

		GetdownConfig config = GetdownConfig.read(tempDir);
		Path appDir = tempDir.toRealPath();

		Assertions.assertEquals(Arrays.asList(
				appDir.resolve("code/common.jar"),
				appDir.resolve("code/macos.jar")), config.getClasspathJars());
	}

	@Test
	public void addsMacDockIconJvmArgOnMacOs() throws IOException {
		setOsName("Mac OS X");
		writeGetdown("version = 1",
				"class = com.threerings.projectx.client.ProjectXApp",
				"code = code/projectx-pcode.jar",
				"ui.mac_dock_icon = rsrc/ui/icon/desktop.icns");

		GetdownConfig config = GetdownConfig.read(tempDir);

		Assertions.assertEquals("-Xdock:icon=%APPDIR%/rsrc/ui/icon/desktop.icns", config.getJvmArgs().get(0));
	}

	@Test
	public void expandsAppDirAndVersion() {
		Assertions.assertEquals(tempDir.toAbsolutePath().normalize() + "/rsrc/20260603121536",
				GetdownUtil.expand("%APPDIR%/rsrc/%VERSION%", tempDir, "20260603121536"));
	}

	private void writeGetdown(String... lines) throws IOException {
		Files.createDirectories(tempDir.resolve("code"));
		Files.write(tempDir.resolve("getdown.txt"), Arrays.asList(lines));
	}

	private void setOsName(String osName) {
		if (oldOsName == null) {
			oldOsName = System.getProperty("os.name");
		}

		System.setProperty("os.name", osName);
	}
}
