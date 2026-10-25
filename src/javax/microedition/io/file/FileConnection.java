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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;

import javax.microedition.io.StreamConnection;

public interface FileConnection extends StreamConnection
{
	public void create() throws IOException;

	public void mkdir() throws IOException;

	public void delete() throws IOException;

	public void rename(String newName) throws IOException;

	public void setFileConnection(String fileName) throws IOException;

	public void truncate(long byteOffset) throws IOException;

	public Enumeration list() throws IOException;

	public Enumeration list(String filter, boolean includeHidden) throws IOException;

	public boolean isHidden() throws IOException;

	public void setHidden(boolean hidden) throws IOException;

	public boolean canRead() throws IOException;

	public void setReadable(boolean readable) throws IOException;

	public boolean canWrite() throws IOException;

	public void setWritable(boolean writable) throws IOException;

	public boolean isDirectory() throws IOException;

	public boolean isFile() throws IOException;

	public String getName();

	public String getPath();

	public String getURL();

	public String getFileSystemName();

	public long lastModified() throws IOException;

	public long fileSize() throws IOException;

	public long directorySize(boolean includeSubDirs) throws IOException;

	public long totalSize() throws IOException;

	public long usedSize() throws IOException;

	public long availableSize() throws IOException;

	public OutputStream openOutputStream(long byteOffset) throws IOException;

	public InputStream openInputStream() throws IOException;

	public OutputStream openOutputStream() throws IOException;

	public DataInputStream openDataInputStream() throws IOException;

	public DataOutputStream openDataOutputStream() throws IOException;
}
