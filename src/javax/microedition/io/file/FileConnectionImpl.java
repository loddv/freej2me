/*
	This file is part of FreeJ2ME.

	FreeJ2ME is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	FreeJ2ME is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with FreeJ2ME.  If not, see http://www.gnu.org/licenses/
*/
package javax.microedition.io.file;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.microedition.io.Connector;

import org.recompile.mobile.MIDletLoader;
import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;

public final class FileConnectionImpl implements FileConnection
{
	private static final String ROOT_NAME = "root1";

	private final Path root;
	private final int mode;
	private final Set<Closeable> streams = new HashSet<Closeable>();
	private Path file;
	private boolean closed;

	public FileConnectionImpl(String url, int mode) throws IOException
	{
		this.mode = mode;
		Path storage = new File("files", getSuiteName()).toPath().resolve(ROOT_NAME).toAbsolutePath().normalize();
		Files.createDirectories(storage);
		root = storage.toRealPath();
		file = resolveUrl(url);
	}

	private static String getSuiteName()
	{
		MobilePlatform platform = Mobile.getPlatform();
		MIDletLoader loader = platform == null ? null : platform.loader;
		String name = loader == null ? null : loader.suitename;
		if(name == null || name.length() == 0)
		{
			return "default";
		}

		String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
		if(safeName.equals(".") || safeName.equals(".."))
		{
			return "default";
		}
		return safeName;
	}

	private Path resolveUrl(String url) throws IOException
	{
		final URI uri;
		try
		{
			uri = new URI(url);
		}
		catch(URISyntaxException e)
		{
			throw new IOException("Invalid file URL: " + url, e);
		}

		if(!"file".equalsIgnoreCase(uri.getScheme()) ||
			(uri.getAuthority() != null && uri.getAuthority().length() > 0 &&
				!"localhost".equalsIgnoreCase(uri.getAuthority())) ||
			uri.getQuery() != null || uri.getFragment() != null)
		{
			throw new IOException("Invalid file URL: " + url);
		}

		String path = uri.getPath();
		if(path == null || !path.startsWith("/" + ROOT_NAME + "/") &&
			!path.equals("/" + ROOT_NAME))
		{
			throw new IOException("File URL must start with /" + ROOT_NAME + "/: " + url);
		}

		String relative = path.length() == ROOT_NAME.length() + 1 ? "" :
			path.substring(ROOT_NAME.length() + 2);
		return resolveRelative(relative);
	}

	private Path resolveRelative(String relative) throws IOException
	{
		Path resolved = root.resolve(relative).normalize();
		checkContained(resolved);
		return resolved;
	}

	private void checkContained(Path candidate) throws IOException
	{
		Path normalized = candidate.toAbsolutePath().normalize();
		if(!normalized.startsWith(root))
		{
			throw new IOException("File path escapes the application storage root");
		}

		Path current = root;
		for(Path segment : root.relativize(normalized))
		{
			current = current.resolve(segment);
			if(Files.exists(current, LinkOption.NOFOLLOW_LINKS))
			{
				if(!current.toRealPath().startsWith(root))
				{
					throw new IOException("File path resolves outside the application storage root");
				}
			}
		}
	}

	private void checkOpen() throws IOException
	{
		if(closed)
		{
			throw new IOException("Connection is closed");
		}
		checkContained(file);
	}

	private void checkMode(int requestedMode) throws IOException
	{
		checkOpen();
		if((mode & requestedMode) != requestedMode)
		{
			throw new IOException("Connection was not opened with the required access mode");
		}
	}

	private synchronized <T extends Closeable> T track(final T stream)
	{
		streams.add(stream);
		return stream;
	}

	private synchronized void untrack(Closeable stream)
	{
		streams.remove(stream);
	}

	private static String getChildName(Path child)
	{
		String name = child.getFileName().toString();
		return Files.isDirectory(child) ? name + "/" : name;
	}

	private static boolean isHidden(Path path) throws IOException
	{
		return Files.isHidden(path);
	}

	private static long sizeOf(Path path) throws IOException
	{
		return Files.isRegularFile(path) ? Files.size(path) : 0;
	}

	private static String toGlob(String filter)
	{
		StringBuilder glob = new StringBuilder();
		for(int i = 0; i < filter.length(); i++)
		{
			char ch = filter.charAt(i);
			if(ch == '*')
			{
				glob.append(".*");
			}
			else if(ch == '?')
			{
				glob.append('.');
			}
			else
			{
				glob.append(Pattern.quote(String.valueOf(ch)));
			}
		}
		return glob.toString();
	}

