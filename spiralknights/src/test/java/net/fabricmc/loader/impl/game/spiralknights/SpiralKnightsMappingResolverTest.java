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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.SystemProperties;

public class SpiralKnightsMappingResolverTest {
	private static final String VERSION = "20260603121536";

	@TempDir
	Path tempDir;

	private String oldMappingPath;
	private String oldSpiralMappingPath;
	private String oldMappingUrlTemplate;
	private String oldDisableDownload;
	private String oldRefreshMappings;

	@BeforeEach
	public void setUp() {
		oldMappingPath = System.getProperty(SystemProperties.MAPPING_PATH);
		oldSpiralMappingPath = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_PATH);
		oldMappingUrlTemplate = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_URL_TEMPLATE);
		oldDisableDownload = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_DISABLE_MAPPING_DOWNLOAD);
		oldRefreshMappings = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_REFRESH_MAPPINGS);

		System.clearProperty(SystemProperties.MAPPING_PATH);
		System.clearProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_PATH);
		System.clearProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_URL_TEMPLATE);
		System.clearProperty(SystemProperties.SPIRAL_KNIGHTS_DISABLE_MAPPING_DOWNLOAD);
		System.clearProperty(SystemProperties.SPIRAL_KNIGHTS_REFRESH_MAPPINGS);
	}

	@AfterEach
	public void tearDown() {
		restore(SystemProperties.MAPPING_PATH, oldMappingPath);
		restore(SystemProperties.SPIRAL_KNIGHTS_MAPPING_PATH, oldSpiralMappingPath);
		restore(SystemProperties.SPIRAL_KNIGHTS_MAPPING_URL_TEMPLATE, oldMappingUrlTemplate);
		restore(SystemProperties.SPIRAL_KNIGHTS_DISABLE_MAPPING_DOWNLOAD, oldDisableDownload);
		restore(SystemProperties.SPIRAL_KNIGHTS_REFRESH_MAPPINGS, oldRefreshMappings);
	}

	@Test
	public void cacheHitDoesNotDownload() throws IOException {
		Path cachePath = cachePath();
		Files.createDirectories(cachePath.getParent());
		Files.write(cachePath, validTiny());
		AtomicInteger downloads = new AtomicInteger();

		SpiralKnightsMappingResolver resolver = new SpiralKnightsMappingResolver((url, outputPath) -> {
			downloads.incrementAndGet();
			throw new IOException("should not download");
		});

		SpiralKnightsMappingResolver.Result result = resolver.resolve(tempDir, VERSION);

		Assertions.assertTrue(result.isAvailable());
		Assertions.assertEquals(cachePath, result.mappingPath);
		Assertions.assertEquals(0, downloads.get());
	}

	@Test
	public void downloadWritesValidatedCacheFile() {
		SpiralKnightsMappingResolver resolver = new SpiralKnightsMappingResolver((url, outputPath) -> {
			Files.write(outputPath, validTiny());
			return SpiralKnightsMappingResolver.DownloadResult.downloaded();
		});

		SpiralKnightsMappingResolver.Result result = resolver.resolve(tempDir, VERSION);

		Assertions.assertTrue(result.isAvailable());
		Assertions.assertEquals(cachePath(), result.mappingPath);
		Assertions.assertTrue(Files.isRegularFile(cachePath()));
	}

	@Test
	public void notFoundRecordsUnavailableMappings() {
		SpiralKnightsMappingResolver resolver = new SpiralKnightsMappingResolver((url, outputPath) ->
				SpiralKnightsMappingResolver.DownloadResult.notFound());

		SpiralKnightsMappingResolver.Result result = resolver.resolve(tempDir, VERSION);

		Assertions.assertFalse(result.isAvailable());
		Assertions.assertTrue(result.unavailableReason.contains("no mappings exist"));
		Assertions.assertFalse(Files.exists(cachePath()));
	}

	@Test
	public void invalidDownloadedTinyIsRejectedAndCleanedUp() throws IOException {
		SpiralKnightsMappingResolver resolver = new SpiralKnightsMappingResolver((url, outputPath) -> {
			Files.write(outputPath, Arrays.asList("not tiny"));
			return SpiralKnightsMappingResolver.DownloadResult.downloaded();
		});

		Assertions.assertThrows(FormattedException.class, () -> resolver.resolve(tempDir, VERSION));
		Assertions.assertFalse(Files.exists(cachePath()));
		Assertions.assertEquals(0, countTempFiles());
	}

	@Test
	public void tinyMissingRequiredNamespaceIsRejected() throws IOException {
		Path cachePath = cachePath();
		Files.createDirectories(cachePath.getParent());
		Files.write(cachePath, Arrays.asList(
				"tiny\t2\t0\t" + MappingConfiguration.OFFICIAL_NAMESPACE + "\t" + MappingConfiguration.NAMED_NAMESPACE,
				"c\tcom/threerings/example/GameThing\tcom/example/ClassOne"));

		SpiralKnightsMappingResolver resolver = new SpiralKnightsMappingResolver((url, outputPath) ->
				SpiralKnightsMappingResolver.DownloadResult.notFound());

		Assertions.assertThrows(FormattedException.class, () -> resolver.resolve(tempDir, VERSION));
	}

	private Path cachePath() {
		return tempDir.resolve(FabricLoaderImpl.CACHE_DIR_NAME)
				.resolve("mappings")
				.resolve("spiralknights")
				.resolve(MappingConfiguration.INTERMEDIARY_NAMESPACE)
				.resolve(VERSION + ".tiny");
	}

	private int countTempFiles() throws IOException {
		Path cacheDir = cachePath().getParent();
		if (!Files.isDirectory(cacheDir)) return 0;

		try (java.util.stream.Stream<Path> stream = Files.list(cacheDir)) {
			return (int) stream.filter(path -> path.getFileName().toString().endsWith(".tmp")).count();
		}
	}

	private static Iterable<String> validTiny() {
		return Arrays.asList(
				"tiny\t2\t0\t" + MappingConfiguration.OFFICIAL_NAMESPACE + "\t" + MappingConfiguration.INTERMEDIARY_NAMESPACE,
				"c\tcom/threerings/example/GameThing\tcom/example/class_1");
	}

	private static void restore(String key, String value) {
		if (value == null) {
			System.clearProperty(key);
		} else {
			System.setProperty(key, value);
		}
	}
}
