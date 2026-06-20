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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

final class SpiralKnightsMappingResolver {
	static final String DEFAULT_MAPPING_URL_TEMPLATE = "https://raw.githubusercontent.com/below-haven/mapping-chain/refs/heads/main/intermediary/%s.tiny";

	private static final int CONNECT_TIMEOUT_MS = 10_000;
	private static final int READ_TIMEOUT_MS = 20_000;

	private final Downloader downloader;

	SpiralKnightsMappingResolver() {
		this(new HttpDownloader());
	}

	SpiralKnightsMappingResolver(Downloader downloader) {
		this.downloader = downloader;
	}

	Result resolve(Path appDir, String version) {
		if (System.getProperty(SystemProperties.MAPPING_PATH) != null) {
			return Result.systemProperty(version);
		}

		String localOverride = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_PATH);

		if (localOverride != null && !localOverride.isEmpty()) {
			Path path = Paths.get(localOverride).toAbsolutePath().normalize();
			validateOrThrow(path, "local override");

			return Result.available(path, null, null, version, "local override");
		}

		Path cachePath = getCachePath(appDir, version);
		URL url = getMappingUrl(version);
		boolean refresh = SystemProperties.isSet(SystemProperties.SPIRAL_KNIGHTS_REFRESH_MAPPINGS);

		if (Files.isRegularFile(cachePath) && !refresh) {
			validateOrThrow(cachePath, "cached");

			return Result.available(cachePath, cachePath, url, version, "cache");
		}

		if (SystemProperties.isSet(SystemProperties.SPIRAL_KNIGHTS_DISABLE_MAPPING_DOWNLOAD)) {
			return Result.unavailable(cachePath, url, version,
					"mapping download is disabled by " + SystemProperties.SPIRAL_KNIGHTS_DISABLE_MAPPING_DOWNLOAD);
		}

		Path tmpPath = null;

		try {
			Files.createDirectories(cachePath.getParent());
			tmpPath = Files.createTempFile(cachePath.getParent(), version + "-", ".tmp");

			DownloadResult downloadResult = downloader.download(url, tmpPath);

			if (downloadResult.notFound) {
				return Result.unavailable(cachePath, url, version, "no mappings exist for Spiral Knights " + version);
			}

			validateOrThrow(tmpPath, "downloaded");
			moveIntoCache(tmpPath, cachePath);
			tmpPath = null;

			return Result.available(cachePath, cachePath, url, version, "download");
		} catch (IOException e) {
			if (Files.isRegularFile(cachePath)) {
				validateOrThrow(cachePath, "cached fallback");

				return Result.available(cachePath, cachePath, url, version,
						"cache after download failure: " + e.getMessage());
			}

			return Result.unavailable(cachePath, url, version,
					"could not fetch mappings and no cache exists: " + e.getMessage());
		} finally {
			if (tmpPath != null) {
				try {
					Files.deleteIfExists(tmpPath);
				} catch (IOException ignored) {
					// Best effort cleanup; the failed mapping resolution above is the actionable error.
				}
			}
		}
	}

	private static void validateOrThrow(Path path, String source) {
		try {
			validate(path);
		} catch (IOException | RuntimeException e) {
			throw new FormattedException("Invalid Spiral Knights mappings!",
					"Invalid " + source + " Spiral Knights mapping file " + path + ": " + e.getMessage(), e);
		}
	}

	private static void validate(Path path) throws IOException {
		if (!Files.isRegularFile(path)) {
			throw new IOException("file does not exist");
		}

		List<String> namespaces = MappingReader.getNamespaces(path);

		if (!namespaces.contains(MappingConfiguration.OFFICIAL_NAMESPACE)
				|| !namespaces.contains(MappingConfiguration.INTERMEDIARY_NAMESPACE)) {
			throw new IOException("expected namespaces " + MappingConfiguration.OFFICIAL_NAMESPACE
					+ " and " + MappingConfiguration.INTERMEDIARY_NAMESPACE + ", found " + namespaces);
		}

		MemoryMappingTree tree = new MemoryMappingTree();
		MappingReader.read(path, tree);

		if (tree.getClasses().isEmpty()) {
			throw new IOException("mapping file does not contain any class mappings");
		}
	}

	private static Path getCachePath(Path appDir, String version) {
		return appDir.resolve(FabricLoaderImpl.CACHE_DIR_NAME)
				.resolve("mappings")
				.resolve("spiralknights")
				.resolve(MappingConfiguration.INTERMEDIARY_NAMESPACE)
				.resolve(version + ".tiny");
	}

	private static URL getMappingUrl(String version) {
		String template = System.getProperty(SystemProperties.SPIRAL_KNIGHTS_MAPPING_URL_TEMPLATE, DEFAULT_MAPPING_URL_TEMPLATE);

		try {
			return new URL(String.format(Locale.ROOT, template, version));
		} catch (IOException e) {
			throw new FormattedException("Invalid Spiral Knights mapping URL!",
					"Invalid Spiral Knights mapping URL template " + template + ": " + e.getMessage(), e);
		}
	}

	private static void moveIntoCache(Path tmpPath, Path cachePath) throws IOException {
		try {
			Files.move(tmpPath, cachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmpPath, cachePath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	interface Downloader {
		DownloadResult download(URL url, Path outputPath) throws IOException;
	}

	static final class DownloadResult {
		final boolean notFound;

		private DownloadResult(boolean notFound) {
			this.notFound = notFound;
		}

		static DownloadResult downloaded() {
			return new DownloadResult(false);
		}

		static DownloadResult notFound() {
			return new DownloadResult(true);
		}
	}

	static final class Result {
		final Path mappingPath;
		final Path cachePath;
		final URL url;
		final String version;
		final String source;
		final String unavailableReason;
		final boolean systemProperty;

		private Result(Path mappingPath, Path cachePath, URL url, String version, String source, String unavailableReason, boolean systemProperty) {
			this.mappingPath = mappingPath;
			this.cachePath = cachePath;
			this.url = url;
			this.version = version;
			this.source = source;
			this.unavailableReason = unavailableReason;
			this.systemProperty = systemProperty;
		}

		static Result systemProperty(String version) {
			return new Result(null, null, null, version, "system property", null, true);
		}

		static Result available(Path mappingPath, Path cachePath, URL url, String version, String source) {
			return new Result(mappingPath, cachePath, url, version, source, null, false);
		}

		static Result unavailable(Path cachePath, URL url, String version, String unavailableReason) {
			return new Result(null, cachePath, url, version, null, unavailableReason, false);
		}

		boolean isAvailable() {
			return mappingPath != null;
		}
	}

	private static final class HttpDownloader implements Downloader {
		@Override
		public DownloadResult download(URL url, Path outputPath) throws IOException {
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setInstanceFollowRedirects(true);
			connection.setRequestProperty("Cache-Control", "no-cache");

			try {
				int responseCode = connection.getResponseCode();

				if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
					return DownloadResult.notFound();
				}

				if (responseCode != HttpURLConnection.HTTP_OK) {
					throw new IOException("unexpected HTTP response " + responseCode);
				}

				try (InputStream is = connection.getInputStream()) {
					Files.copy(is, outputPath, StandardCopyOption.REPLACE_EXISTING);
				}

				return DownloadResult.downloaded();
			} finally {
				connection.disconnect();
			}
		}
	}
}