	public synchronized void close() throws IOException
	{
		if(closed)
		{
			return;
		}
		closed = true;
		IOException failure = null;
		for(Closeable stream : new ArrayList<Closeable>(streams))
		{
			try
			{
				stream.close();
			}
			catch(IOException e)
			{
				if(failure == null)
				{
					failure = e;
				}
				else
				{
					failure.addSuppressed(e);
				}
			}
		}
		streams.clear();
		if(failure != null)
		{
			throw failure;
		}
	}

	public void create() throws IOException
	{
		checkOpen();
		checkMode(Connector.WRITE);
		if(Files.exists(file))
		{
			throw new IOException("File already exists: " + getURL());
		}
		Files.createFile(file);
	}

	public void mkdir() throws IOException
	{
		checkOpen();
		checkMode(Connector.WRITE);
		Files.createDirectory(file);
	}

	public void delete() throws IOException
	{
		checkOpen();
		checkMode(Connector.WRITE);
		Files.delete(file);
	}

	public void rename(String newName) throws IOException
	{
		checkOpen();
		checkMode(Connector.WRITE);
		validateChildName(newName);
		Path destination = file.resolveSibling(newName);
		checkContained(destination);
		Files.move(file, destination);
		file = destination;
	}

	public void setFileConnection(String fileName) throws IOException
	{
		checkOpen();
		validateChildName(fileName);
		Path parent = Files.isDirectory(file) ? file : file.getParent();
		if(parent == null)
		{
			throw new IOException("The storage root has no parent");
		}
		Path destination = parent.resolve(fileName);
		checkContained(destination);
		file = destination;
	}

