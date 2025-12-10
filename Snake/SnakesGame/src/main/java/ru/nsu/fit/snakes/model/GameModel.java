package ru.nsu.fit.snakes.model;

import me.ippolitov.fit.snakes.SnakesProto;

import java.awt.*;
import java.net.InetAddress;
import java.util.*;
import java.util.List;

public class GameModel {
    private static GameModel instance;
    private SnakesProto.GameConfig config;
    private SnakesProto.GameState currentState;
    private Map<String, SnakesProto.GameAnnouncement> announcements = new HashMap<>();
    private Map<String, InetAddress> masterAddresses = new HashMap<>();
    private Map<String, Integer> masterPorts = new HashMap<>();
    private int playerId = -1;
    private String playerName;
    private SnakesProto.NodeRole role = SnakesProto.NodeRole.NORMAL;

    private GameModel() {}

    public static GameModel getInstance() {
        if (instance == null) instance = new GameModel();
        return instance;
    }

    public void setConfig(SnakesProto.GameConfig config) {
        this.config = config;
    }

    public SnakesProto.GameConfig getConfig() {
        return config;
    }

    public void updateState(SnakesProto.GameState state) {
        if (currentState != null && state.getStateOrder() <= currentState.getStateOrder()) return;
        currentState = state;
    }

    public SnakesProto.GameState getCurrentState() {
        return currentState;
    }

    public void addAnnouncement(String gameName, SnakesProto.GameAnnouncement ann, InetAddress addr, int port) {
        announcements.put(gameName, ann);
        masterAddresses.put(gameName, addr);
        masterPorts.put(gameName, port);
    }

    public Map<String, SnakesProto.GameAnnouncement> getAnnouncements() {
        return announcements;
    }

    public InetAddress getMasterAddress(String gameName) {
        return masterAddresses.get(gameName);
    }

    public Integer getMasterPort(String gameName) {
        return masterPorts.get(gameName);
    }

    public void setPlayerId(int id) {
        this.playerId = id;
    }

    public int getPlayerId() {
        return playerId;
    }

