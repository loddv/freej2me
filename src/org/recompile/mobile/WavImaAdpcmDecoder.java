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
package org.recompile.mobile;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.util.Arrays;

public class WavImaAdpcmDecoder
{

	/* Information about this audio format: https://wiki.multimedia.cx/index.php/IMA_ADPCM */

	private static final int LEFTCHANNEL = 0;
	private static final int RIGHTCHANNEL = 1;

	/* Canonical wav header, written back in front of the decoded samples. */
	private static final int HEADERSIZE = 44;

	/* Every channel has a 4-byte preamble per block: predictor (16-bit), step index and a reserved byte. */
	private static final int PREAMBLEBYTES = 4;

	/* Microsoft's layout has each channel contributing 4 bytes (8 nibbles) before the next channel continues. */
	private static final int GROUPBYTES = 4;

	/* Limits that keep a malformed file from making the decoder run away. */
	private static final int MAXCHUNKS = 64;
	private static final int MAXSKIPBYTES = 8 * 1024 * 1024;

	/* 
	 * Variables to hold the previously decoded sample and step used, per channel (if needed) 
	 * "NOTE: Arrays that won't be reassigned (but its values can still change) and variables 
	 * that won't be changed are marked as final throughout the code to try and optimize the 
	 * execution as much as possible since FreeJ2ME has a habit of freezing when adpcm samples 
	 * are being decoded. So far only seems to happen in Java 8 and on my more limited devices."
	 *     - @AShiningRay
	 *
	 * They are per instance (and not per class) because every player owns its own decoder and
	 * two players can be prepared from different threads. While they were static, one decode
	 * stepped on the other's predictor, and the result was noise instead of an error.
	 */
	private final int[] prevSample = {0, 0};
	private final int[] prevStep = {0, 0};
	
	private static final int[] ima_step_index_table = 
	{
		-1, -1, -1, -1, 2, 4, 6, 8, 
		-1, -1, -1, -1, 2, 4, 6, 8
	};
	
	private static final int[] ima_step_size_table = 
	{
		7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
		19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
		50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
		130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
		337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
		876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
		2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
		5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
		15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
	};

	/* Everything the chunk walk finds out about the stream, which is what the block loop needs. */
	private static final class RiffInfo
	{
		private final int channels;
		private final int sampleRate;
		private final int blockAlign;
		private final int dataSize;

		private RiffInfo(int channels, int sampleRate, int blockAlign, int dataSize)
		{
			this.channels = channels;
			this.sampleRate = sampleRate;
			this.blockAlign = blockAlign;
			this.dataSize = dataSize;
		}
	}

	/*
		The header of a WAV (RIFF) file is 44 bytes long and has the following format:

		CHAR[4] "RIFF" header
		UINT32  Size of the file (chunkSize).
		  CHAR[4] "WAVE" format
			CHAR[4] "fmt " header
			UINT32  SubChunkSize (examples: 16 for plain PCM, 20 for ADPCM with cbSize )
			  UINT16 AudioFormat (ex: 1 [PCM], 17 [IMA ADPCM] )
			  UINT16 NumChannels
			  UINT32 SampleRate
			  UINT32 BytesPerSec (samplerate*frame size)
			  UINT16 frameSize or blockAlign (256 on some gameloft games)
			  UINT16 BitsPerSample (gameloft games appear to use 4)
			CHAR[4] "data" header
			UINT32 Length of sample data.
			<Sample data>

		Files in the wild are not always that canonical: "fact" and "LIST" chunks show up before
		"data", and a larger "fmt " chunk pushes the samples further down. This probe only reads
		the first 44 bytes to tell the caller which codec it is dealing with, and the chunks are
		walked for real in decodeImaAdpcm, which gets the stream from its very start.

		NOTE: It must not read past 44 bytes because PlatformPlayer marks the stream with mark(48)
		before calling it, and reading past that limit makes the following reset() throw.
	*/
	public int[] readHeader(InputStream input) throws IOException 
	{
		/* The reads are what advance the stream by exactly 44 bytes, so none of them can be skipped */
		readInputStreamASCII(input, 4);              /* "RIFF" */
		readInputStreamInt32(input);                 /* file size */
		readInputStreamASCII(input, 4);              /* "WAVE" */
		readInputStreamASCII(input, 4);              /* "fmt " */
		readInputStreamInt32(input);                 /* fmt chunk size */
		short audioFormat = (short) readInputStreamInt16(input);
		short audioChannels = (short) readInputStreamInt16(input);
		int sampleRate = readInputStreamInt32(input);
		readInputStreamInt32(input);                 /* byte rate */
		short frameSize = (short) readInputStreamInt16(input);
		readInputStreamInt16(input);                 /* bits per sample */
		readInputStreamASCII(input, 4);              /* "data" */
		readInputStreamInt32(input);                 /* data size */

		/* 
		 * We need the audio format to check if it's ADPCM or PCM, and the file's 
		 * SampleRate and audioChannels to decode ADPCM and build a new header. 
		 */
		return new int[] {audioFormat, sampleRate, audioChannels, frameSize};
	}

