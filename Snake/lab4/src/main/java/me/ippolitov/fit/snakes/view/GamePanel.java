package me.ippolitov.fit.snakes.view;

import me.ippolitov.fit.snakes.SnakesProto;
import me.ippolitov.fit.snakes.model.GameModel;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class GamePanel extends JPanel {
    private SnakesProto.GameState state;
    private final int CELL_SIZE = 20;

    public void updateState(SnakesProto.GameState newState) {
        this.state = newState;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (state == null) return;

        SnakesProto.GameConfig config = GameModel.getInstance().getConfig();
        if (config == null) return;

        int width = config.getWidth();
        int height = config.getHeight();

        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width * CELL_SIZE, height * CELL_SIZE);

        g.setColor(Color.GREEN);
        for (SnakesProto.GameState.Coord food : state.getFoodsList()) {
            g.fillRect(food.getX() * CELL_SIZE, food.getY() * CELL_SIZE, CELL_SIZE, CELL_SIZE);
        }

        Color[] colors = {Color.RED, Color.BLUE, Color.YELLOW, Color.ORANGE, Color.PINK};
        List<SnakesProto.GameState.Snake> snakes = state.getSnakesList();
        for (int i = 0; i < snakes.size(); i++) {
            SnakesProto.GameState.Snake snake = snakes.get(i);
            g.setColor(colors[i % colors.length]);

            int x = 0, y = 0;
            for (SnakesProto.GameState.Coord point : snake.getPointsList()) {
                x = (x + point.getX() + width) % width;
                y = (y + point.getY() + height) % height;
                g.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
            }
        }
    }
}