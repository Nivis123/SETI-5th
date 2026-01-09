// GamePanel.java
import me.ippolitov.fit.snakes.SnakesProto;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

public class GamePanel extends JPanel {
    private Controller controller;
    private static final int CELL_SIZE = 18;
    private static final int INFO_PANEL_WIDTH = 220;

    private final Color BACKGROUND_COLOR = new Color(240, 242, 245);
    private final Color FIELD_COLOR = new Color(255, 255, 255);
    private final Color GRID_COLOR = new Color(235, 238, 245);
    private final Color INFO_PANEL_COLOR = new Color(255, 255, 255);
    private final Color TEXT_COLOR = new Color(48, 49, 51);
    private final Color SECONDARY_TEXT_COLOR = new Color(144, 147, 153);
    private final Font MAIN_FONT = new Font("Segoe UI", Font.PLAIN, 12);
    private final Font TITLE_FONT = new Font("Segoe UI Semibold", Font.PLAIN, 16);
    private final Font SCORE_FONT = new Font("Segoe UI Semibold", Font.PLAIN, 12);

    private Color[] snakeColors = {
            new Color(64, 158, 255),   // Синий
            new Color(103, 194, 58),   // Зеленый
            new Color(230, 162, 60),   // Оранжевый
            new Color(245, 108, 108),  // Красный
            new Color(144, 147, 153),  // Серый
            new Color(156, 39, 176),   // Фиолетовый
            new Color(0, 188, 212),    // Бирюзовый
            new Color(255, 87, 34)     // Темно-оранжевый
    };