	/* 
	 * Decodes the IMA ADPCM stream into a canonical PCM16LE wav stream, which is what the rest
	 * of the audio path expects, be it the shared wav mixer or the clip fallback.
	 */
	public ByteArrayInputStream decodeImaAdpcm(InputStream stream, int[] wavHeaderData) throws IOException
	{
		final RiffInfo info = findDataChunk(stream, wavHeaderData);

		final int channels = info.channels;
		final int blockAlign = info.blockAlign;

		if(channels != 1 && channels != 2)
		{
			throw new IOException("Unsupported IMA ADPCM channel count: " + channels);
		}
		if(blockAlign <= PREAMBLEBYTES * channels || blockAlign % channels != 0)
		{
			throw new IOException("Invalid IMA ADPCM block alignment: " + blockAlign);
		}

		/* Each channel has its own preamble and the rest of the block is split in interleave groups */
		final int groupsPerBlock = (blockAlign - (PREAMBLEBYTES * channels)) / (GROUPBYTES * channels);
		if(groupsPerBlock <= 0)
		{
			throw new IOException("IMA ADPCM block has no samples: " + blockAlign);
		}

		/* 
		 * A group holds 8 nibbles per channel (16 bytes decoded), so this is the amount of PCM a
		 * complete block turns into. Every block decodes to whole frames, never to half a sample.
		 */
		final int bytesPerBlock = groupsPerBlock * (GROUPBYTES * 2) * channels * 2;

		/* 
		 * The declared data size bounds the output when it is sane, which is the normal case: the
		 * array is then allocated exactly and nothing has to be copied afterwards. A file that lies
		 * about it falls back to what the stream reports, and only then the result is trimmed.
		 */
		final int available = stream.available();
		int dataSize = info.dataSize;

		if(dataSize <= 0) { dataSize = Math.max(0, available); }
		else if(available > 0 && dataSize > available) { dataSize = available; }

		final boolean partialBlock = (dataSize % blockAlign) != 0;
		final byte[] output = new byte[HEADERSIZE + ((dataSize / blockAlign) + (partialBlock ? 1 : 0)) * bytesPerBlock];

		final byte[] block = new byte[blockAlign];
		int remaining = dataSize;
		int outputIndex = HEADERSIZE;

		while(remaining > 0)
		{
			final int wanted = Math.min(blockAlign, remaining);
			final int read = readBlock(stream, block, wanted);
			if(read <= 0) { break; }

			/* A truncated last block still decodes whichever groups it holds completely */
			int groups = 0;
			if(read > PREAMBLEBYTES * channels)
			{
				groups = (read - (PREAMBLEBYTES * channels)) / (GROUPBYTES * channels);
			}

			outputIndex += decodeBlock(block, groups, channels, output, outputIndex);
			remaining -= read;

			if(read < wanted) { break; }
		}

		/* 
		 * When blocks came out short (or missing, if the file lied) the array is longer than the
		 * decoded audio. Trimming keeps the header and the samples matching, and keeps the pcm a
		 * multiple of the frame size, which is how the wav mixer walks through the buffer.
		 */
		final byte[] pcm = outputIndex == output.length ? output : Arrays.copyOf(output, outputIndex);

		buildHeader(pcm, channels, info.sampleRate); /* Builds a new header for the decoded stream. */
		return new ByteArrayInputStream(pcm);
	}

