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

import net.java.games.input.Component;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;
import net.java.games.input.Event;
import net.java.games.input.EventQueue;
import net.java.games.input.Component;
import net.java.games.input.Component.Identifier;
import org.recompile.mobile.*;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.FilenameFilter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;

public class FreeJ2ME
{
    public static void main(String args[])
    {
        FreeJ2ME app = new FreeJ2ME(args);
    }

    private Frame main;
    private Canvas graf;
    private int lcdWidth;
    private int lcdHeight;

    private LCD lcd;

    private int xborder;
    private int yborder;

    private static List<Controller> gamepads = new ArrayList<>();
    private Config config;
    private boolean useNokiaControls = true;
    private boolean rotateDisplay = false;
    private int limitFPS = 0;

    // Controle de estado do gamepad
    private final Map<Integer, Boolean> gamepadKeyStates = new ConcurrentHashMap<>();
    private final Map<String, Integer> componentToKeyMap = new HashMap<>();
    private final Set<Integer> pressedKeys = new HashSet<>();

    // Thread e controle
    private volatile boolean isRunning = true;
    private Thread gamepadThread;

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
                shutdownGamepad();
                System.exit(0);
            }
        });

		// Setup Device //
		lcdWidth = main.getWidth();
		lcdHeight = main.getHeight();

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
                if(config.isRunning)
                {
                    config.keyPressed(getMobileKey(e.getKeyCode()));
                }
                else
                {
                    Mobile.getPlatform().keyPressed(getMobileKey(e.getKeyCode()));
                }
            }

            public void keyReleased(KeyEvent e)
            {
                if(config.isRunning)
                {
                    config.keyReleased(getMobileKey(e.getKeyCode()));
                }
                else
                {
                    Mobile.getPlatform().keyReleased(getMobileKey(e.getKeyCode()));
                }
            }

            public void keyTyped(KeyEvent e) { }
        });

        lcd.addMouseListener(new MouseListener()
        {
            public void mousePressed(MouseEvent e)
            {
                int x = (int)((e.getX()-lcd.cx) * lcd.scalex);
                int y = (int)((e.getY()-lcd.cy) * lcd.scaley);
                Mobile.getPlatform().pointerPressed(x, y);
            }

            public void mouseReleased(MouseEvent e)
            {
                int x = (int)((e.getX()-lcd.cx) * lcd.scalex);
                int y = (int)((e.getY()-lcd.cy) * lcd.scaley);
                Mobile.getPlatform().pointerReleased(x, y);
            }

            public void mouseExited(MouseEvent e) { }
            public void mouseEntered(MouseEvent e) { }
            public void mouseClicked(MouseEvent e) { }
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
        main.setSize(lcdWidth+xborder, lcdHeight+yborder);

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
            settingsChanged();

            initGamepad(); // Inicia a detecção e o loop do gamepad
            Mobile.getPlatform().runJar();
        }
        else
        {
            System.out.println("Couldn't load jar...");
        }
    }

    private void settingsChanged()
    {
        String rotate = config.settings.get("rotate");
        if(rotate.equals("on")) { rotateDisplay = true; }
        if(rotate.equals("off")) { rotateDisplay = false; }

        limitFPS = Integer.parseInt(config.settings.get("fps"));
        if(limitFPS>0) { limitFPS = 1000 / limitFPS; }

        String sound = config.settings.get("sound");
        if(sound.equals("on")) { Mobile.getPlatform().sound = true; }
        if(sound.equals("off")) { Mobile.getPlatform().sound = false; }

        String nokia = config.settings.get("nokia");
        if(nokia.equals("on")) { useNokiaControls = true; }
        if(nokia.equals("off")) { useNokiaControls = false; }

        int w = rotateDisplay ? Integer.parseInt(config.settings.get("height")) : Integer.parseInt(config.settings.get("width"));
        int h = rotateDisplay ? Integer.parseInt(config.settings.get("width")) : Integer.parseInt(config.settings.get("height"));

        if(lcdWidth != w || lcdHeight != h)
        {
            lcdWidth = w;
            lcdHeight = h;

            Mobile.getPlatform().resizeLCD(w, h);

            resize();
            main.setSize(lcdWidth+xborder, lcdHeight+yborder);
        }
    }

    private void initGamepad() {
        gamepadThread = new Thread(() -> {
            try {
                System.out.println("Initializing gamepad detection...");

                // Inicializa o mapeamento de componentes
                initializeComponentMapping();

                // Detecta controladores
                // detectControllers();

                if (gamepads.isEmpty()) {
                    System.out.println("No gamepad detected.");
                    return;
                }

                System.out.println("Gamepad(s) initialized successfully. Found: " + gamepads.size());

                // Loop principal de polling
                long lastPollTime = System.currentTimeMillis();
                while (isRunning) {
                    boolean hasChanges = false;

                    // Poll todos os gamepads
                    for (Controller controller : gamepads) {
                        controller.poll();
                        hasChanges |= processControllerEvents(controller);
                    }

                    // Controla a taxa de polling
                    long currentTime = System.currentTimeMillis();
                    long elapsed = currentTime - lastPollTime;
                    if (elapsed < 16) { // ~60 FPS
                        Thread.sleep(16 - elapsed);
                    }
                    lastPollTime = System.currentTimeMillis();
                }
            } catch (Exception e) {
                System.err.println("Gamepad thread error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                System.out.println("Gamepad thread stopped.");
            }
        }, "GamepadThread");

        gamepadThread.setDaemon(true);
        gamepadThread.start();
    }

    private void detectControllers() {
        gamepads.clear();
        Controller[] controllers = ControllerEnvironment.getDefaultEnvironment().getControllers();

        for (Controller c : controllers) {
            Controller.Type type = c.getType();
            String name = c.getName();

            // Suporte a diferentes tipos de controladores
            if (type == Controller.Type.GAMEPAD ||
                    type == Controller.Type.STICK ||
                    type == Controller.Type.WHEEL || // Alguns wheels têm botões gamepad
                    type == Controller.Type.FINGERSTICK) {

                System.out.println("Gamepad detected: " + name + " (Type: " + type + ")");

                // Verifica se tem componentes suficientes para ser um gamepad
                if (hasSufficientComponents(c)) {
                    gamepads.add(c);
                    System.out.println("Added gamepad: " + name);
                }
            }
        }

        // Ordena por nome para consistência
        gamepads.sort(Comparator.comparing(Controller::getName));

        // Mantém apenas o primeiro gamepad se houver múltiplos
        if (gamepads.size() > 1) {
            System.out.println("Multiple gamepads detected. Using: " + gamepads.get(0).getName());
            gamepads = gamepads.subList(0, 1);
        }
    }

    private boolean hasSufficientComponents(Controller controller) {
        Component[] components = controller.getComponents();
        int buttonCount = 0;
        boolean hasDPad = false;
        boolean hasAxes = false;

        for (Component comp : components) {
            if (comp.getIdentifier().getName().startsWith("Button")) {
                buttonCount++;
            } else if (comp.getName().toLowerCase().contains("pov") ||
                    comp.getName().toLowerCase().contains("dpad")) {
                hasDPad = true;
            } else if (comp.isAnalog()) {
                hasAxes = true;
            }
        }

        return buttonCount >= 4 && (hasDPad || hasAxes);
    }

    private void initializeComponentMapping() {
        componentToKeyMap.clear();

        // Mapeamento padrão para botões (pode ser expandido para diferentes controladores)
        // Xbox/Generic
        componentToKeyMap.put("Button 0", Mobile.KEY_NUM5);        // A/X
        componentToKeyMap.put("Button 1", Mobile.KEY_NUM0);        // B/Y
        componentToKeyMap.put("Button 2", Mobile.NOKIA_SOFT1);     // X/B
        componentToKeyMap.put("Button 3", Mobile.NOKIA_SOFT2);     // Y/A
        componentToKeyMap.put("Button 4", Mobile.KEY_NUM1);        // LB
        componentToKeyMap.put("Button 5", Mobile.KEY_NUM3);        // RB
        componentToKeyMap.put("Button 6", Mobile.KEY_STAR);        // Back/Share
        componentToKeyMap.put("Button 7", Mobile.KEY_POUND);       // Start/Options
        componentToKeyMap.put("Button 8", Mobile.KEY_NUM7);        // LS (Left Stick)
        componentToKeyMap.put("Button 9", Mobile.KEY_NUM9);        // RS (Right Stick)

        // PS4/PS5
        componentToKeyMap.put("0", Mobile.KEY_NUM5);               // X
        componentToKeyMap.put("1", Mobile.KEY_NUM0);               // Circle
        componentToKeyMap.put("2", Mobile.NOKIA_SOFT1);            // Square
        componentToKeyMap.put("3", Mobile.NOKIA_SOFT2);            // Triangle
        componentToKeyMap.put("4", Mobile.KEY_NUM1);               // L1
        componentToKeyMap.put("5", Mobile.KEY_NUM3);               // R1
        componentToKeyMap.put("6", Mobile.KEY_STAR);               // L2
        componentToKeyMap.put("7", Mobile.KEY_POUND);              // R2
        componentToKeyMap.put("8", Mobile.KEY_NUM7);               // Share
        componentToKeyMap.put("9", Mobile.KEY_NUM9);               // Options

        // D-Pad universal
        componentToKeyMap.put("pov", Mobile.KEY_NUM2);             // Base para D-Pad
        componentToKeyMap.put("dpad", Mobile.KEY_NUM2);            // Base para D-Pad
    }

    private boolean processControllerEvents(Controller controller) {
        if (controller == null) {
            return false;
        }

        controller.poll();
        EventQueue queue = controller.getEventQueue();
        Event event = new Event();
        boolean hasChanges = false;

        while (queue.getNextEvent(event)) {
            Component component = event.getComponent();
            float value = event.getValue();

            // Mapeia o D-Pad, que é um 'axis' especial.
            if (component.getIdentifier() instanceof Component.Identifier.Axis && component.getName().equals("pov")) {
                // O valor do D-Pad é um ângulo que representa a direção.
                processDPad(value);
                hasChanges = true;
                continue;
            }

            // Mapeia botões do gamepad
            if (component.getIdentifier() instanceof Component.Identifier.Button) {
                boolean isPressed = value == 1.0f; // Para botões, o valor é 1.0f (pressionado) ou 0.0f (solto)
                Integer mobileKey = getMobileKeyForButton(component.getName());
                if (mobileKey != null) {
                    // Notifica o emulador sobre a ação de pressionar ou soltar.
                    if (isPressed) {
                        Mobile.getPlatform().keyPressed(mobileKey);
                    } else {
                        Mobile.getPlatform().keyReleased(mobileKey);
                    }
                    hasChanges = true;
                }
            }
        }

        return hasChanges;
    }
    // O método `processDPad` deve ser implementado separadamente.
    private void processDPad(float value) {
        if (value == Component.POV.UP) {
            Mobile.getPlatform().keyPressed(Mobile.KEY_NUM2);
            Mobile.getPlatform().keyReleased(Mobile.KEY_NUM2);
        } else if (value == Component.POV.DOWN) {
            Mobile.getPlatform().keyPressed(Mobile.KEY_NUM8);
            Mobile.getPlatform().keyReleased(Mobile.KEY_NUM8);
        } else if (value == Component.POV.LEFT) {
            Mobile.getPlatform().keyPressed(Mobile.KEY_NUM4);
            Mobile.getPlatform().keyReleased(Mobile.KEY_NUM4);
        } else if (value == Component.POV.RIGHT) {
            Mobile.getPlatform().keyPressed(Mobile.KEY_NUM6);
            Mobile.getPlatform().keyReleased(Mobile.KEY_NUM6);
        }
    }
    // O método `getMobileKeyForButton` deve ser implementado.
    private Integer getMobileKeyForButton(String buttonName) {
        switch (buttonName) {
            case "Button 0": // Botão X (para PS4)
                return Mobile.KEY_NUM5; // Tecla de ação
            case "Button 1": // Botão O
                return Mobile.NOKIA_SOFT1; // Tecla "soft"
            case "Button 2": // Botão quadrado
                return Mobile.KEY_STAR;
            case "Button 3": // Botão triângulo
                return Mobile.KEY_POUND;
            case "Button 9": // Botão Options (Start)
                return Mobile.NOKIA_SOFT1;
            case "Button 8": // Botão Share (Select)
                return Mobile.NOKIA_SOFT2;
            default:
                return null;
        }
    }
    //    private boolean processControllerEvents(Controller controller) {
    //        boolean hasChanges = false;
    //        EventQueue queue = controller.getEventQueue();
    //        Event event = new Event();
    //
    //        while (queue.getNextEvent(event)) {
    //            Component component = event.getComponent();
    //            float value = event.getValue();
    //            String componentName = component.getName();
    //            int componentId = component.getIdentifier().getClass().getModifiers();
    //
    //            // Processa D-Pad
    //            if (processDPad(componentName, value, componentId)) {
    //                hasChanges = true;
    //                continue;
    //            }
    //
    //            // Processa botões
    //            Integer mobileKey = getMobileKeyForComponent(componentName, componentId);
    //            if (mobileKey != null) {
    //                boolean isPressed = value > 0.5f;
    //                processKeyEvent(mobileKey, isPressed);
    //                hasChanges = true;
    //            }
    //
    //            // Processa analógicos (como botões quando pressionados completamente)
    //            if (component.isAnalog() && value > 0.9f) {
    //                Integer analogKey = getAnalogKeyForComponent(componentName);
    //                if (analogKey != null) {
    //                    processKeyEvent(analogKey, true);
    //                    hasChanges = true;
    //                }
    //            }
    //        }
    //
    //        return hasChanges;
    //    }

    private boolean processDPad(String componentName, float value, int componentId) {
        if (!componentName.toLowerCase().contains("pov") &&
                !componentName.toLowerCase().contains("dpad")) {
            return false;
        }

        int dpadKey = -1;
        if (value == Component.POV.UP || value == 0.0f) {
            dpadKey = Mobile.KEY_NUM2;
        } else if (value == Component.POV.DOWN) {
            dpadKey = Mobile.KEY_NUM8;
        } else if (value == Component.POV.LEFT) {
            dpadKey = Mobile.KEY_NUM4;
        } else if (value == Component.POV.RIGHT) {
            dpadKey = Mobile.KEY_NUM6;
        }

        if (dpadKey != -1) {
            processKeyEvent(dpadKey, value != 0.0f);
            return true;
        }

        return false;
    }

    private Integer getMobileKeyForComponent(String componentName, int componentId) {
        // Primeiro tenta o mapeamento por nome do botão
        Integer key = componentToKeyMap.get(componentName);
        if (key != null) {
            return key;
        }

        // Depois tenta pelo ID numérico (alguns controladores usam números)
        if (Character.isDigit(componentName.charAt(0))) {
            String numName = componentName.substring(0, 1);
            key = componentToKeyMap.get(numName);
            if (key != null) {
                return key;
            }
        }

        // Fallback para botões genéricos
        switch (componentId % 10) {
            case 0: return Mobile.KEY_NUM5;
            case 1: return Mobile.KEY_NUM0;
            case 2: return Mobile.NOKIA_SOFT1;
            case 3: return Mobile.NOKIA_SOFT2;
            case 4: return Mobile.KEY_NUM1;
            case 5: return Mobile.KEY_NUM3;
            case 6: return Mobile.KEY_STAR;
            case 7: return Mobile.KEY_POUND;
            default: return null;
        }
    }

    private Integer getAnalogKeyForComponent(String componentName) {
        // Mapeia analógicos como botões quando pressionados completamente
        switch (componentName.toLowerCase()) {
            case "x axis": // Left stick horizontal
                return null; // Não mapeia movimento horizontal como botão
            case "y axis": // Left stick vertical
                return null; // Não mapeia movimento vertical como botão
            case "z axis": // Right stick horizontal
                return null;
            case "rz axis": // Right stick vertical
                return null;
            case "z rotation": // Triggers
            case "slider":
                return Mobile.KEY_POUND; // L2/R2 como #
            default:
                return null;
        }
    }

    private void processKeyEvent(int mobileKey, boolean isPressed) {
        if (mobileKey == -1) return; // Tecla inválida

        Integer keyStateKey = mobileKey;

        if (isPressed) {
            if (!pressedKeys.contains(mobileKey)) {
                pressedKeys.add(mobileKey);
                gamepadKeyStates.put(keyStateKey, true);

                // Só envia se o jogo estiver rodando
                if (config.isRunning) {
                    Mobile.getPlatform().keyPressed(mobileKey);
                }
                System.out.println("Gamepad key pressed: " + mobileKey);
            }
        } else {
            if (pressedKeys.contains(mobileKey)) {
                pressedKeys.remove(mobileKey);
                gamepadKeyStates.put(keyStateKey, false);

                // Só envia se o jogo estiver rodando
                if (config.isRunning) {
                    Mobile.getPlatform().keyReleased(mobileKey);
                }
                System.out.println("Gamepad key released: " + mobileKey);
            }
        }
    }

    private void shutdownGamepad() {
        isRunning = false;
        if (gamepadThread != null && gamepadThread.isAlive()) {
            try {
                gamepadThread.interrupt();
                gamepadThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        gamepads.clear();
        pressedKeys.clear();
        gamepadKeyStates.clear();
    }

    private int getMobileKey(int keycode)
    {
        // Se a tecla for o 'escape', sempre abre a configuração
        if (keycode == KeyEvent.VK_ESCAPE) {
            config.start();
            return -1; // Retorna -1 para não passar para o jogo
        }

        // Se usar controles Nokia, mapeia as setas do teclado
        if (useNokiaControls) {
            switch(keycode) {
                case KeyEvent.VK_UP: return Mobile.NOKIA_UP;
                case KeyEvent.VK_DOWN: return Mobile.NOKIA_DOWN;
                case KeyEvent.VK_LEFT: return Mobile.NOKIA_LEFT;
                case KeyEvent.VK_RIGHT: return Mobile.NOKIA_RIGHT;
            }
        }

        // Mapeia as outras teclas do teclado
        return switch (keycode) {
            case KeyEvent.VK_0 -> Mobile.KEY_NUM0;
            case KeyEvent.VK_1 -> Mobile.KEY_NUM1;
            case KeyEvent.VK_2 -> Mobile.KEY_NUM2;
            case KeyEvent.VK_3 -> Mobile.KEY_NUM3;
            case KeyEvent.VK_4 -> Mobile.KEY_NUM4;
            case KeyEvent.VK_5 -> Mobile.KEY_NUM5;
            case KeyEvent.VK_6 -> Mobile.KEY_NUM6;
            case KeyEvent.VK_7 -> Mobile.KEY_NUM7;
            case KeyEvent.VK_8 -> Mobile.KEY_NUM8;
            case KeyEvent.VK_9 -> Mobile.KEY_NUM9;
            case KeyEvent.VK_ASTERISK -> Mobile.KEY_STAR;
            case KeyEvent.VK_NUMBER_SIGN -> Mobile.KEY_POUND;
            case KeyEvent.VK_NUMPAD0 -> Mobile.KEY_NUM0;
            case KeyEvent.VK_NUMPAD7 -> Mobile.KEY_NUM1;
            case KeyEvent.VK_NUMPAD8 -> Mobile.KEY_NUM2;
            case KeyEvent.VK_NUMPAD9 -> Mobile.KEY_NUM3;
            case KeyEvent.VK_NUMPAD4 -> Mobile.KEY_NUM4;
            case KeyEvent.VK_NUMPAD5 -> Mobile.KEY_NUM5;
            case KeyEvent.VK_NUMPAD6 -> Mobile.KEY_NUM6;
            case KeyEvent.VK_NUMPAD1 -> Mobile.KEY_NUM7;
            case KeyEvent.VK_NUMPAD2 -> Mobile.KEY_NUM8;
            case KeyEvent.VK_NUMPAD3 -> Mobile.KEY_NUM9;
            case KeyEvent.VK_UP -> Mobile.KEY_NUM2;
            case KeyEvent.VK_DOWN -> Mobile.KEY_NUM8;
            case KeyEvent.VK_LEFT -> Mobile.KEY_NUM4;
            case KeyEvent.VK_RIGHT -> Mobile.KEY_NUM6;
            case KeyEvent.VK_ENTER -> Mobile.KEY_NUM5;
            case KeyEvent.VK_Q -> Mobile.NOKIA_SOFT1;
            case KeyEvent.VK_W -> Mobile.NOKIA_SOFT2;
            case KeyEvent.VK_E -> Mobile.KEY_STAR;
            case KeyEvent.VK_R -> Mobile.KEY_POUND;
            case KeyEvent.VK_A -> -1;
            case KeyEvent.VK_Z -> -2;
            default -> keycode;
        };

    }

    private void resize()
    {
        xborder = main.getInsets().left+main.getInsets().right;
        yborder = main.getInsets().top+main.getInsets().bottom;

        //double vw = main.getWidth() - xborder * 1;
        double vw = main.getWidth();
        vw += -xborder;
        //double vh = main.getHeight() - yborder * 1;
        double vh = main.getHeight();
        vh += -yborder;

        double nw = lcdWidth;
        double nh = lcdHeight;

        nw = vw;
        nh = nw*((double)lcdHeight/(double)lcdWidth);

        if(nh>vh)
        {
            nh = vh;
            nw = nh*((double)lcdWidth/(double)lcdHeight);
        }

        lcd.updateScale((int)nw, (int)nh);
        // lcd.setFullScreenMode(false);
    }

    private class LCD extends Canvas
    {
        public int cx=0;
        public int cy=0;
        public int cw=240;
        public int ch=320;

        public double scalex=1;
        public double scaley=1;

        public void setFullScreenMode(boolean mode)
        {
            System.out.print("Set Canvas Full Screen Mode ");

            if(mode)
            {
                ch = Mobile.getPlatform().getLCD().getHeight();
                cw = Mobile.getPlatform().getLCD().getWidth();
            }
        }

        public void updateScale(int vw, int vh)
        {
            cx = (this.getWidth()-vw)/2;
            cy = (this.getHeight()-vh)/2;
            cw = vw;
            ch = vh;
            scalex = (double)lcdWidth/(double)vw;
            scaley = (double)lcdHeight/(double)vh;
        }

        public void paint(Graphics g)
        {
            try
            {
                Graphics2D cgc = (Graphics2D)this.getGraphics();
                if (config.isRunning)
                {
                    g.drawImage(config.getLCD(), cx, cy, cw, ch, null);
                }
                else
                {
                    g.drawImage(Mobile.getPlatform().getLCD(), cx, cy, cw, ch, null);
                    if(limitFPS>0)
                    {
                        Thread.sleep(limitFPS);
                    }
                }
            }
            catch (Exception e)
            {
                System.out.println("Error"+e.getMessage());
            }
        }
    }
}