//package view;
//
//import me.ippolitov.fit.snakes.SnakesProto;
//import model.GameModel;
//import model.GameNode;
//
//import javax.swing.*;
//import java.awt.*;
//import java.awt.event.KeyAdapter;
//import java.awt.event.KeyEvent;
//import java.net.InetAddress;
//import java.util.List;
//import java.util.Map;
//
//public class MainFrame extends JFrame {
//    private final GameModel model = GameModel.getInstance();
//    private final GameNode node = new GameNode();
//    private JList<String> gamesList;
//    private GamePanel gamePanel;
//    private JList<String> playersList;
//    private JTextField nameField;
//
//    public MainFrame() {
//        setTitle("Snakes Game");
//        setSize(800, 600);
//        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
//        setLayout(new BorderLayout());
//
//        JPanel leftPanel = new JPanel(new BorderLayout());
//        gamesList = new JList<>();
//        leftPanel.add(new JScrollPane(gamesList), BorderLayout.CENTER);
//        JButton joinButton = new JButton("Join");
//        joinButton.addActionListener(e -> joinGame());
//        JButton newGameButton = new JButton("New Game");
//        newGameButton.addActionListener(e -> startNewGame());
//        JPanel buttons = new JPanel();
//        buttons.add(joinButton);
//        buttons.add(newGameButton);
//        nameField = new JTextField("PlayerName");
//        buttons.add(nameField);
//        leftPanel.add(buttons, BorderLayout.SOUTH);
//        add(leftPanel, BorderLayout.WEST);
//
//        gamePanel = new GamePanel();
//        add(gamePanel, BorderLayout.CENTER);
//
//        playersList = new JList<>();
//        add(new JScrollPane(playersList), BorderLayout.EAST);
//
//        addKeyListener(new KeyAdapter() {
//            @Override
//            public void keyPressed(KeyEvent e) {
//                SnakesProto.Direction dir = null;
//                switch (e.getKeyCode()) {
//                    case KeyEvent.VK_W -> dir = SnakesProto.Direction.UP;
//                    case KeyEvent.VK_S -> dir = SnakesProto.Direction.DOWN;
//                    case KeyEvent.VK_A -> dir = SnakesProto.Direction.LEFT;
//                    case KeyEvent.VK_D -> dir = SnakesProto.Direction.RIGHT;
//                }
//                if (dir != null) {
//                    node.sendSteer(dir);
//                }
//            }
//        });
//        setFocusable(true);
//
//        node.startMulticastReceiver(this::updateGamesList);
//        node.startUnicastSocket();
//
//        setVisible(true);
//    }
//
//    private void updateGamesList(Map<String, SnakesProto.GameAnnouncement> games) {
//        SwingUtilities.invokeLater(() -> {
//            DefaultListModel<String> listModel = new DefaultListModel<>();
//            games.keySet().forEach(listModel::addElement);
//            gamesList.setModel(listModel);
//        });
//    }
//
//    private void joinGame() {
//        String selected = gamesList.getSelectedValue();
//        if (selected == null) {
//            JOptionPane.showMessageDialog(this, "Please select a game to join");
//            return;
//        }
//        SnakesProto.GameAnnouncement ann = model.getAnnouncements().get(selected);
//        if (ann == null) return;
//
//        String playerName = nameField.getText().trim();
//        if (playerName.isEmpty()) {
//            playerName = "Player";
//        }
//        InetAddress masterAddr = model.getMasterAddress(selected);
//        int masterPort = model.getMasterPort(selected);
//        node.joinGame(ann.getGameName(), playerName, masterAddr, masterPort, false);
//        startGameLoop();
//    }
//
//    private void startNewGame() {
//        String widthStr = JOptionPane.showInputDialog(this, "Width (10-100)", "40");
//        if (widthStr == null) return;
//
//        String heightStr = JOptionPane.showInputDialog(this, "Height (10-100)", "30");
//        if (heightStr == null) return;
//
//        String foodStaticStr = JOptionPane.showInputDialog(this, "Food Static (0-100)", "1");
//        if (foodStaticStr == null) return;
//
//        String delayMsStr = JOptionPane.showInputDialog(this, "Delay ms (100-3000)", "1000");
//        if (delayMsStr == null) return;
//
//        try {
//            int width = Integer.parseInt(widthStr);
//            int height = Integer.parseInt(heightStr);
//            int foodStatic = Integer.parseInt(foodStaticStr);
//            int delayMs = Integer.parseInt(delayMsStr);
//
//            if (width < 10 || width > 100 || height < 10 || height > 100 ||
//                    foodStatic < 0 || foodStatic > 100 || delayMs < 100 || delayMs > 3000) {
//                JOptionPane.showMessageDialog(this, "Invalid parameters! Using defaults.");
//                width = 40; height = 30; foodStatic = 1; delayMs = 1000;
//            }
//
//            SnakesProto.GameConfig config = SnakesProto.GameConfig.newBuilder()
//                    .setWidth(width).setHeight(height).setFoodStatic(foodStatic).setStateDelayMs(delayMs)
//                    .build();
//            String playerName = nameField.getText().trim();
//            if (playerName.isEmpty()) {
//                playerName = "Player";
//            }
//            String gameName = "MyGame";
//
//            node.startNewGame(gameName, playerName, config);
//            startGameLoop();
//        } catch (NumberFormatException e) {
//            JOptionPane.showMessageDialog(this, "Invalid number format! Using defaults.");
//            SnakesProto.GameConfig config = SnakesProto.GameConfig.newBuilder()
//                    .setWidth(40).setHeight(30).setFoodStatic(1).setStateDelayMs(1000)
//                    .build();
//            String playerName = nameField.getText().trim();
//            if (playerName.isEmpty()) {
//                playerName = "Player";
//            }
//            node.startNewGame("MyGame", playerName, config);
//            startGameLoop();
//        }
//    }
//
//    private void startGameLoop() {
//        new Thread(() -> {
//            while (true) {
//                SnakesProto.GameState state = model.getCurrentState();
//                if (state != null) {
//                    gamePanel.updateState(state);
//                    updatePlayersList(state.getPlayers().getPlayersList());
//                }
//                try {
//                    Thread.sleep(100);
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                    break;
//                }
//            }
//        }).start();
//    }
//
//    private void updatePlayersList(List<SnakesProto.GamePlayer> players) {
//        SwingUtilities.invokeLater(() -> {
//            DefaultListModel<String> listModel = new DefaultListModel<>();
//            players.forEach(p -> listModel.addElement(p.getName() + ": " + p.getScore() + " (" + p.getRole() + ")"));
//            playersList.setModel(listModel);
//        });
//    }
//}