	private static void validateChildName(String name) throws IOException
	{
		if(name == null || name.length() == 0 || name.equals(".") || name.equals("..") ||
			name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
		{
			throw new IOException("Expected a single file or directory name");
		}
	}

	public void truncate(long byteOffset) throws IOException
	{
		checkMode(Connector.WRITE);
		if(byteOffset < 0)
		{
			throw new IllegalArgumentException("byteOffset must not be negative");
		}
		if(!Files.isRegularFile(file))
		{
			throw new IOException("Not a regular file: " + getURL());
		}
		try(RandomAccessFile access = new RandomAccessFile(file.toFile(), "rw"))
		{
			access.setLength(byteOffset);
		}
	}

	public Enumeration list() throws IOException
	{
		return list("*", true);
	}

	public Enumeration list(String filter, boolean includeHidden) throws IOException
	{
		checkOpen();
		checkMode(Connector.READ);
		if(filter == null)
		{
			throw new NullPointerException("filter");
		}
		if(!Files.isDirectory(file))
		{
			throw new IOException("Not a directory: " + getURL());
		}

		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("regex:" + toGlob(filter));
		List<String> names = new ArrayList<String>();
		try(DirectoryStream<Path> children = Files.newDirectoryStream(file))
		{
			for(Path child : children)
			{
				if((includeHidden || !isHidden(child)) &&
					matcher.matches(FileSystems.getDefault().getPath(child.getFileName().toString())))
				{
					names.add(getChildName(child));
				}
			}
		}
		Collections.sort(names);
		return Collections.enumeration(names);
	}

	public boolean isHidden() throws IOException
	{
		checkOpen();
		return Files.exists(file) && isHidden(file);
	}

	public void setHidden(boolean hidden) throws IOException
	{
		checkOpen();
		checkMode(Connector.WRITE);
		if(!Files.exists(file))
		{
			throw new FileNotFoundException(getURL());
		}
		try
		{
			Files.setAttribute(file, "dos:hidden", Boolean.valueOf(hidden));
		}
		catch(UnsupportedOperationException e)
		{
			throw new IOException("The host file system does not support hidden attributes", e);
		}
	}

	public boolean canRead() throws IOException
	{
		checkOpen();
		return Files.exists(file) && Files.isReadable(file);
	}

	public void setReadable(boolean readable) throws IOException
	{
		checkOpen();
		checkMode(Connector.WRITE);
		if(!file.toFile().setReadable(readable, false))
		{
			throw new IOException("Could not change file read permissions: " + getURL());
		}
	}

	public boolean canWrite() throws IOException
	{
		checkOpen();
		Path target = Files.exists(file) ? file : file.getParent();
		return target != null && Files.isWritable(target);
	}

	public void setWritable(boolean writable) throws IOException
	{
		checkOpen();
		checkMode(Connector.WRITE);
		if(!file.toFile().setWritable(writable, false))
		{
			throw new IOException("Could not change file write permissions: " + getURL());
		}
	}

	public boolean isDirectory() throws IOException
	{
		checkOpen();
		return Files.isDirectory(file);
	}

	public boolean isFile() throws IOException
	{
		checkOpen();
		return Files.isRegularFile(file);
	}

	public String getName()
	{
		if(file.equals(root))
		{
			return "";
		}
		Path name = file.getFileName();
		if(name == null)
		{
			return "";
		}
		return getChildName(file);
	}

	public String getPath()
	{
		String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
		return "/" + ROOT_NAME + "/" + relative +
			(Files.isDirectory(file) && relative.length() > 0 ? "/" : "");
	}

	public String getURL()
	{
		try
		{
			return new URI("file", "", getPath(), null).toASCIIString();
		}
		catch(URISyntaxException e)
		{
			throw new IllegalStateException("Could not format file URL", e);
		}
	}

	public String getFileSystemName()
	{
		return ROOT_NAME;
	}

	public long lastModified() throws IOException
	{
		checkOpen();
		return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
	}

	public long fileSize() throws IOException
	{
		checkOpen();
		if(!Files.exists(file))
		{
			throw new FileNotFoundException(getURL());
		}
		if(Files.isDirectory(file))
		{
			throw new IOException("Cannot get the size of a directory: " + getURL());
		}
		return Files.size(file);
	}

	public long directorySize(boolean includeSubDirs) throws IOException
	{
		checkOpen();
		if(!Files.isDirectory(file))
		{
			throw new IOException("Not a directory: " + getURL());
		}

		long size = 0;
		if(includeSubDirs)
		{
			try(java.util.stream.Stream<Path> paths = Files.walk(file))
			{
				for(Path child : (Iterable<Path>)paths::iterator)
				{
					size += sizeOf(child);
				}
			}
		}
		else
		{
			try(DirectoryStream<Path> children = Files.newDirectoryStream(file))
			{
				for(Path child : children)
				{
					size += sizeOf(child);
				}
			}
		}
		return size;
	}

	private FileStore getFileStore() throws IOException
	{
		checkOpen();
		return Files.getFileStore(root);
	}

	public long totalSize() throws IOException
	{
		return getFileStore().getTotalSpace();
	}

	public long usedSize() throws IOException
	{
		FileStore store = getFileStore();
		return store.getTotalSpace() - store.getUsableSpace();
	}

	public long availableSize() throws IOException
	{
		return getFileStore().getUsableSpace();
	}

	public synchronized InputStream openInputStream() throws IOException
	{
		checkMode(Connector.READ);
		if(!Files.isRegularFile(file))
		{
			throw new FileNotFoundException(getURL());
		}
		final InputStream input = Files.newInputStream(file);
		return track(new FilterInputStream(input)
		{
			public void close() throws IOException
			{
				try
				{
					super.close();
				}
				finally
				{
					untrack(this);
				}
			}
		});
	}

	public synchronized OutputStream openOutputStream() throws IOException
	{
		checkMode(Connector.WRITE);
		if(Files.isDirectory(file))
		{
			throw new IOException("Cannot open a directory for writing: " + getURL());
		}
		Path parent = file.getParent();
		if(parent == null || !Files.isDirectory(parent))
		{
			throw new FileNotFoundException("Parent directory does not exist: " + getURL());
		}
		final OutputStream output = Files.newOutputStream(file,
			StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		return track(new FilterOutputStream(output)
		{
			public void close() throws IOException
			{
				try
				{
					super.close();
				}
				finally
				{
					untrack(this);
				}
			}
		});
	}

	public synchronized OutputStream openOutputStream(long byteOffset) throws IOException
	{
		checkMode(Connector.WRITE);
		if(byteOffset < 0)
		{
			throw new IllegalArgumentException("byteOffset must not be negative");
		}
		if(Files.isDirectory(file))
		{
			throw new IOException("Cannot open a directory for writing: " + getURL());
		}
		if(!Files.exists(file))
		{
			create();
		}
		final RandomAccessFile access = new RandomAccessFile(file.toFile(), "rw");
		try
		{
			if(byteOffset > access.length())
			{
				throw new IOException("byteOffset is beyond the end of the file");
			}
			access.seek(byteOffset);
		}
		catch(IOException e)
		{
			try
			{
				access.close();
			}
			catch(IOException closeException)
			{
				e.addSuppressed(closeException);
			}
			throw e;
		}
		OutputStream output = new OutputStream()
		{
			private boolean streamClosed;

			public void write(int value) throws IOException
			{
				access.write(value);
			}

			public void write(byte[] data, int offset, int length) throws IOException
			{
				access.write(data, offset, length);
			}

			public void close() throws IOException
			{
				if(!streamClosed)
				{
					streamClosed = true;
					try
					{
						access.close();
					}
					finally
					{
						untrack(this);
					}
				}
			}
		};
		return track(output);
	}

	public DataInputStream openDataInputStream() throws IOException
	{
		return new DataInputStream(openInputStream());
	}

	public DataOutputStream openDataOutputStream() throws IOException
	{
		return new DataOutputStream(openOutputStream());
	}
}
