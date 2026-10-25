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
package org.recompile.freej2me;

/*
	FreeJ2ME - AWT
*/

import org.recompile.mobile.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import javax.imageio.ImageIO;

public class FreeJ2ME
{
	public static void main(String args[])
	{
		FreeJ2ME app = new FreeJ2ME(args);
	}

	private Frame main;
	private int lcdWidth;
	private int lcdHeight;
	private int scaleFactor = 1;

	private LCD lcd;

	private int xborder;
	private int yborder;

	private PlatformImage img;

	private Config config;
	private boolean useNokiaControls = false;
	private boolean useSiemensControls = false;
	private boolean useMotorolaControls = false;
	private boolean invertDimesions = false;
	private int rotation = 0;
	private int limitFPS = 0;
	
	private boolean[] pressedKeys = new boolean[128];

	public FreeJ2ME(String args[])
	{
		main = new Frame("FreeJ2ME");
		main.setSize(350,450);
		main.setBackground(new Color(0,0,0));
		try
		{
			main.setIconImage(ImageIO.read(main.getClass().getResourceAsStream("/org/recompile/icon.png")));	
		}
		catch (Exception e) { }

		main.addWindowListener(new WindowAdapter()
		{
			public void windowClosing(WindowEvent e)
			{
				System.exit(0);
			}
		});

		// Setup Device //

		lcdWidth = 240;
		lcdHeight = 320;

		String jarfile = "";
		if(args.length>=1)
		{
			jarfile = args[0];
		}
		if(args.length>=3)
		{
			lcdWidth = Integer.parseInt(args[1]);
			lcdHeight = Integer.parseInt(args[2]);
		}
		if(args.length>=4)
		{
			scaleFactor = Integer.parseInt(args[3]);
		}

		Mobile.setPlatform(new MobilePlatform(lcdWidth, lcdHeight));

		lcd = new LCD();
		lcd.setFocusable(true);
		main.add(lcd);

		config = new Config();
		config.onChange = new Runnable() { public void run() { settingsChanged(); } };

		Mobile.getPlatform().setPainter(new Runnable()
		{
			public void run()
			{
				lcd.paint(lcd.getGraphics());
			}
		});

		lcd.addKeyListener(new KeyListener()
		{
			public void keyPressed(KeyEvent e)
			{
				int keycode = e.getKeyCode();
				int mobikey = getMobileKey(keycode);
				int mobikeyN = (mobikey + 64) & 0x7F; //Normalized value for indexing the pressedKeys array
				
				switch(keycode) // Handle emulator control keys
				{
					case KeyEvent.VK_PLUS:
					case KeyEvent.VK_ADD:
						scaleFactor++;
						main.setSize(lcdWidth * scaleFactor + xborder, lcdHeight * scaleFactor + yborder);
					break;
					case KeyEvent.VK_MINUS:
					case KeyEvent.VK_SUBTRACT:
						if( scaleFactor > 1 )
						{
							scaleFactor--;
							main.setSize(lcdWidth * scaleFactor + xborder, lcdHeight * scaleFactor + yborder);
						}
					break;
					case KeyEvent.VK_C:
						if(e.isControlDown())
						{
							ScreenShot.takeScreenshot(false);
						}
					break;
				}
				
				if (mobikey == 0) //Ignore events from keys not mapped to a phone keypad key
				{
					return; 
				}
				
				if(config.isRunning)
				{
					config.keyPressed(mobikey);
				}
				else
				{
					if (pressedKeys[mobikeyN] == false)
					{
						//~ System.out.println("keyPressed:  " + Integer.toString(mobikey));
						Mobile.getPlatform().keyPressed(mobikey);
					}
					else
					{
						//~ System.out.println("keyRepeated:  " + Integer.toString(mobikey));
						Mobile.getPlatform().keyRepeated(mobikey);
					}
				}
				pressedKeys[mobikeyN] = true;
				
			}

			public void keyReleased(KeyEvent e)
			{
				int mobikey = getMobileKey(e.getKeyCode());
				int mobikeyN = (mobikey + 64) & 0x7F; //Normalized value for indexing the pressedKeys array
				
				if (mobikey == 0) //Ignore events from keys not mapped to a phone keypad key
				{
					return; 
				}
				
				pressedKeys[mobikeyN] = false;
				
				if(config.isRunning)
				{
					config.keyReleased(mobikey);
				}
				else
				{
					//~ System.out.println("keyReleased: " + Integer.toString(mobikey));
					Mobile.getPlatform().keyReleased(mobikey);
				}
			}

			public void keyTyped(KeyEvent e) { }

		});

		lcd.addMouseListener(new MouseListener()
		{
			public void mousePressed(MouseEvent e)
			{
				Point point = lcd.mapPointer(e.getX(), e.getY());
				Mobile.getPlatform().pointerPressed(point.x, point.y);
			}

			public void mouseReleased(MouseEvent e)
			{
				Point point = lcd.mapPointer(e.getX(), e.getY());
				Mobile.getPlatform().pointerReleased(point.x, point.y);
			}

			public void mouseExited(MouseEvent e) { }
			public void mouseEntered(MouseEvent e) { }
			public void mouseClicked(MouseEvent e) { }

		});

		lcd.addMouseMotionListener(new MouseMotionAdapter() 
		{
			public void mouseDragged(MouseEvent e)
			{
				Point point = lcd.mapPointer(e.getX(), e.getY());
				Mobile.getPlatform().pointerDragged(point.x, point.y);
			}
		});

		main.addComponentListener(new ComponentAdapter()
		{
			public void componentResized(ComponentEvent e)
			{
				resize();
			}
		});

		main.setVisible(true);
		main.pack();

		resize();
		main.setSize(lcdWidth*scaleFactor+xborder, lcdHeight*scaleFactor+yborder);

		if(args.length<1)
		{
			FileDialog t = new FileDialog(main, "Open JAR File", FileDialog.LOAD);
			t.setFilenameFilter(new FilenameFilter()
			{
				public boolean accept(File dir, String name)
				{
					return name.toLowerCase().endsWith(".jar");
				}
			});
			t.setVisible(true);
			jarfile = new File(t.getDirectory()+File.separator+t.getFile()).toURI().toString();
		}
		if(Mobile.getPlatform().loadJar(jarfile))
		{
			config.init();

			/* Allows FreeJ2ME to set the width and height passed as cmd arguments. */
			if(args.length>=3)
			{
				lcdWidth = Integer.parseInt(args[1]);
				lcdHeight = Integer.parseInt(args[2]);
				config.settings.put("width",  ""+lcdWidth);
				config.settings.put("height", ""+lcdHeight);
			}

			settingsChanged();

			Mobile.getPlatform().runJar();
		}
		else
		{
			System.out.println("Couldn't load jar...");
		}
	}