    public GamePanel(Controller ctrl) {
        this.controller = ctrl;
        setBackground(BACKGROUND_COLOR);
        setFocusable(true);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKey(e.getKeyCode());
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
            }
        });
    }

    private void handleKey(int keyCode) {
        SnakesProto.Direction direction = null;

        switch (keyCode) {
            case KeyEvent.VK_W:
            case KeyEvent.VK_UP:
                direction = SnakesProto.Direction.UP;
                break;
            case KeyEvent.VK_S:
            case KeyEvent.VK_DOWN:
                direction = SnakesProto.Direction.DOWN;
                break;
            case KeyEvent.VK_A:
            case KeyEvent.VK_LEFT:
                direction = SnakesProto.Direction.LEFT;
                break;
            case KeyEvent.VK_D:
            case KeyEvent.VK_RIGHT:
                direction = SnakesProto.Direction.RIGHT;
                break;
            case KeyEvent.VK_ESCAPE:
                int result = JOptionPane.showConfirmDialog(
                        this,
                        "Вы уверены, что хотите выйти из игры?",
                        "Выход",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );
                if (result == JOptionPane.YES_OPTION) {
                    controller.exitGame();
                }
                return;
        }

        if (direction != null) {
            controller.handleDirection(direction);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        StateManager stateMgr = controller.getStateManager();
        SnakesProto.GameState state = stateMgr.getCurrentState();

        if (state == null) {
            drawWaitingScreen(g2d);
            return;
        }

        SnakesProto.GameConfig config = stateMgr.getConfig();
        if (config == null) return;

        int fieldWidth = config.getWidth() * CELL_SIZE;
        int fieldHeight = config.getHeight() * CELL_SIZE;

        int offsetX = (getWidth() - fieldWidth - INFO_PANEL_WIDTH) / 2;
        int offsetY = (getHeight() - fieldHeight) / 2;

        if (offsetX < 10) offsetX = 10;
        if (offsetY < 10) offsetY = 10;

        g2d.setColor(FIELD_COLOR);
        g2d.fillRoundRect(offsetX, offsetY, fieldWidth, fieldHeight, 8, 8);

        g2d.setColor(new Color(220, 223, 230));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawRoundRect(offsetX, offsetY, fieldWidth, fieldHeight, 8, 8);
        g2d.setStroke(new BasicStroke(1));

        g2d.setColor(GRID_COLOR);
        for (int i = 0; i <= config.getWidth(); i++) {
            g2d.drawLine(offsetX + i * CELL_SIZE, offsetY,
                    offsetX + i * CELL_SIZE, offsetY + fieldHeight);
        }
        for (int i = 0; i <= config.getHeight(); i++) {
            g2d.drawLine(offsetX, offsetY + i * CELL_SIZE,
                    offsetX + fieldWidth, offsetY + i * CELL_SIZE);
        }

        g2d.setColor(new Color(245, 108, 108));
        for (SnakesProto.GameState.Coord food : state.getFoodsList()) {
            int x = offsetX + food.getX() * CELL_SIZE;
            int y = offsetY + food.getY() * CELL_SIZE;

            g2d.setColor(new Color(245, 108, 108, 100));
            g2d.fillOval(x + 2, y + 2, CELL_SIZE - 4, CELL_SIZE - 4);

            g2d.setColor(new Color(245, 108, 108));
            g2d.fillOval(x, y, CELL_SIZE - 4, CELL_SIZE - 4);

            g2d.setColor(new Color(255, 255, 255, 150));
            g2d.fillOval(x + 3, y + 3, CELL_SIZE / 4, CELL_SIZE / 4);
        }

        for (SnakesProto.GameState.Snake snake : state.getSnakesList()) {
            Color snakeColor = snakeColors[snake.getPlayerId() % snakeColors.length];
            drawSnake(g2d, snake, snakeColor, offsetX, offsetY, config);
        }

        drawInfoPanel(g2d, state, offsetX + fieldWidth + 20, offsetY);

        drawControls(g2d, offsetX, offsetY + fieldHeight + 15);
    }

    private void drawWaitingScreen(Graphics2D g2d) {
        g2d.setColor(SECONDARY_TEXT_COLOR);
        g2d.setFont(new Font("Segoe UI", Font.PLAIN, 20));
        String msg = "Ожидание начала игры...";
        FontMetrics fm = g2d.getFontMetrics();
        int x = (getWidth() - fm.stringWidth(msg)) / 2;
        int y = getHeight() / 2;
        g2d.drawString(msg, x, y);
    }

    private void drawSnake(Graphics2D g2d, SnakesProto.GameState.Snake snake,
                           Color color, int offsetX, int offsetY,
                           SnakesProto.GameConfig config) {
        List<Point> cells = getSnakeCells(snake, config);
        if (cells.isEmpty()) return;

        for (int i = 0; i < cells.size(); i++) {
            Point cell = cells.get(i);
            int x = offsetX + cell.x * CELL_SIZE;
            int y = offsetY + cell.y * CELL_SIZE;

            g2d.setColor(new Color(0, 0, 0, 20));
            g2d.fillRoundRect(x + 1, y + 1, CELL_SIZE - 2, CELL_SIZE - 2, 4, 4);

            if (i == 0) {
                g2d.setColor(color.brighter());
            } else {
                float factor = 1.0f - (i * 0.2f / cells.size());
                g2d.setColor(new Color(
                        Math.max(0, (int)(color.getRed() * factor)),
                        Math.max(0, (int)(color.getGreen() * factor)),
                        Math.max(0, (int)(color.getBlue() * factor))
                ));
            }

            g2d.fillRoundRect(x, y, CELL_SIZE - 2, CELL_SIZE - 2, 4, 4);

            g2d.setColor(color.darker());
            g2d.drawRoundRect(x, y, CELL_SIZE - 2, CELL_SIZE - 2, 4, 4);

            if (i == 0) {
                g2d.setColor(Color.WHITE);
                SnakesProto.Direction dir = snake.getHeadDirection();

                int eyeSize = 4;
                int eye1X = x + CELL_SIZE / 2 - 3;
                int eye1Y = y + CELL_SIZE / 2 - 3;
                int eye2X = x + CELL_SIZE / 2 + 1;
                int eye2Y = y + CELL_SIZE / 2 - 3;

                switch (dir) {
                    case UP:
                        eye1Y = y + 4;
                        eye2Y = y + 4;
                        break;
                    case DOWN:
                        eye1Y = y + CELL_SIZE - 8;
                        eye2Y = y + CELL_SIZE - 8;
                        break;
                    case LEFT:
                        eye1X = x + 4;
                        eye2X = x + 4;
                        eye1Y = y + CELL_SIZE / 2 - 3;
                        eye2Y = y + CELL_SIZE / 2 + 1;
                        break;
                    case RIGHT:
                        eye1X = x + CELL_SIZE - 8;
                        eye2X = x + CELL_SIZE - 8;
                        eye1Y = y + CELL_SIZE / 2 - 3;
                        eye2Y = y + CELL_SIZE / 2 + 1;
                        break;
                }

                g2d.fillOval(eye1X, eye1Y, eyeSize, eyeSize);
                g2d.fillOval(eye2X, eye2Y, eyeSize, eyeSize);

                g2d.setColor(Color.BLACK);
                g2d.fillOval(eye1X + 1, eye1Y + 1, eyeSize - 2, eyeSize - 2);
                g2d.fillOval(eye2X + 1, eye2Y + 1, eyeSize - 2, eyeSize - 2);
            }
        }
    }

    private List<Point> getSnakeCells(SnakesProto.GameState.Snake snake, SnakesProto.GameConfig config) {
        List<Point> cells = new ArrayList<>();
        if (snake.getPointsCount() == 0) return cells;

        int x = snake.getPoints(0).getX();
        int y = snake.getPoints(0).getY();
        cells.add(new Point(x, y));

        for (int i = 1; i < snake.getPointsCount(); i++) {
            SnakesProto.GameState.Coord coord = snake.getPoints(i);
            int dx = coord.getX();
            int dy = coord.getY();

            int steps = Math.max(Math.abs(dx), Math.abs(dy));
            int signX = dx == 0 ? 0 : (dx > 0 ? 1 : -1);
            int signY = dy == 0 ? 0 : (dy > 0 ? 1 : -1);

            for (int j = 1; j <= steps; j++) {
                x = (x + signX + config.getWidth()) % config.getWidth();
                y = (y + signY + config.getHeight()) % config.getHeight();
                cells.add(new Point(x, y));
            }
        }

        return cells;
    }

    private void drawInfoPanel(Graphics2D g2d, SnakesProto.GameState state, int x, int y) {
        g2d.setColor(INFO_PANEL_COLOR);
        g2d.fillRoundRect(x, y, INFO_PANEL_WIDTH - 20, 450, 12, 12);

        g2d.setColor(new Color(0, 0, 0, 10));
        g2d.fillRoundRect(x + 2, y + 2, INFO_PANEL_WIDTH - 20, 450, 12, 12);

        g2d.setColor(new Color(220, 223, 230));
        g2d.drawRoundRect(x, y, INFO_PANEL_WIDTH - 20, 450, 12, 12);

        g2d.setColor(TEXT_COLOR);
        g2d.setFont(TITLE_FONT);
        g2d.drawString("ИГРОКИ", x + 15, y + 30);

        g2d.setColor(new Color(235, 238, 245));
        g2d.setStroke(new BasicStroke(1));
        g2d.drawLine(x + 15, y + 45, x + INFO_PANEL_WIDTH - 35, y + 45);

        g2d.setFont(MAIN_FONT);
        int yPos = y + 70;

        List<SnakesProto.GamePlayer> sortedPlayers = new ArrayList<>(state.getPlayers().getPlayersList());
        sortedPlayers.sort((p1, p2) -> Integer.compare(p2.getScore(), p1.getScore()));

        for (SnakesProto.GamePlayer player : sortedPlayers) {
            Color playerColor = snakeColors[player.getId() % snakeColors.length];

            g2d.setColor(playerColor);
            g2d.fillRoundRect(x + 15, yPos - 10, 12, 12, 3, 3);
            g2d.setColor(playerColor.darker());
            g2d.drawRoundRect(x + 15, yPos - 10, 12, 12, 3, 3);

            g2d.setColor(TEXT_COLOR);
            String name = player.getName();
            if (name.length() > 18) {
                name = name.substring(0, 15) + "...";
            }
            g2d.drawString(name, x + 32, yPos);

            g2d.setColor(new Color(103, 194, 58));
            g2d.setFont(SCORE_FONT);
            String score = String.valueOf(player.getScore());
            g2d.drawString(score, x + INFO_PANEL_WIDTH - 60, yPos);

            g2d.setColor(SECONDARY_TEXT_COLOR);
            g2d.setFont(new Font("Segoe UI", Font.ITALIC, 10));
            String role = "";
            switch (player.getRole()) {
                case MASTER: role = "HOST"; break;
                case DEPUTY: role = "DEPUTY"; break;
                case NORMAL: role = "NORMAL"; break;
                case VIEWER: role = "VIEW"; break;
            }
            if (!role.isEmpty()) {
                g2d.drawString(role, x + 32, yPos + 15);
            }

            g2d.setFont(MAIN_FONT);
            yPos += 40;
        }

        g2d.setColor(new Color(235, 238, 245));
        g2d.drawLine(x + 15, yPos + 15, x + INFO_PANEL_WIDTH - 35, yPos + 15);

        yPos += 40;
        g2d.setColor(SECONDARY_TEXT_COLOR);
        g2d.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        g2d.drawString("Статистика игры:", x + 15, yPos);

        yPos += 25;
        g2d.drawString("Змеек: " + state.getSnakesCount(), x + 15, yPos);
        yPos += 20;
        g2d.drawString("Еды: " + state.getFoodsCount(), x + 15, yPos);
        yPos += 20;
        g2d.drawString("Ход: " + state.getStateOrder(), x + 15, yPos);

        int playerId = controller.getPlayerId();
        if (playerId != -1) {
            yPos += 40;
            g2d.setColor(TEXT_COLOR);
            g2d.setFont(new Font("Segoe UI Semibold", Font.PLAIN, 12));
            g2d.drawString("Ваш ID: " + playerId, x + 15, yPos);
        }
    }

    private void drawControls(Graphics2D g2d, int x, int y) {
        g2d.setColor(SECONDARY_TEXT_COLOR);
        g2d.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        String text = "Управление: W/A/S/D или стрелки  |  ESC - выход";
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int centerX = x + (getWidth() - INFO_PANEL_WIDTH - x - textWidth) / 2;
        g2d.drawString(text, centerX, y);
    }
}