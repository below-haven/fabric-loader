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

package net.fabricmc.loader.impl.util;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Map;
import java.util.zip.ZipError;

public final class FileSystemUtil {
	public static class FileSystemDelegate implements AutoCloseable {
		private final FileSystem fileSystem;
		private final boolean owner;

		public FileSystemDelegate(FileSystem fileSystem, boolean owner) {
			this.fileSystem = fileSystem;
			this.owner = owner;
		}

		public FileSystem get() {
			return fileSystem;
		}

		@Override
		public void close() throws IOException {
			if (owner) {
				fileSystem.close();
			}
		}
	}

	private FileSystemUtil() { }

	private static final Map<String, String> jfsArgsCreate = Collections.singletonMap("create", "true");
	private static final Map<String, String> jfsArgsEmpty = Collections.emptyMap();
	private static volatile FileSystemProvider bundledZipFileSystemProvider;

	public static FileSystemDelegate getJarFileSystem(Path path, boolean create) throws IOException {
		return getJarFileSystem(path.toUri(), create);
	}

	public static FileSystemDelegate getJarFileSystem(URI uri, boolean create) throws IOException {
		URI jarUri;

		try {
			jarUri = new URI("jar:" + uri.getScheme(), uri.getHost(), uri.getPath(), uri.getFragment());
		} catch (URISyntaxException e) {
			throw new IOException(e);
		}

		boolean opened = false;
		FileSystem ret = null;

		try {
			ret = FileSystems.getFileSystem(jarUri);
		} catch (ProviderNotFoundException e) {
			return getBundledJarFileSystem(jarUri, create, uri, e);
		} catch (FileSystemNotFoundException ignore) {
			try {
				ret = FileSystems.newFileSystem(jarUri, create ? jfsArgsCreate : jfsArgsEmpty);
				opened = true;
			} catch (ProviderNotFoundException e) {
				return getBundledJarFileSystem(jarUri, create, uri, e);
			} catch (FileSystemAlreadyExistsException ignore2) {
				ret = FileSystems.getFileSystem(jarUri);
			} catch (IOException | ZipError e) {
				throw new IOException("Error accessing "+uri+": "+e, e);
			}
		}

		return new FileSystemDelegate(ret, opened);
	}

	private static FileSystemDelegate getBundledJarFileSystem(URI jarUri, boolean create, URI sourceUri,
			ProviderNotFoundException providerException) throws IOException {
		FileSystemProvider provider = getBundledZipFileSystemProvider(providerException);
		boolean opened = false;
		FileSystem ret = null;

		try {
			ret = provider.getFileSystem(jarUri);
		} catch (FileSystemNotFoundException ignore) {
			try {
				ret = provider.newFileSystem(jarUri, create ? jfsArgsCreate : jfsArgsEmpty);
				opened = true;
			} catch (FileSystemAlreadyExistsException ignore2) {
				ret = provider.getFileSystem(jarUri);
			} catch (IOException | ZipError e) {
				throw new IOException("Error accessing "+sourceUri+": "+e, e);
			}
		}

		return new FileSystemDelegate(ret, opened);
	}

	private static FileSystemProvider getBundledZipFileSystemProvider(ProviderNotFoundException providerException)
			throws IOException {
		FileSystemProvider ret = bundledZipFileSystemProvider;
		if (ret != null) return ret;

		synchronized (FileSystemUtil.class) {
			ret = bundledZipFileSystemProvider;
			if (ret != null) return ret;

			try {
				ret = Class.forName("jdk.nio.zipfs.ZipFileSystemProvider", true, FileSystemUtil.class.getClassLoader())
						.asSubclass(FileSystemProvider.class)
						.getConstructor()
						.newInstance();
			} catch (ReflectiveOperationException | LinkageError e) {
				IOException ioException = new IOException("The Java runtime does not provide the jar file system provider, "
						+ "and Fabric Loader's bundled jdk.zipfs provider could not be loaded.", e);
				ioException.addSuppressed(providerException);
				throw ioException;
			}

			bundledZipFileSystemProvider = ret;
			return ret;
		}
	}
}