	private void settingsChanged()
	{
		int w = Integer.parseInt(config.settings.get("width"));
		int h = Integer.parseInt(config.settings.get("height"));

		limitFPS = Integer.parseInt(config.settings.get("fps"));
		if(limitFPS>0) { limitFPS = 1000 / limitFPS; }

		String sound = config.settings.get("sound");
		Mobile.sound = false;
		if(sound.equals("on")) { Mobile.sound = true; }

		String phone = config.settings.get("phone");
		useNokiaControls = false;
		useSiemensControls = false;
		useMotorolaControls = false;
		Mobile.nokia = false;
		Mobile.siemens = false;
		Mobile.motorola = false;
		if(phone.equals("Nokia")) { Mobile.nokia = true; useNokiaControls = true; }
		if(phone.equals("Siemens")) { Mobile.siemens = true; useSiemensControls = true; }
		if(phone.equals("Motorola")) { Mobile.motorola = true; useMotorolaControls = true; }

		invertDimesions = "on".equals(config.settings.get("invertDimesions"));
		rotation = Integer.parseInt(config.settings.get("rotation"));
		int gameWidth = invertDimesions ? h : w;
		int gameHeight = invertDimesions ? w : h;
		lcdWidth = rotation % 180 == 0 ? gameWidth : gameHeight;
		lcdHeight = rotation % 180 == 0 ? gameHeight : gameWidth;
		Mobile.getPlatform().resizeLCD(gameWidth, gameHeight);

		resize();
		main.setSize(lcdWidth*scaleFactor+xborder , lcdHeight*scaleFactor+yborder);
	}

