import javax.swing.*;
import java.awt.*;
import java.util.List;

public class MainGame extends JFrame {
    private final Controller controller;
    private final GamePanel gamePanel;
    private final Menu menuPanel;
    private final CardLayout cardLayout;
    private final JPanel mainPanel;

    public MainGame() {
        setTitle("Сетевая Змейка");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 700);

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);

        controller = new Controller(this);
        menuPanel = new Menu(controller);
        gamePanel = new GamePanel(controller);

        mainPanel.add(menuPanel, "MENU");
        mainPanel.add(gamePanel, "GAME");

        add(mainPanel);
        setLocationRelativeTo(null);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                controller.shutdown();
            }
        });
    }

    public void showMenu() {
        SwingUtilities.invokeLater(() -> {
            cardLayout.show(mainPanel, "MENU");
            menuPanel.requestFocusInWindow();
        });
    }

    public void showGame() {
        SwingUtilities.invokeLater(() -> {
            cardLayout.show(mainPanel, "GAME");
            gamePanel.requestFocusInWindow();
        });
    }

    public void updateGamePanel() {
        SwingUtilities.invokeLater(() -> gamePanel.repaint());
    }

    public void updateGamesList(List<GameInfo> games) {
        SwingUtilities.invokeLater(() -> menuPanel.updateGamesList(games));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MainGame game = new MainGame();
            game.setVisible(true);
        });
    }
}