	/* 
	 * Builds a WAV header that describes the decoded ADPCM file on the first 44 bytes. 
	 * Data: little-endian, 16-bit, signed, same sample rate and channels as source IMA ADPCM.
	 */
	private void buildHeader(final byte[] buffer, final int numChannels, final int sampleRate) 
	{ 
		final short bitsPerSample = 16;   /* 16-bit PCM */
		final short audioFormat = 1;      /* WAV linear PCM */
		final int subChunkSize = 16;      /* Fixed size for Wav Linear PCM */
		final int chunk = 0x52494646;     /* 'RIFF' */ 
		final int format = 0x57415645;    /* 'WAVE' */ 
		final int subChunk1 = 0x666d7420; /* 'fmt ' */ 
		final int subChunk2 = 0x64617461; /* 'data' */ 

		/* 
		 * Frame size is fairly standard, and PCM's fixed sample size makes it so the frameSize is
		 * either 2 bytes for mono, or 4 bytes for stereo.
		 */
		final short frameSize = (short) (numChannels * (bitsPerSample / 8));

		/* Whatever comes after the header is sample data, already in whole frames */
		final int sampleDataLength = buffer.length - HEADERSIZE;

		/* 
		 * Represents how many bytes are streamed per second. With all of the data above, it's trivial
		 * to calculate by getting the sample rate, the amount of channels and bytes per sample
		 */
		final int bytesPerSec = sampleRate * frameSize;
		
		/* NOTE: ChunkSize is everything after it, so the sample data plus the rest of the header */

		/* ChunkID */
		ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.BIG_ENDIAN).putInt(chunk);
		/* ChunkSize (or File size, which is everything minus this field and the chunk id) */
		ByteBuffer.wrap(buffer, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleDataLength + HEADERSIZE - 8);
		/* Format (WAVE) */
		ByteBuffer.wrap(buffer, 8, 4).order(ByteOrder.BIG_ENDIAN).putInt(format);
		/* SubchunkID (fmt) */
		ByteBuffer.wrap(buffer, 12, 4).order(ByteOrder.BIG_ENDIAN).putInt(subChunk1);
		/* SubchunkSize (or format chunk size) */
		ByteBuffer.wrap(buffer, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(subChunkSize);
		/* Audioformat */
		ByteBuffer.wrap(buffer, 20, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(audioFormat);
		/* NumChannels (will be the same as source ADPCM) */
		ByteBuffer.wrap(buffer, 22, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) numChannels);
		/* SampleRate (will be the same as source ADPCM) */
		ByteBuffer.wrap(buffer, 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate);
		/* ByteRate (BytesPerSec) */
		ByteBuffer.wrap(buffer, 28, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytesPerSec);
		/* BlockAlign (Frame Size) */
		ByteBuffer.wrap(buffer, 32, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(frameSize);
		/* BitsPerSample */
		ByteBuffer.wrap(buffer, 34, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(bitsPerSample);
		/* Subchunk2ID (data) */
		ByteBuffer.wrap(buffer, 36, 4).order(ByteOrder.BIG_ENDIAN).putInt(subChunk2);
		/* Subchunk2 Size (sampledata length) */
		ByteBuffer.wrap(buffer, 40, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleDataLength);
	}

	/* 
	 * Decodes the preambles and the interleaved groups of one block straight into 'output'.
	 * Returns how many bytes were written, which is always a whole number of frames.
	 */
	private int decodeBlock(byte[] block, int groups, int channels, byte[] output, int outputIndex)
	{
		if(groups <= 0) { return 0; }

		int inputIndex = 0;

		for(int channel = 0; channel < channels; channel++)
		{
			/* Bytes 0 and 1 are the block's initial predictor (little-endian, signed) */
			prevSample[channel] = (short) ((block[inputIndex] & 0xFF) | ((block[inputIndex + 1] & 0xFF) << 8));

			/* Byte 2 is the block's initial index on the step size table, byte 3 is reserved */
			prevStep[channel] = block[inputIndex + 2] & 0xFF;
			if(prevStep[channel] > 88) { prevStep[channel] = 88; }

			inputIndex += PREAMBLEBYTES;
		}

		for(int group = 0; group < groups; group++)
		{
			/* A group has GROUPBYTES of packed nibbles for each channel, in channel order */
			for(int channel = 0; channel < channels; channel++)
			{
				for(int i = 0; i < GROUPBYTES; i++)
				{
					final int packed = block[inputIndex++] & 0xFF;

					/* The low nibble of every byte is decoded before the high one */
					final short low = decodeSample(channel, (byte)(packed & 0x0F));
					final short high = decodeSample(channel, (byte)((packed >> 4) & 0x0F));

					final int frame = (group * (GROUPBYTES * 2)) + (i * 2);
					final int lowOffset = outputIndex + ((frame * channels) + channel) * 2;

					output[lowOffset] = (byte)(low & 0xFF);
					output[lowOffset + 1] = (byte)((low >> 8) & 0xFF);
					output[lowOffset + (channels * 2)] = (byte)(high & 0xFF);
					output[lowOffset + (channels * 2) + 1] = (byte)((high >> 8) & 0xFF);
				}
			}
		}

		return groups * (GROUPBYTES * 2) * channels * 2;
	}

	/* This method will decode a single IMA ADPCM sample to linear PCM_S16LE sample. */
	private short decodeSample(final int channel, byte adpcmSample)
	{
		/* 
		 * This decode procedure is based on the following document:
		 * https://www.cs.columbia.edu/~hgs/audio/dvi/IMA_ADPCM.pdf
		 */

		/* Get the step size to be used when decoding the given sample. */
		int stepSize = ima_step_size_table[prevStep[channel]] & 0x0000FFFF;

		/* This variable acts as 'difference' and then 'newSample' on columbia's doc */
		int decodedSample = (stepSize >> 3) & 0x1fff;
		
		/* 
		 * Similar to cs.columbia doc's first code block on Page 32, multiplication through 
		 * repetitive addition. Nowadays using multiplication is probably faster on most archs, 
		 * but let's follow the reference implementation for the sake of improving documentation.
		 */
		if ((adpcmSample & 4) != 0) { decodedSample += stepSize; }
		if ((adpcmSample & 2) != 0) { decodedSample += (stepSize >> 1) & 0x7fff; }
		if ((adpcmSample & 1) != 0) { decodedSample += (stepSize >> 2) & 0x3fff; }
		if ((adpcmSample & 8) != 0) { decodedSample  = -decodedSample; }
		
		decodedSample += prevSample[channel];

		/* 
		 * Clamps the value of decodedSample to that of a short data type. At this point, the decoded 
		 * sample already should already fit nicely into a short type value range as per columbia's doc.
		 */
		if (decodedSample < -32768)     { decodedSample = -32768; }
		else if (decodedSample > 32767) { decodedSample = 32767; }

		prevSample[channel] = decodedSample;

		/* Basically columbia doc's "calculate stepsize" snippet */
		prevStep[channel] += ima_step_index_table[(int)(adpcmSample & 0x0FF)];
		if (prevStep[channel] < 0)       { prevStep[channel] = 0; }
		else if (prevStep[channel] > 88) { prevStep[channel] = 88; }

		/* Return the decoded sample casted to short (16-bit) */
		return (short) (decodedSample & 0xFFFF);
	}

	/* 
	 * Walks the RIFF chunks until the sample data, which is where the decoder has to start. The
	 * canonical 44-byte layout can't be assumed: 'fact' and 'LIST' chunks, or a larger 'fmt ',
	 * push the samples further down, and decoding those bytes as audio is pure noise.
	 */
	private RiffInfo findDataChunk(InputStream stream, int[] wavHeaderData) throws IOException
	{
		if(!readInputStreamASCII(stream, 4).equals("RIFF")) { throw new IOException("Stream is not a RIFF file"); }
		readInputStreamInt32(stream); /* File size: files in the wild lie about it */
		if(!readInputStreamASCII(stream, 4).equals("WAVE")) { throw new IOException("Stream is not a WAVE file"); }

		boolean sawFormat = false;
		int channels = 0;
		int sampleRate = 0;
		int blockAlign = 0;

		for(int i = 0; i < MAXCHUNKS; i++)
		{
			final String id = readInputStreamASCII(stream, 4);
			final int size = readInputStreamInt32(stream);

			if(size < 0) { throw new IOException("Invalid size for chunk '" + id + "': " + size); }

			if(id.equals("fmt "))
			{
				if(size < 16) { throw new IOException("Invalid 'fmt ' chunk size: " + size); }

				sawFormat = true;
				readInputStreamInt16(stream); /* Audio format, the caller's probe already checked it */
				channels = readInputStreamInt16(stream);
				sampleRate = readInputStreamInt32(stream);
				readInputStreamInt32(stream); /* Byte rate */
				blockAlign = readInputStreamInt16(stream);
				readInputStreamInt16(stream); /* Bits per sample, 4 on adpcm files */

				/* cbSize and samplesPerBlock, plus the padding byte of an odd sized chunk */
				skipChunk(stream, size - 16);
			}
			else if(id.equals("data"))
			{
				/* Fall back to the 44-byte probe if 'fmt ' was missing or unusable */
				if(!sawFormat || channels <= 0) { channels = wavHeaderData[2]; }
				if(sampleRate <= 0) { sampleRate = wavHeaderData[1]; }
				if(blockAlign <= 0) { blockAlign = wavHeaderData[3]; }

				return new RiffInfo(channels, sampleRate, blockAlign, size);
			}
			else
			{
				/* 'fact', 'LIST' and friends: skip their payload and their padding byte */
				skipChunk(stream, size);
			}
		}

		throw new IOException("No 'data' chunk found in the RIFF stream");
	}

	/* Skips a chunk payload, including the padding byte that keeps the next chunk word aligned. */
	private void skipChunk(InputStream stream, int size) throws IOException
	{
		if(size < 0 || size > MAXSKIPBYTES) { throw new IOException("Refusing to skip " + size + " bytes"); }

		skipBytes(stream, size);
		if((size & 1) != 0) { skipBytes(stream, 1); }
	}

	private void skipBytes(InputStream stream, int count) throws IOException
	{
		while(count > 0)
		{
			final long skipped = stream.skip(count);

			if(skipped > 0) { count -= (int) skipped; }
			else if(stream.read() < 0) { throw new java.io.EOFException(); }
			else { count--; }
		}
	}

	/* Reads 'length' bytes unless the stream ends first, and returns how many were actually read. */
	private static int readBlock(InputStream stream, byte[] buffer, int length) throws IOException
	{
		int read = 0;

		while(read < length)
		{
			final int count = stream.read(buffer, read, length - read);
			if(count < 0) { break; }
			read += count;
		}

		return read;
	}

	/* Read a 16-bit little-endian unsigned integer from input.*/
	private static int readInputStreamInt16(InputStream input) throws IOException 
	{ return ( input.read() & 0xFF ) | ( ( input.read() & 0xFF ) << 8 ); }

	/* Read a 32-bit little-endian signed integer from input.*/
	private static int readInputStreamInt32(InputStream input) throws IOException 
	{
		return ( input.read() & 0xFF ) | ( ( input.read() & 0xFF ) << 8 )
			| ( ( input.read() & 0xFF ) << 16 ) | ( ( input.read() & 0xFF ) << 24 );
	}

	/* Return a String containing 'n' Characters of ASCII/ISO-8859-1 text from input. */
	private static String readInputStreamASCII(InputStream input, int nChars) throws IOException 
	{
		byte[] chars = new byte[nChars];
		readInputStreamData(input, chars, 0, nChars);
		return new String(chars, "ISO-8859-1");
	}

	/* Read 'n' Bytes from the InputStream starting from the specified offset into the output array. */
	private static void readInputStreamData(InputStream input, byte[] output, int offset, int nBytes) throws IOException 
	{
		int end = offset + nBytes;
		while(offset < end) 
		{
			int read = input.read(output, offset, end - offset);
			if(read < 0) throw new java.io.EOFException();
			offset += read;
		}
	}

}