	private int getMobileKey(int keycode)
	{
		if(useNokiaControls)
		{
			switch(keycode)
			{
				case KeyEvent.VK_UP: return Mobile.NOKIA_UP;
				case KeyEvent.VK_DOWN: return Mobile.NOKIA_DOWN;
				case KeyEvent.VK_LEFT: return Mobile.NOKIA_LEFT;
				case KeyEvent.VK_RIGHT: return Mobile.NOKIA_RIGHT;
				case KeyEvent.VK_ENTER: return Mobile.NOKIA_SOFT3;
			}
		}

		if(useSiemensControls)
		{
			switch(keycode)
			{
				case KeyEvent.VK_UP: return Mobile.SIEMENS_UP;
				case KeyEvent.VK_DOWN: return Mobile.SIEMENS_DOWN;
				case KeyEvent.VK_LEFT: return Mobile.SIEMENS_LEFT;
				case KeyEvent.VK_RIGHT: return Mobile.SIEMENS_RIGHT;
				case KeyEvent.VK_Q: return Mobile.SIEMENS_SOFT1;
				case KeyEvent.VK_W: return Mobile.SIEMENS_SOFT2;
				case KeyEvent.VK_ENTER: return Mobile.SIEMENS_FIRE;
			}
		}

		if(useMotorolaControls)
		{
			switch(keycode)
			{
				case KeyEvent.VK_UP: return Mobile.MOTOROLA_UP;
				case KeyEvent.VK_DOWN: return Mobile.MOTOROLA_DOWN;
				case KeyEvent.VK_LEFT: return Mobile.MOTOROLA_LEFT;
				case KeyEvent.VK_RIGHT: return Mobile.MOTOROLA_RIGHT;
				case KeyEvent.VK_Q: return Mobile.MOTOROLA_SOFT1;
				case KeyEvent.VK_W: return Mobile.MOTOROLA_SOFT2;
				case KeyEvent.VK_ENTER: return Mobile.MOTOROLA_FIRE;
			}
		}

		switch(keycode)
		{
			case KeyEvent.VK_0: return Mobile.KEY_NUM0;
			case KeyEvent.VK_1: return Mobile.KEY_NUM1;
			case KeyEvent.VK_2: return Mobile.KEY_NUM2;
			case KeyEvent.VK_3: return Mobile.KEY_NUM3;
			case KeyEvent.VK_4: return Mobile.KEY_NUM4;
			case KeyEvent.VK_5: return Mobile.KEY_NUM5;
			case KeyEvent.VK_6: return Mobile.KEY_NUM6;
			case KeyEvent.VK_7: return Mobile.KEY_NUM7;
			case KeyEvent.VK_8: return Mobile.KEY_NUM8;
			case KeyEvent.VK_9: return Mobile.KEY_NUM9;
			case KeyEvent.VK_ASTERISK: return Mobile.KEY_STAR;
			case KeyEvent.VK_NUMBER_SIGN: return Mobile.KEY_POUND;

			case KeyEvent.VK_NUMPAD0: return Mobile.KEY_NUM0;
			case KeyEvent.VK_NUMPAD7: return Mobile.KEY_NUM1;
			case KeyEvent.VK_NUMPAD8: return Mobile.KEY_NUM2;
			case KeyEvent.VK_NUMPAD9: return Mobile.KEY_NUM3;
			case KeyEvent.VK_NUMPAD4: return Mobile.KEY_NUM4;
			case KeyEvent.VK_NUMPAD5: return Mobile.KEY_NUM5;
			case KeyEvent.VK_NUMPAD6: return Mobile.KEY_NUM6;
			case KeyEvent.VK_NUMPAD1: return Mobile.KEY_NUM7;
			case KeyEvent.VK_NUMPAD2: return Mobile.KEY_NUM8;
			case KeyEvent.VK_NUMPAD3: return Mobile.KEY_NUM9;

			case KeyEvent.VK_UP: return Mobile.KEY_NUM2;
			case KeyEvent.VK_DOWN: return Mobile.KEY_NUM8;
			case KeyEvent.VK_LEFT: return Mobile.KEY_NUM4;
			case KeyEvent.VK_RIGHT: return Mobile.KEY_NUM6;

			case KeyEvent.VK_ENTER: return Mobile.KEY_NUM5;

			case KeyEvent.VK_Q: return Mobile.NOKIA_SOFT1;
			case KeyEvent.VK_W: return Mobile.NOKIA_SOFT2;
			case KeyEvent.VK_E: return Mobile.KEY_STAR;
			case KeyEvent.VK_R: return Mobile.KEY_POUND;

			case KeyEvent.VK_A: return -1;
			case KeyEvent.VK_Z: return -2;

			// Config //
			case KeyEvent.VK_ESCAPE: config.start();
		}
		return 0;
	}

