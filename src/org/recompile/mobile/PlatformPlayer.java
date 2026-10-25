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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Vector;
import java.util.prefs.Preferences;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

import javax.microedition.media.Control;

public class PlatformPlayer implements Player
{

	private String contentType = "";

	private audioplayer player;

	private byte[] mediaData;

	private int state = Player.UNREALIZED;

	private Vector<PlayerListener> listeners;

	private Control[] controls;
	private static final Vector<PlatformPlayer> activePlayers = new Vector<PlatformPlayer>();

	/*
	 * Keeps the amount of open MIDI devices bounded. ID Software's games (DOOM II RPG, Orcs and Elves II)
	 * allocate a new sequencer and synthesizer for every sample they load and never close any of them.
	 */
	private static final ArrayList<PlatformPlayer> openMidiPlayers = new ArrayList<PlatformPlayer>();

	private static final Object midiLock = new Object();

	public PlatformPlayer(InputStream stream, String type)
	{
		listeners = new Vector<PlayerListener>();
		controls = new Control[3];

		contentType = type;

		if(Mobile.sound == false)
		{
			player = new audioplayer();
		}
		else
		{
			/*
			 * The media data is read right away because the MIDlet is allowed to close the
			 * stream as soon as createPlayer() returns. The audio device itself is only
			 * acquired on prefetch()/start(), which keeps start() immediate for the games
			 * that follow the MIDP lifecycle.
			 */
			try { mediaData = readInputStream(stream); }
			catch (IOException e) { System.out.println("Couldn't read input stream: " + e.getMessage()); }
		}

		controls[0] = new volumeControl();
		controls[1] = new tempoControl();
		controls[2] = new midiControl();
		registerPlayer();

		//System.out.println("media type: "+type);
	}

	/* Creates the underlying player. Called from realize()/prefetch()/start() so the device is opened there. */
	private void prepare()
	{
		if(player != null) { return; }

		if(!Mobile.sound || mediaData == null)
		{
			player = new audioplayer();
			return;
		}

		boolean anyType = contentType.equals("");
		boolean midiType = contentType.equalsIgnoreCase("audio/mid") || contentType.equalsIgnoreCase("audio/midi")
			|| contentType.equalsIgnoreCase("sp-midi") || contentType.equalsIgnoreCase("audio/spmidi");
		boolean wavType = contentType.equalsIgnoreCase("audio/x-wav") || contentType.equalsIgnoreCase("audio/wav");

		if(midiType || anyType)
		{
			midiPlayer midi = new midiPlayer(mediaData);
			if(midi.isLoaded())
			{
				/* Only takes a slot once the device is known to be in use, so a wav guess doesn't evict anyone */
				reserveMidiSlot(this);
				player = midi;
				return;
			}

			if(!anyType)
			{
				System.out.println("No Player For: "+contentType);
				player = new audioplayer();
				return;
			}

			System.out.println("Received no explicit audio type. Trying to load as MIDI, and if it fails, WAV.");
		}

		if(wavType || anyType)
		{
			wavPlayer wav = new wavPlayer(mediaData);
			if(wav.isLoaded()) { player = wav; return; }
		}

		System.out.println("No Player For: "+contentType); /* TODO: Implement a player for amr and mpeg audio types */
		player = new audioplayer();
	}

