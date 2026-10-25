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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/*

	WavMixer

	Keeps one SourceDataLine open per audio format and mixes every active
	sound on it.

	Java Sound's Clip opens its device line with a one second buffer, and
	that buffer is what puts a fixed delay between the game's action and
	the sound the player hears. Here the line is opened with a small buffer
	and kept alive, so a sound starts on the next mixing block instead of
	waiting for a device buffer to be filled. It also means that any number
	of sound effects share the same device line instead of opening one
	stream each.

*/

public final class WavMixer
{

	/* Line buffer: 40ms is enough to survive scheduling hiccups without adding audible delay */
	private static final float LINE_BUFFER_SECONDS = 0.04f;

	/* Mixing block: 10ms */
	private static final float BLOCK_SECONDS = 0.01f;

	private static final List<WavMixer> mixers = new ArrayList<WavMixer>();

	private final Object lock = new Object();

	private final AudioFormat format;

	private final List<Sound> sounds = new ArrayList<Sound>();

	private SourceDataLine line;

	private boolean unavailable;

	private boolean mixing;

	private WavMixer(AudioFormat format)
	{
		this.format = format;
	}

	/* One mixer per format, so that the device line is opened only once. */
	public static synchronized WavMixer get(AudioFormat format)
	{
		for(int i = 0; i < mixers.size(); i++)
		{
			WavMixer mixer = mixers.get(i);
			if(mixer.format.getSampleRate() == format.getSampleRate()
				&& mixer.format.getChannels() == format.getChannels())
			{
				return mixer;
			}
		}

		WavMixer mixer = new WavMixer(format);
		mixers.add(mixer);
		return mixer;
	}

	/* Opens (or reuses) the shared line. Must be called before play(). */
	public boolean isAvailable()
	{
		synchronized(lock)
		{
			if(line != null) { return true; }
			if(unavailable) { return false; }
			return openLine();
		}
	}

	private boolean openLine()
	{
		try
		{
			SourceDataLine candidate = (SourceDataLine) AudioSystem.getLine(
				new DataLine.Info(SourceDataLine.class, format));

			int bufferSize = (int)(format.getFrameSize() * format.getFrameRate() * LINE_BUFFER_SECONDS);
			try
			{
				candidate.open(format, bufferSize);
			}
			catch (IllegalArgumentException e) /* Buffer size not supported, let the mixer decide */
			{
				candidate.open(format);
			}
			candidate.start();

			line = candidate;
			return true;
		}
		catch (Exception e)
		{
			System.out.println("Couldn't open shared audio line: " + e.getMessage());
			unavailable = true;
			return false;
		}
	}

	public Sound create(byte[] pcm)
	{
		return new Sound(this, format, pcm);
	}

	/* Puts a sound back into the mix. */
	void queue(Sound sound)
	{
		synchronized(lock)
		{
			if(!sound.queued)
			{
				sound.queued = true;
				sounds.add(sound);
			}
		}
		ensureMixing();
	}

	void remove(Sound sound)
	{
		synchronized(lock)
		{
			sound.queued = false;
			sounds.remove(sound);
		}
	}

	private void ensureMixing()
	{
		synchronized(lock)
		{
			if(mixing || line == null) { return; }

			mixing = true;
			Thread thread = new Thread(new Runnable()
			{
				public void run()
				{
					mix();
				}
			}, "FreeJ2ME WavMixer");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private void mix()
	{
		int blockBytes = Math.max(format.getFrameSize(),
			(int)(format.getFrameRate() * BLOCK_SECONDS) * format.getFrameSize());
		int samples = blockBytes / 2; /* 16-bit samples, mono or interleaved stereo */
		int[] accumulator = new int[samples];
		byte[] block = new byte[blockBytes];

		while(true)
		{
			synchronized(lock)
			{
				if(!hasActiveSounds())
				{
					mixing = false;
					return;
				}

				Arrays.fill(accumulator, 0);

				for(int i = sounds.size() - 1; i >= 0; i--)
				{
					Sound sound = sounds.get(i);
					if(sound.paused) { continue; }

					sound.mix(accumulator, samples);
					if(sound.finished)
					{
						sound.queued = false;
						sounds.remove(i);
					}
				}
			}

			for(int i = 0; i < samples; i++)
			{
				int sample = accumulator[i];
				if(sample > 32767) { sample = 32767; }
				else if(sample < -32768) { sample = -32768; }

				block[2 * i] = (byte)(sample & 0xFF);
				block[2 * i + 1] = (byte)((sample >> 8) & 0xFF);
			}

			line.write(block, 0, block.length);
		}
	}

	private boolean hasActiveSounds()
	{
		for(int i = 0; i < sounds.size(); i++)
		{
			if(!sounds.get(i).paused) { return true; }
		}
		return false;
	}

	/*

		Sound

		A single sound effect playing on a mixer. Positions are kept in bytes
		of PCM_SIGNED 16-bit little-endian data.

	*/

	public static final class Sound
	{
		private final WavMixer mixer;
		private final byte[] pcm;
		private final int frameSize;
		private final float frameRate;

		private int position;
		private int loopCount = 0;
		private int loopsPlayed;
		private boolean queued;
		private boolean paused = true;
		private boolean finished;

		private Sound(WavMixer mixer, AudioFormat format, byte[] pcm)
		{
			this.mixer = mixer;
			this.pcm = pcm;
			this.frameSize = format.getFrameSize();
			this.frameRate = format.getFrameRate();
		}

		public void start()
		{
			synchronized(mixer.lock)
			{
				if(finished)
				{
					position = 0;
					loopsPlayed = 0;
					finished = false;
				}
				paused = false;
			}
			mixer.queue(this);
		}

		public void stop()
		{
			synchronized(mixer.lock)
			{
				paused = true;
			}
		}

		/* Number of loop backs, as the Java SE audio APIs count them: 0 plays once, -1 loops forever.
		   PlatformPlayer converts the MIDP value before it gets here. */
		public void setLoopCount(int count)
		{
			synchronized(mixer.lock)
			{
				loopCount = count;
			}
		}

		public void setMediaTime(long microseconds)
		{
			synchronized(mixer.lock)
			{
				long frames = (long)((microseconds / 1000000.0) * frameRate);
				long bytes = frames * frameSize;

				if(bytes < 0) { bytes = 0; }
				if(bytes > pcm.length) { bytes = pcm.length - (pcm.length % frameSize); }

				position = (int) bytes;
				loopsPlayed = 0;
				finished = false;
			}
		}

		public long getMediaTime()
		{
			synchronized(mixer.lock)
			{
				return (long)((position / frameSize) * 1000000.0 / frameRate);
			}
		}

		public boolean isPlaying()
		{
			synchronized(mixer.lock)
			{
				return queued && !paused && !finished;
			}
		}

		/* Called with the mixer lock held. */
		private void mix(int[] accumulator, int sampleCount)
		{
			for(int i = 0; i < sampleCount; i++)
			{
				if(position + 1 >= pcm.length)
				{
					if(loopCount == -1 || loopsPlayed < loopCount)
					{
						if(loopCount != -1) { loopsPlayed++; }
						position = 0;
					}
					else
					{
						finished = true;
						return;
					}
				}

				accumulator[i] += (pcm[position + 1] << 8) | (pcm[position] & 0xFF);
				position += 2;
			}
		}
	}
}