	private void resize()
	{
		xborder = main.getInsets().left+main.getInsets().right;
		yborder = main.getInsets().top+main.getInsets().bottom;

		double vw = (main.getWidth()-xborder)*1;
		double vh = (main.getHeight()-yborder)*1;

		BufferedImage displayImage = config.isRunning ? config.getLCD() : Mobile.getPlatform().getLCD();
		int displayRotation = config.isRunning ? 0 : rotation;
		int imageWidth = displayImage.getWidth();
		int imageHeight = displayImage.getHeight();
		int displayWidth = displayRotation % 180 == 0 ? imageWidth : imageHeight;
		int displayHeight = displayRotation % 180 == 0 ? imageHeight : imageWidth;
		double nw = displayWidth;
		double nh = displayHeight;

		nw = vw;
		nh = nw*((double)displayHeight/(double)displayWidth);

		if(nh>vh)
		{
			nh = vh;
			nw = nh*((double)displayWidth/(double)displayHeight);
		}

		lcd.updateScale((int)nw, (int)nh);
	}

	public boolean isInvertResolution()
	{
		return invertDimesions;
	}

	public void setInvertResolution(boolean invertDimesions)
	{
		this.invertDimesions = invertDimesions;
	}

	private class LCD extends Canvas
	{
		public int cx=0;
		public int cy=0;
		public int cw=240;
		public int ch=320;
		private boolean configScaleActive;

		public double scalex=1;
		public double scaley=1;

		public void updateScale(int vw, int vh)
		{
			cx = (this.getWidth()-vw)/2;
			cy = (this.getHeight()-vh)/2;
			cw = vw;
			ch = vh;
			BufferedImage image = config.isRunning ? config.getLCD() : Mobile.getPlatform().getLCD();
			int displayRotation = config.isRunning ? 0 : rotation;
			int imageWidth = displayRotation % 180 == 0 ? image.getWidth() : image.getHeight();
			int imageHeight = displayRotation % 180 == 0 ? image.getHeight() : image.getWidth();
			scalex = (double)imageWidth/(double)vw;
			scaley = (double)imageHeight/(double)vh;
		}

		private Point mapPointer(int x, int y)
		{
			int displayX = (int)((x-cx) * scalex);
			int displayY = (int)((y-cy) * scaley);
			if(config.isRunning)
			{
				return new Point(displayX, displayY);
			}

			int sourceWidth = rotation % 180 == 0 ? lcdWidth : lcdHeight;
			int sourceHeight = rotation % 180 == 0 ? lcdHeight : lcdWidth;
			switch(rotation)
			{
				case 90: return new Point(sourceWidth - 1 - displayY, displayX);
				case 180: return new Point(sourceWidth - 1 - displayX, sourceHeight - 1 - displayY);
				case 270: return new Point(displayY, sourceHeight - 1 - displayX);
				default: return new Point(displayX, displayY);
			}
		}

		public void paint(Graphics g)
		{
			try
			{
				Graphics2D cgc = (Graphics2D)g.create();
				BufferedImage image;
				if (config.isRunning)
				{
					image = config.getLCD();
				}
				else
				{
					image = Mobile.getPlatform().getLCD();
					if(limitFPS>0)
					{
						Thread.sleep(limitFPS);
					}
				}
				if(configScaleActive != config.isRunning)
				{
					configScaleActive = config.isRunning;
					FreeJ2ME.this.resize();
				}
				int displayRotation = config.isRunning ? 0 : rotation;
				int imageWidth = image.getWidth();
				int imageHeight = image.getHeight();
				cgc.translate(cx + cw / 2.0, cy + ch / 2.0);
				cgc.rotate(-Math.toRadians(displayRotation));
				if(displayRotation % 180 == 0)
				{
					cgc.scale((double)cw / imageWidth, (double)ch / imageHeight);
				}
				else
				{
					cgc.scale((double)ch / imageWidth, (double)cw / imageHeight);
				}
				cgc.drawImage(image, -imageWidth / 2, -imageHeight / 2, null);
				cgc.dispose();
			}
			catch (Exception e)
			{
				System.out.println(e.getMessage());
			}
		}
	}
}