	/* Reads the whole stream, the MIDlet is allowed to close it right after createPlayer() returns. */
	private static byte[] readInputStream(InputStream input) throws IOException
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1024, input.available()));
		byte[] buffer = new byte[4096];
		int read;
		while((read = input.read(buffer)) > 0)
		{
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	public PlatformPlayer(String locator)
	{
		player = new audioplayer();
		listeners = new Vector<PlayerListener>();
		controls = new Control[3];
		System.out.println("Player locator: "+locator);
		registerPlayer();
	}

	private void registerPlayer()
	{
		synchronized(activePlayers)
		{
			activePlayers.add(this);
		}
	}

	private void unregisterPlayer()
	{
		synchronized(activePlayers)
		{
			activePlayers.remove(this);
		}
	}

	public static void closeAll()
	{
		PlatformPlayer[] players;
		synchronized(activePlayers)
		{
			players = activePlayers.toArray(new PlatformPlayer[activePlayers.size()]);
		}

		for(PlatformPlayer platformPlayer : players)
		{
			platformPlayer.deallocate();
		}
	}

	/*
	 * Opens room for one more MIDI device. When the limit is reached, the oldest player that isn't playing
	 * has its device recycled (or the very oldest one, if every single one of them is running).
	 */
	private static void reserveMidiSlot(PlatformPlayer platformPlayer)
	{
		PlatformPlayer recyclable = null;

		synchronized(midiLock)
		{
			for(int i = openMidiPlayers.size() - 1; i >= 0; i--)
			{
				if(openMidiPlayers.get(i).state == Player.CLOSED) { openMidiPlayers.remove(i); }
			}

			if(openMidiPlayers.size() >= Math.max(1, Mobile.maxMidiPlayers))
			{
				int victim = 0;
				for(int i = 0; i < openMidiPlayers.size(); i++)
				{
					if(!openMidiPlayers.get(i).isDeviceRunning()) { victim = i; break; }
				}
				recyclable = openMidiPlayers.remove(victim);
			}

			if(!openMidiPlayers.contains(platformPlayer)) { openMidiPlayers.add(platformPlayer); }
		}

		if(recyclable != null) { recyclable.recycleMidiDevice(); }
	}

	private static void releaseMidiSlot(PlatformPlayer platformPlayer)
	{
		synchronized(midiLock)
		{
			openMidiPlayers.remove(platformPlayer);
		}
	}

	/* Applies a new limit right away, when the setting is changed while a game is running. */
	public static void trimMidiPlayers()
	{
		ArrayList<PlatformPlayer> excess = new ArrayList<PlatformPlayer>();

		synchronized(midiLock)
		{
			while(openMidiPlayers.size() > Math.max(1, Mobile.maxMidiPlayers))
			{
				int victim = 0;
				for(int i = 0; i < openMidiPlayers.size(); i++)
				{
					if(!openMidiPlayers.get(i).isDeviceRunning()) { victim = i; break; }
				}
				excess.add(openMidiPlayers.remove(victim));
			}
		}

		for(int i = 0; i < excess.size(); i++)
		{
			excess.get(i).recycleMidiDevice();
		}
	}

	/* Frees the underlying device but leaves the player usable: the next start() prepares it again. */
	private void recycleMidiDevice()
	{
		audioplayer current;

		synchronized(this)
		{
			current = player;
			player = null;
		}

		if(current != null)
		{
			try { current.deallocate(); } catch (Exception e) { }
		}
		state = Player.UNREALIZED;
	}

	private boolean isDeviceRunning()
	{
		audioplayer current = player;
		return current != null && current.isRunning();
	}

	public void close()
	{
		try
		{
			if(player != null)
			{
				player.deallocate();
			}
			state = Player.CLOSED;
			notifyListeners(PlayerListener.CLOSED, null);	
		}
		catch (Exception e) { }
		state = Player.CLOSED;
		unregisterPlayer();
		releaseMidiSlot(this);
	}

	public int getState()
	{
		if(player == null) { return state; }

		if(player.isRunning()==false)
		{
			state = Player.PREFETCHED;
		}
		return state;
	}

	public void start()
	{
		//System.out.println("Play "+contentType);
		try
		{
			prepare();
			player.start();
		}
		catch (Exception e) {  }
	}

	public void stop()
	{
		//System.out.println("Stop "+contentType);
		try
		{
			player.stop();
		}
		catch (Exception e) { }
	}

	public void addPlayerListener(PlayerListener playerListener)
	{
		//System.out.println("Add Player Listener");
		listeners.add(playerListener);
	}

	public void removePlayerListener(PlayerListener playerListener)
	{
		//System.out.println("Remove Player Listener");
		listeners.remove(playerListener);
	}

	private void notifyListeners(String event, Object eventData)
	{
		for(int i=0; i<listeners.size(); i++)
		{
			listeners.get(i).playerUpdate(this, event, eventData);
		}
	}

	public void deallocate()
	{
		try
		{
			if(player != null)
			{
				player.deallocate();
			}
		}
		finally
		{
			state = Player.CLOSED;
			unregisterPlayer();
			releaseMidiSlot(this);
		}
	}

	public String getContentType() { return contentType; }

	public long getDuration() { return Player.TIME_UNKNOWN; }

	public long getMediaTime() { return player == null ? 0 : player.getMediaTime(); }

	/* realize() and prefetch() are where the audio device gets acquired, so that start() doesn't have to. */
	public void prefetch()
	{
		try { prepare(); }
		catch (Exception e) { }
		state = Player.PREFETCHED;
	}

	public void realize()
	{
		try { prepare(); }
		catch (Exception e) { }

		if(state == Player.UNREALIZED) { state = Player.REALIZED; }
	}

	public void setLoopCount(int count)
	{
		try
		{
			prepare();
			player.setLoopCount(loopCount(count));
		}
		catch (Exception e) { }
	}

	/*
	 * Games (mostly Gameloft's) ask for one loop more than they want: they set 1 expecting a single
	 * playback. The Java SE audio APIs already count repetitions, so the request is adjusted once here
	 * instead of in each player. LOOP_CONTINUOUSLY is kept as is.
	 */
	private static int loopCount(int count)
	{
		if(count < 0) { return -1; }
		return count == 0 ? 0 : count - 1;
	}

	public long setMediaTime(long now)
	{
		try
		{
			prepare();
			return player.setMediaTime(now);
		}
		catch (Exception e) { return now; }
	}

	// Controllable interface //

	public Control getControl(String controlType)
	{
		if(controlType.equals("VolumeControl")) { return controls[0]; }
		if(controlType.equals("TempoControl")) { return controls[1]; }
		if(controlType.equals("MIDIControl")) { return controls[2]; }
		if(controlType.equals("javax.microedition.media.control.VolumeControl")) { return controls[0]; }
		if(controlType.equals("javax.microedition.media.control.TempoControl")) { return controls[1]; }
		if(controlType.equals("javax.microedition.media.control.MIDIControl")) { return controls[2]; }
		return null;
	}

	public Control[] getControls()
	{
		return controls;
	}

	/* Read 'n' Bytes from the InputStream. Used by IMA ADPCM decoder as well. */
	public static void readInputStreamData(InputStream input, byte[] output, int offset, int nBytes) throws IOException 
	{
		int end = offset + nBytes;
		while(offset < end) 
		{
			int read = input.read(output, offset, end - offset);
			if(read < 0) throw new java.io.EOFException();
			offset += read;
		}
	}

	/*
	 * Gervill's soft synthesizer defaults to 200ms of latency, plus a jitter corrector on
	 * top of it, which makes every midi note come out well after the game's action. The
	 * public midi API has no way to set that and com.sun.media.sound isn't exported, so the
	 * values are written to the properties Gervill reads on open() and restored right after.
	 */
	private static final Object synthLock = new Object();

	private static final int MIDI_LATENCY_MS = Integer.getInteger("freej2me.midi.latency", 40);

	private static Synthesizer openLowLatencySynth() throws Exception
	{
		Preferences prefs = null;
		String oldLatency = null;
		String oldJitter = null;

		synchronized(synthLock)
		{
			try
			{
				prefs = Preferences.userRoot().node("/com/sun/media/sound/softsynthesizer");
				oldLatency = prefs.get("latency", null);
				oldJitter = prefs.get("jitter correction", null);

				prefs.put("latency", Long.toString(MIDI_LATENCY_MS * 1000L));
				prefs.put("jitter correction", "false");
			}
			catch (Exception e) { prefs = null; }

			try
			{
				Synthesizer synth = MidiSystem.getSynthesizer();
				if(!synth.isOpen()) { synth.open(); }
				return synth;
			}
			finally
			{
				restorePreference(prefs, "latency", oldLatency);
				restorePreference(prefs, "jitter correction", oldJitter);
			}
		}
	}

	private static void restorePreference(Preferences prefs, String key, String value)
	{
		if(prefs == null) { return; }

		try
		{
			if(value == null) { prefs.remove(key); }
			else { prefs.put(key, value); }
		}
		catch (Exception e) { }
	}

	// Players //

	private class audioplayer
	{
		protected boolean loaded = false;

		public boolean isLoaded() { return loaded; }
		public void start() {  }
		public void stop() {  }
		public void setLoopCount(int count) {  }
		public long setMediaTime(long now) { return now; }
		public long getMediaTime() { return 0; }
		public boolean isRunning() { return false; }
		public void deallocate() {  }
	}

	private class midiPlayer extends audioplayer
	{
		private Sequencer midi;

		private Synthesizer synth;

		private int loops = 0;

		private long tick = 0L;

		public midiPlayer(byte[] mediaData)
		{
			try
			{
				openDevices();
				midi.setSequence(new ByteArrayInputStream(mediaData));
				loaded = true;
			}
			catch (Exception e) 
			{ 
				System.out.println("Couldn't load MIDI file: " + e.getMessage());
				deallocate();
			}
		}

		private void openDevices() throws Exception
		{
			try
			{
				synth = openLowLatencySynth();
				midi = MidiSystem.getSequencer(false); /* We connect it to our own synth below */
				midi.getTransmitter().setReceiver(synth.getReceiver());
				midi.open();
			}
			catch (Exception e)
			{
				/* No low latency route available, fall back to the default, auto connected sequencer */
				deallocate();
				midi = MidiSystem.getSequencer();
				midi.open();
			}
		}

		public void start()
		{
			if(isRunning()) { return; }

			if(midi.getTickPosition() >= midi.getTickLength())
			{
				midi.setTickPosition(0);
			}
			tick = midi.getTickPosition();
			midi.start();
			state = Player.STARTED;
			notifyListeners(PlayerListener.STARTED, tick);
		}

		public void stop()
		{
			if(!isRunning()) { return; }
			midi.stop();
			state = Player.PREFETCHED;
			tick = midi.getTickPosition();
			notifyListeners(PlayerListener.STOPPED, tick);
		}
		public void deallocate()
		{
			if(midi != null)
			{
				try { midi.stop(); } catch (Exception e) { }
				try { midi.close(); } catch (Exception e) { }
				midi = null;
			}
			if(synth != null)
			{
				try { synth.close(); } catch (Exception e) { }
				synth = null;
			}
		}

		public void setLoopCount(int count)
		{
			loops = count;
			if(midi != null) { midi.setLoopCount(count); }
		}
		public long setMediaTime(long now)
		{
			try
			{
				midi.setTickPosition(now);
			}
			catch (Exception e) { }
			return now;
		}
		public long getMediaTime()
		{
			return midi == null ? 0 : midi.getTickPosition();
		}
		public boolean isRunning()
		{
			return midi != null && midi.isRunning();
		}
	}

	private class wavPlayer extends audioplayer
	{
		/* Shared low latency mixing path */
		private WavMixer mixer;
		private WavMixer.Sound sound;
		private byte[] pcmData;
		/* Fallback path, used when the shared line can't be opened */
		private AudioInputStream wavStream;
		private Clip wavClip;
		/* IMA ADPCM WAV variables */
		private WavImaAdpcmDecoder adpcmDec = new WavImaAdpcmDecoder();
		
		/* Player control variables */
		private int loops = 0;

		private long time = 0L;

		public wavPlayer(byte[] mediaData)
		{
			try
			{
				if(!openMixer(mediaData))
				{
					wavStream = openStream(mediaData);
					wavClip = AudioSystem.getClip();
					wavClip.open(wavStream);
				}
				loaded = true;
			}
			catch (Exception e) 
			{ 
				System.out.println("Couldn't load wav file: " + e.getMessage());
				deallocate();
			}
		}

		/*
		 * A wav header is generally 44-bytes long, and it is what we need to read in order to get
		 * the stream's format, frame size, bit rate, number of channels, etc. which gives us information
		 * on the kind of codec needed to play or decode the incoming stream. The stream needs to be reset
		 * or else PCM files will be loaded without a header and it might cause issues with playback.
		 */
		private AudioInputStream openStream(byte[] mediaData) throws Exception
		{
			InputStream stream = new ByteArrayInputStream(mediaData);
			stream.mark(48);
			int[] wavHeaderData = adpcmDec.readHeader(stream);
			stream.reset();

			/* We only check for IMA ADPCM at the moment. */
			if(wavHeaderData[0] != 17) /* If it's not IMA ADPCM we don't need to do anything to the stream. */
			{
				return AudioSystem.getAudioInputStream(stream);
			}

			/* But if it is IMA ADPCM, we have to decode it manually. */
			return AudioSystem.getAudioInputStream(adpcmDec.decodeImaAdpcm(stream, wavHeaderData));
		}

		/* Converts the wav into the mixer's pcm format. Returns false if the shared line isn't usable. */
		private boolean openMixer(byte[] mediaData)
		{
			try
			{
				AudioInputStream stream = openStream(mediaData);
				AudioFormat source = stream.getFormat();
				AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, source.getSampleRate(), 16,
					source.getChannels(), source.getChannels() * 2, source.getSampleRate(), false);

				WavMixer candidate = WavMixer.get(target);
				if(!candidate.isAvailable()) { return false; }

				pcmData = readPcm(AudioSystem.getAudioInputStream(target, stream));
				mixer = candidate;
				return true;
			}
			catch (Exception e)
			{
				System.out.println("Couldn't prepare wav for the mixer: " + e.getMessage());
				return false;
			}
		}

		private byte[] readPcm(AudioInputStream stream) throws IOException
		{
			try
			{
				ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(4096, stream.available()));
				byte[] buffer = new byte[4096];
				int read;
				while((read = stream.read(buffer)) > 0)
				{
					output.write(buffer, 0, read);
				}
				return output.toByteArray();
			}
			finally
			{
				stream.close();
			}
		}

		public void start()
		{
			if(isRunning()) { return; }

			if(mixer != null)
			{
				if(sound == null) { sound = mixer.create(pcmData); }
				sound.setLoopCount(loops);
				sound.start();
				time = sound.getMediaTime();
			}
			else
			{
				if(wavClip.getFramePosition() >= wavClip.getFrameLength())
				{
					wavClip.setFramePosition(0);
				}
				time = wavClip.getMicrosecondPosition();
				wavClip.start();
			}
			state = Player.STARTED;
			notifyListeners(PlayerListener.STARTED, time);
		}

		public void stop()
		{
			if(!isRunning()) { return; }

			if(mixer != null)
			{
				sound.stop();
				time = sound.getMediaTime();
			}
			else
			{
				wavClip.stop();
				time = wavClip.getMicrosecondPosition();
			}
			state = Player.PREFETCHED;
			notifyListeners(PlayerListener.STOPPED, time);
		}

		public void setLoopCount(int count)
		{
			loops = count;
			if(mixer != null)
			{
				if(sound == null) { sound = mixer.create(pcmData); }
				sound.setLoopCount(count);
			}
			else if(wavClip != null)
			{
				wavClip.loop(count);
			}
		}

		public long setMediaTime(long now)
		{
			if(mixer != null)
			{
				if(sound == null) { sound = mixer.create(pcmData); }
				sound.setMediaTime(now);
			}
			else if(wavClip != null)
			{
				wavClip.setMicrosecondPosition(now);
			}
			return now;
		}
		public long getMediaTime()
		{
			if(mixer != null) { return sound == null ? 0 : sound.getMediaTime(); }
			return wavClip == null ? 0 : wavClip.getMicrosecondPosition();
		}

		public boolean isRunning()
		{
			if(mixer != null) { return sound != null && sound.isPlaying(); }
			return wavClip != null && wavClip.isRunning();
		}

		public void deallocate()
		{
			if(sound != null && mixer != null)
			{
				mixer.remove(sound);
				sound = null;
			}
			if(wavClip != null)
			{
				wavClip.stop();
				wavClip.close();
				wavClip = null;
			}
			try
			{
				if(wavStream != null)
				{
					wavStream.close();
				}
			}
			catch (IOException e) { }
			wavStream = null;
		}
	}

	// Controls //

	private class midiControl implements javax.microedition.media.control.MIDIControl
	{
		public int[] getBankList(boolean custom) { return new int[]{}; }

		public int getChannelVolume(int channel) { return 0; }

		public java.lang.String getKeyName(int bank, int prog, int key) { return ""; }

		public int[] getProgram(int channel) { return new int[]{}; }

		public int[] getProgramList(int bank) { return new int[]{}; }

		public java.lang.String getProgramName(int bank, int prog) { return ""; }

		public boolean isBankQuerySupported() { return false; }

		public int longMidiEvent(byte[] data, int offset, int length) { return 0; }

		public void setChannelVolume(int channel, int volume) {  }

		public void setProgram(int channel, int bank, int program) {  }

		public void shortMidiEvent(int type, int data1, int data2) {  }
	}

	private class volumeControl implements javax.microedition.media.control.VolumeControl
	{
		private int level = 100;
		private boolean muted = false;

		public int getLevel() { return level; }

		public boolean isMuted() { return muted; }

		public int setLevel(int value) { level = value; return level; }

		public void setMute(boolean mute) { muted = mute; }
	}

	private class tempoControl implements javax.microedition.media.control.TempoControl
	{
		int tempo = 5000;
		int rate = 5000;

		public int getTempo() { return tempo; }

		public int setTempo(int millitempo) { tempo = millitempo; return tempo; }

		// RateControl interface
		public int getMaxRate() { return rate; }

		public int getMinRate() { return rate; }

		public int getRate() { return rate; }

		public int setRate(int millirate) { rate=millirate; return rate; }
	}
}
