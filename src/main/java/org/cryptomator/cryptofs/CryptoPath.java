/*******************************************************************************
 * Copyright (c) 2016 Sebastian Stenzel and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE.txt.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.cryptofs;

import static org.cryptomator.cryptofs.Constants.SEPARATOR;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

class CryptoPath implements Path {

	private static final String CURRENT_DIR = ".";
	private static final String PARENT_DIR = "..";

	private final CryptoFileSystemImpl fileSystem;
	private final List<String> elements;
	private final boolean absolute;

	public CryptoPath(CryptoFileSystemImpl fileSystem, List<String> elements, boolean absolute) {
		fileSystem.assertOpen();
		this.fileSystem = fileSystem;
		this.elements = Collections.unmodifiableList(elements);
		this.absolute = absolute;
	}

	public static CryptoPath castAndAssertAbsolute(Path path) {
		CryptoPath result = cast(path);
		if (!result.isAbsolute()) {
			throw new IllegalArgumentException("Path must be absolute but was " + path);
		}
		return result;
	}

	public static CryptoPath cast(Path path) {
		if (path instanceof CryptoPath) {
			CryptoPath cryptoPath = (CryptoPath) path;
			cryptoPath.getFileSystem().assertOpen();
			return cryptoPath;
		} else {
			throw new ProviderMismatchException("Used a path from different provider: " + path);
		}
	}

	@Override
	public CryptoFileSystemImpl getFileSystem() {
		return fileSystem;
	}

	@Override
	public boolean isAbsolute() {
		fileSystem.assertOpen();
		return absolute;
	}

	@Override
	public CryptoPath getRoot() {
		fileSystem.assertOpen();
		return absolute ? fileSystem.getRootPath() : null;
	}

	@Override
	public Path getFileName() {
		fileSystem.assertOpen();
		int elementCount = getNameCount();
		if (elementCount == 0) {
			return null;
		} else {
			return getName(elementCount - 1);
		}
	}

	@Override
	public CryptoPath getParent() {
		fileSystem.assertOpen();
		int elementCount = getNameCount();
		if (elementCount > 1) {
			List<String> elems = elements.subList(0, elementCount - 1);
			return copyWithElements(elems);
		} else {
			return getRoot();
		}
	}

	@Override
	public int getNameCount() {
		fileSystem.assertOpen();
		return elements.size();
	}

	@Override
	public Path getName(int index) {
		fileSystem.assertOpen();
		return subpath(index, index + 1);
	}

	@Override
	public CryptoPath subpath(int beginIndex, int endIndex) {
		fileSystem.assertOpen();
		return new CryptoPath(fileSystem, elements.subList(beginIndex, endIndex), false);
	}

	@Override
	public boolean startsWith(Path path) {
		fileSystem.assertOpen();
		CryptoPath other = cast(path);
		boolean matchesAbsolute = this.isAbsolute() == other.isAbsolute();
		if (matchesAbsolute && other.elements.size() <= this.elements.size()) {
			return this.elements.subList(0, other.elements.size()).equals(other.elements);
		} else {
			return false;
		}
	}

	@Override
	public boolean startsWith(String other) {
		fileSystem.assertOpen();
		return startsWith(fileSystem.getPath(other));
	}

	@Override
	public boolean endsWith(Path path) {
		fileSystem.assertOpen();
		CryptoPath other = cast(path);
		if (other.elements.size() <= this.elements.size()) {
			return this.elements.subList(this.elements.size() - other.elements.size(), this.elements.size()).equals(other.elements);
		} else {
			return false;
		}
	}

	@Override
	public boolean endsWith(String other) {
		fileSystem.assertOpen();
		return endsWith(fileSystem.getPath(other));
	}

	@Override
	public CryptoPath normalize() {
		fileSystem.assertOpen();
		LinkedList<String> normalized = new LinkedList<>();
		for (String elem : elements) {
			String lastElem = normalized.peekLast();
			if (elem.isEmpty() || CURRENT_DIR.equals(elem)) {
				continue;
			} else if (PARENT_DIR.equals(elem) && lastElem != null && !PARENT_DIR.equals(lastElem)) {
				normalized.removeLast();
			} else {
				normalized.add(elem);
			}
		}
		return copyWithElements(normalized);
	}

	@Override
	public Path resolve(Path path) {
		fileSystem.assertOpen();
		CryptoPath other = cast(path);
		if (other.isAbsolute()) {
			return other;
		} else {
			List<String> joined = new ArrayList<>();
			joined.addAll(this.elements);
			joined.addAll(other.elements);
			return copyWithElements(joined);
		}
	}

	@Override
	public Path resolve(String other) {
		fileSystem.assertOpen();
		return resolve(fileSystem.getPath(other));
	}

	@Override
	public Path resolveSibling(Path other) {
		fileSystem.assertOpen();
		final Path parent = getParent();
		if (parent == null || other.isAbsolute()) {
			return other;
		} else {
			return parent.resolve(other);
		}
	}

	@Override
	public Path resolveSibling(String other) {
		fileSystem.assertOpen();
		return resolveSibling(fileSystem.getPath(other));
	}

	@Override
	public Path relativize(Path path) {
		fileSystem.assertOpen();
		CryptoPath normalized = this.normalize();
		CryptoPath other = cast(path).normalize();
		if (normalized.isAbsolute() == other.isAbsolute()) {
			int commonPrefix = countCommonPrefixElements(normalized, other);
			int stepsUp = this.getNameCount() - commonPrefix;
			List<String> elems = new ArrayList<>();
			elems.addAll(Collections.nCopies(stepsUp, PARENT_DIR));
			elems.addAll(other.elements.subList(commonPrefix, other.getNameCount()));
			return copyWithElementsAndAbsolute(elems, false);
		} else {
			throw new IllegalArgumentException("Can't relativize an absolute path relative to a relative path.");
		}
	}

	private int countCommonPrefixElements(CryptoPath p1, CryptoPath p2) {
		int n = Math.min(p1.getNameCount(), p2.getNameCount());
		for (int i = 0; i < n; i++) {
			if (!p1.elements.get(i).equals(p2.elements.get(i))) {
				return i;
			}
		}
		return n;
	}

	@Override
	public URI toUri() {
		fileSystem.assertOpen();
		return CryptoFileSystemUris.createUri(fileSystem.getPathToVault(), elements.toArray(new String[elements.size()]));
	}

	@Override
	public Path toAbsolutePath() {
		fileSystem.assertOpen();
		if (isAbsolute()) {
			return this;
		} else {
			return copyWithAbsolute(true);
		}
	}

	@Override
	public Path toRealPath(LinkOption... options) throws IOException {
		fileSystem.assertOpen();
		return normalize().toAbsolutePath();
	}

	@Override
	public File toFile() {
		fileSystem.assertOpen();
		throw new UnsupportedOperationException();
	}

	@Override
	public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
		fileSystem.assertOpen();
		throw new UnsupportedOperationException("Method not implemented.");
	}

	@Override
	public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
		fileSystem.assertOpen();
		throw new UnsupportedOperationException("Method not implemented.");
	}

	@Override
	public Iterator<Path> iterator() {
		fileSystem.assertOpen();
		return new Iterator<Path>() {

			private int idx = 0;

			@Override
			public boolean hasNext() {
				return idx < getNameCount();
			}

			@Override
			public Path next() {
				return getName(idx++);
			}
		};
	}

	@Override
	public int compareTo(Path path) {
		CryptoPath other = (CryptoPath) path;
		if (this.isAbsolute() != other.isAbsolute()) {
			return this.isAbsolute() ? -1 : 1;
		}
		for (int i = 0; i < Math.min(this.getNameCount(), other.getNameCount()); i++) {
			int result = this.elements.get(i).compareTo(other.elements.get(i));
			if (result != 0) {
				return result;
			}
		}
		return this.getNameCount() - other.getNameCount();
	}

	@Override
	public int hashCode() {
		int hash = 0;
		hash = 31 * hash + fileSystem.hashCode();
		hash = 31 * hash + elements.hashCode();
		hash = 31 * hash + (absolute ? 1 : 0);
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof CryptoPath) {
			CryptoPath other = (CryptoPath) obj;
			return this.fileSystem.equals(other.fileSystem) //
					&& this.compareTo(other) == 0;
		} else {
			return false;
		}
	}

	@Override
	public String toString() {
		String prefix = absolute ? SEPARATOR : "";
		return prefix + String.join(SEPARATOR, elements);
	}

	public CryptoPath copyWithElements(List<String> elements) {
		return new CryptoPath(fileSystem, elements, absolute);
	}

	public CryptoPath copyWithAbsolute(boolean absolute) {
		return new CryptoPath(fileSystem, elements, absolute);
	}

	public CryptoPath copyWithElementsAndAbsolute(List<String> elements, boolean absolute) {
		return new CryptoPath(fileSystem, elements, absolute);
	}

}