    public void setPlayerName(String name) {
        this.playerName = name;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setRole(SnakesProto.NodeRole role) {
        this.role = role;
    }

    public SnakesProto.NodeRole getRole() {
        return role;
    }

    public SnakesProto.GameState advanceState(Map<Integer, SnakesProto.Direction> steers) {
        if (role != SnakesProto.NodeRole.MASTER || currentState == null) return currentState;

        SnakesProto.GameState.Builder newState = currentState.toBuilder();
        int width = config.getWidth();
        int height = config.getHeight();

        int aliveSnakes = (int) newState.getSnakesList().stream()
                .filter(s -> s.getState() == SnakesProto.GameState.Snake.SnakeState.ALIVE)
                .count();

        Set<Point> occupied = new HashSet<>();
        List<Point> foods = new ArrayList<>();
        for (SnakesProto.GameState.Coord f : newState.getFoodsList()) {
            foods.add(new Point(f.getX(), f.getY()));
        }

        for (SnakesProto.GameState.Snake snake : newState.getSnakesList()) {
            Point pos = new Point(0, 0);
            for (SnakesProto.GameState.Coord p : snake.getPointsList()) {
                pos.x = (pos.x + p.getX() + width) % width;
                pos.y = (pos.y + p.getY() + height) % height;
                occupied.add(new Point(pos.x, pos.y));
            }
        }

        List<SnakesProto.GameState.Snake.Builder> newSnakes = new ArrayList<>();
        Set<Integer> deadSnakes = new HashSet<>();
        Map<Integer, Integer> scores = new HashMap<>();

        for (SnakesProto.GamePlayer p : currentState.getPlayers().getPlayersList()) {
            scores.put(p.getId(), p.getScore());
        }

        for (int i = 0; i < newState.getSnakesCount(); i++) {
            SnakesProto.GameState.Snake snake = newState.getSnakes(i);
            if (snake.getState() != SnakesProto.GameState.Snake.SnakeState.ALIVE) {
                newSnakes.add(snake.toBuilder());
                continue;
            }

            SnakesProto.GameState.Snake.Builder newSnake = snake.toBuilder();
            SnakesProto.Direction dir = snake.getHeadDirection();

            if (steers.containsKey(snake.getPlayerId())) {
                SnakesProto.Direction newDir = steers.get(snake.getPlayerId());
                if (!isReverse(dir, newDir)) {
                    dir = newDir;
                }
            }

            Point head = getHeadPosition(snake, width, height);

            Point newHead = new Point(head.x, head.y);
            switch (dir) {
                case UP -> newHead.y = (newHead.y - 1 + height) % height;
                case DOWN -> newHead.y = (newHead.y + 1) % height;
                case LEFT -> newHead.x = (newHead.x - 1 + width) % width;
                case RIGHT -> newHead.x = (newHead.x + 1) % width;
            }

            boolean ate = false;
            Iterator<Point> foodIter = foods.iterator();
            while (foodIter.hasNext()) {
                Point food = foodIter.next();
                if (food.equals(newHead)) {
                    foodIter.remove();
                    ate = true;
                    scores.put(snake.getPlayerId(), scores.getOrDefault(snake.getPlayerId(), 0) + 1);
                    break;
                }
            }

            List<SnakesProto.GameState.Coord> newPoints = new ArrayList<>();

            int deltaX = (newHead.x - head.x + width) % width;
            int deltaY = (newHead.y - head.y + height) % height;
            if (deltaX > width/2) deltaX -= width;
            if (deltaY > height/2) deltaY -= height;
            if (deltaX < -width/2) deltaX += width;
            if (deltaY < -height/2) deltaY += height;

            SnakesProto.GameState.Coord headDelta = SnakesProto.GameState.Coord.newBuilder()
                    .setX(deltaX)
                    .setY(deltaY)
                    .build();
            newPoints.add(headDelta);

            if (ate) {
                newPoints.addAll(snake.getPointsList().subList(1, snake.getPointsCount()));
            } else {
                if (snake.getPointsCount() > 1) {
                    newPoints.addAll(snake.getPointsList().subList(1, snake.getPointsCount()));
                }
            }

            newSnake.clearPoints().addAllPoints(newPoints);
            newSnake.setHeadDirection(dir);
            newSnakes.add(newSnake);
        }

        occupied.clear();
        Map<Point, List<Integer>> headPositions = new HashMap<>();
        //работаем со случаями когда бьются головами
        for (int i = 0; i < newSnakes.size(); i++) {
            SnakesProto.GameState.Snake snake = newSnakes.get(i).build();
            Point pos = new Point(0, 0);
            boolean isHead = true;
            for (SnakesProto.GameState.Coord p : snake.getPointsList()) {
                pos.x = (pos.x + p.getX() + width) % width;
                pos.y = (pos.y + p.getY() + height) % height;
                if (isHead) {
                    headPositions.computeIfAbsent(new Point(pos.x, pos.y), k -> new ArrayList<>()).add(i);
                    isHead = false;
                }
                occupied.add(new Point(pos.x, pos.y));
            }
        }

        for (Map.Entry<Point, List<Integer>> entry : headPositions.entrySet()) {
            List<Integer> snakesAtPoint = entry.getValue();
            if (snakesAtPoint.size() > 1) {
                deadSnakes.addAll(snakesAtPoint);
                for (int i : snakesAtPoint) {
                    for (int j : snakesAtPoint) {
                        if (i != j) {
                            int playerId = newSnakes.get(j).getPlayerId();
                            scores.put(playerId, scores.getOrDefault(playerId, 0) + 1);
                        }
                    }
                }
            }
        }
        //Мертвые змеи
        Random rand = new Random();
        Iterator<SnakesProto.GameState.Snake.Builder> snakeIter = newSnakes.iterator();
        int snakeIndex = 0;
        while (snakeIter.hasNext()) {
            SnakesProto.GameState.Snake.Builder snakeBuilder = snakeIter.next();
            if (deadSnakes.contains(snakeIndex)) {
                snakeBuilder.setState(SnakesProto.GameState.Snake.SnakeState.ZOMBIE);

                SnakesProto.GameState.Snake deadSnake = snakeBuilder.build();
                Point pos = new Point(0, 0);
                for (SnakesProto.GameState.Coord p : deadSnake.getPointsList()) {
                    pos.x = (pos.x + p.getX() + width) % width;
                    pos.y = (pos.y + p.getY() + height) % height;
                    if (rand.nextDouble() < 0.5 && !occupied.contains(new Point(pos.x, pos.y))) {
                        foods.add(new Point(pos.x, pos.y));
                    }
                }
            }
            snakeIndex++;
        }
        //Новая еда
        int neededFood = config.getFoodStatic() + aliveSnakes;
        while (foods.size() < neededFood && foods.size() < (width * height - occupied.size())) {
            Point newFood;
            int attempts = 0;
            do {
                newFood = new Point(rand.nextInt(width), rand.nextInt(height));
                attempts++;
                if (attempts > width * height) break;
            } while (occupied.contains(newFood));
            if (!occupied.contains(newFood)) {
                foods.add(newFood);
                occupied.add(newFood);
            }
        }
        //Преобразуем обратно в протобаф
        newState.clearFoods().addAllFoods(foods.stream()
                .map(p -> SnakesProto.GameState.Coord.newBuilder().setX(p.x).setY(p.y).build())
                .toList());

        newState.clearSnakes().addAllSnakes(newSnakes.stream()
                .map(SnakesProto.GameState.Snake.Builder::build)
                .toList());

        SnakesProto.GamePlayers.Builder newPlayers = SnakesProto.GamePlayers.newBuilder();
        for (SnakesProto.GamePlayer p : currentState.getPlayers().getPlayersList()) {
            SnakesProto.GamePlayer.Builder pb = p.toBuilder();
            pb.setScore(scores.getOrDefault(p.getId(), 0));
            newPlayers.addPlayers(pb);
        }
        newState.setPlayers(newPlayers);
        newState.setStateOrder(currentState.getStateOrder() + 1);

        currentState = newState.build();
        return currentState;
    }

    private boolean isReverse(SnakesProto.Direction d1, SnakesProto.Direction d2) {
        return (d1 == SnakesProto.Direction.UP && d2 == SnakesProto.Direction.DOWN) ||
                (d1 == SnakesProto.Direction.DOWN && d2 == SnakesProto.Direction.UP) ||
                (d1 == SnakesProto.Direction.LEFT && d2 == SnakesProto.Direction.RIGHT) ||
                (d1 == SnakesProto.Direction.RIGHT && d2 == SnakesProto.Direction.LEFT);
    }

    private Point getHeadPosition(SnakesProto.GameState.Snake snake, int width, int height) {
        Point head = new Point(0, 0);
        for (SnakesProto.GameState.Coord p : snake.getPointsList()) {
            head.x = (head.x + p.getX() + width) % width;
            head.y = (head.y + p.getY() + height) % height;
        }
        return head;
    }

    private Point findEmptyCell(Set<Point> occupied, int width, int height) {
        Random r = new Random();
        Point p;
        int attempts = 0;
        do {
            p = new Point(r.nextInt(width), r.nextInt(height));
            attempts++;
            if (attempts > width * height) {
                return null;
            }
        } while (occupied.contains(p));
        return p;
    }
}