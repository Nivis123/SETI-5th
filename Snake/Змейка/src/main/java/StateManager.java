import me.ippolitov.fit.snakes.SnakesProto;

import java.awt.Point;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StateManager {
    private SnakesProto.GameState currentState;
    private SnakesProto.GameConfig config;
    private final Map<Integer, SnakesProto.Direction> pendingDirections = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private int stateOrder = 0;
    private final AtomicInteger counterPlayers = new AtomicInteger(0);

    public int initializeNewGame(SnakesProto.GameConfig cfg, String playerName) {
        this.config = cfg;
        this.stateOrder = 0;

        int playerId = counterPlayers.getAndIncrement();

        System.out.println("Инициализация новой игры: " + cfg.getWidth() + "x" + cfg.getHeight());

        SnakesProto.GameState.Builder stateBuilder = SnakesProto.GameState.newBuilder();
        stateBuilder.setStateOrder(stateOrder++);

        SnakesProto.GamePlayer player = SnakesProto.GamePlayer.newBuilder()
                .setName(playerName)
                .setId(playerId)
                .setRole(SnakesProto.NodeRole.MASTER)
                .setScore(0)
                .build();

        SnakesProto.GamePlayers players = SnakesProto.GamePlayers.newBuilder()
                .addPlayers(player)
                .build();

        stateBuilder.setPlayers(players);

        int centerX = cfg.getWidth() / 2;
        int centerY = cfg.getHeight() / 2;
        SnakesProto.GameState.Snake snake = createInitialSnake(playerId, centerX, centerY);
        stateBuilder.addSnakes(snake);

        Set<Point> occupied = getOccupiedCells(Collections.singletonList(snake));
        placeFood(stateBuilder, occupied);

        currentState = stateBuilder.build();

        System.out.println("Игра инициализирована. Змеек: " + currentState.getSnakesCount() + ", Еды: " + currentState.getFoodsCount());

        return playerId;
    }

    private SnakesProto.GameState.Snake createInitialSnake(int playerId, int x, int y) {
        return SnakesProto.GameState.Snake.newBuilder()
                .setPlayerId(playerId)
                .addPoints(SnakesProto.GameState.Coord.newBuilder().setX(x).setY(y).build())
                .addPoints(SnakesProto.GameState.Coord.newBuilder().setX(0).setY(1).build())
                .setState(SnakesProto.GameState.Snake.SnakeState.ALIVE)
                .setHeadDirection(SnakesProto.Direction.UP)
                .build();
    }

    public synchronized void updateGameState() {
        if (currentState == null) return;

        SnakesProto.GameState.Builder newStateBuilder = SnakesProto.GameState.newBuilder();
        newStateBuilder.setStateOrder(stateOrder++);
        newStateBuilder.setPlayers(currentState.getPlayers());

        Set<Point> foodSet = new HashSet<>();
        for (SnakesProto.GameState.Coord food : currentState.getFoodsList()) {
            foodSet.add(new Point(food.getX(), food.getY()));
        }

        Map<Integer, Integer> scores = new HashMap<>();
        for (SnakesProto.GamePlayer player : currentState.getPlayers().getPlayersList()) {
            scores.put(player.getId(), player.getScore());
        }

        Map<Integer, List<Point>> snakeCells = new HashMap<>();
        Map<Point, Integer> occupiedCell = new HashMap<>();

        List<SnakesProto.GameState.Snake> movedSnakes = new ArrayList<>();
        for (SnakesProto.GameState.Snake snake : currentState.getSnakesList()) {
            if (snake.getState() != SnakesProto.GameState.Snake.SnakeState.ALIVE) {
                movedSnakes.add(snake);
                continue;
            }

            int playerId = snake.getPlayerId();

            SnakesProto.Direction direction = pendingDirections.getOrDefault(
                    playerId, snake.getHeadDirection()
            );

            if (isOppositeDirection(direction, snake.getHeadDirection()) && getSnakeLength(snake) > 1) {
                direction = snake.getHeadDirection();
            }

            SnakesProto.GameState.Snake movedSnake = moveSnake(snake, direction, foodSet, scores);
            movedSnakes.add(movedSnake);

            List<Point> cells = getSnakeCells(movedSnake);
            snakeCells.put(playerId, cells);

            for (Point cell : cells) {
                occupiedCell.put(cell, occupiedCell.getOrDefault(cell, 0) + 1);
            }
        }

        pendingDirections.clear();

        Set<Integer> deadSnakes = new HashSet<>();
        for (SnakesProto.GameState.Snake snake : movedSnakes) {
            int playerId = snake.getPlayerId();
            List<Point> cells = snakeCells.get(playerId);

            if (cells.isEmpty()) {
                continue;
            }
            Point head = cells.getFirst();
            if (occupiedCell.getOrDefault(head, 0) > 1) {
                System.err.println("Змейка врезалась: " + snake.getPlayerId());
                deadSnakes.add(snake.getPlayerId());
            }
        }

        for (SnakesProto.GameState.Snake snake : movedSnakes) {
            if (deadSnakes.contains(snake.getPlayerId())) {
                List<Point> cells = snakeCells.get(snake.getPlayerId());

                for (Point cell : cells) {
                    if (random.nextDouble() < 0.5) {
                        foodSet.add(cell);
                    }
                }
                System.out.println("Змейка игрока " + snake.getPlayerId() + " погибла и исчезла");
            } else {
                newStateBuilder.addSnakes(snake);
            }
        }

        SnakesProto.GamePlayers.Builder playersBuilder = SnakesProto.GamePlayers.newBuilder();
        for (SnakesProto.GamePlayer player : currentState.getPlayers().getPlayersList()) {
            playersBuilder.addPlayers(
                    player.toBuilder()
                            .setScore(scores.getOrDefault(player.getId(), player.getScore()))
                            .build()
            );
        }
        newStateBuilder.setPlayers(playersBuilder.build());

        for (Point food : foodSet) {
            newStateBuilder.addFoods(
                    SnakesProto.GameState.Coord.newBuilder()
                            .setX(food.x)
                            .setY(food.y)
                            .build()
            );
        }

        Set<Point> occupied = new HashSet<>();
        for (List<Point> cells : snakeCells.values()) {
            occupied.addAll(cells);
        }

        placeFood(newStateBuilder, occupied);

        currentState = newStateBuilder.build();
    }

    private boolean isOppositeDirection(SnakesProto.Direction d1, SnakesProto.Direction d2) {
        return (d1 == SnakesProto.Direction.UP && d2 == SnakesProto.Direction.DOWN) ||
                (d1 == SnakesProto.Direction.DOWN && d2 == SnakesProto.Direction.UP) ||
                (d1 == SnakesProto.Direction.LEFT && d2 == SnakesProto.Direction.RIGHT) ||
                (d1 == SnakesProto.Direction.RIGHT && d2 == SnakesProto.Direction.LEFT);
    }

    private int getSnakeLength(SnakesProto.GameState.Snake snake) {
        int length = 0;
        for (SnakesProto.GameState.Coord coord : snake.getPointsList()) {
            length += Math.max(Math.abs(coord.getX()), Math.abs(coord.getY()));
        }
        return Math.max(1, length);
    }

    private SnakesProto.GameState.Snake moveSnake(SnakesProto.GameState.Snake snake, SnakesProto.Direction direction,
                                                  Set<Point> foodSet,
                                                  Map<Integer, Integer> scores) {
        List<Point> snakeCells = getSnakeCells(snake);
        if (snakeCells.isEmpty()) return snake;

        Point head = snakeCells.getFirst();

        Point newHead = getNextPosition(head, direction);
        boolean ateFood = foodSet.remove(newHead);

        if (ateFood) {
            scores.put(snake.getPlayerId(), scores.getOrDefault(snake.getPlayerId(), 0) + 1);
            snakeCells.addFirst(newHead);
        } else {
            snakeCells.addFirst(newHead);
            if (snakeCells.size() > 1) {
                snakeCells.removeLast();
            }
        }

        return buildSnakeFromCells(snake.getPlayerId(), snakeCells, direction);
    }

    private Point getNextPosition(Point current, SnakesProto.Direction direction) {
        int x = current.x;
        int y = current.y;

        switch (direction) {
            case UP:
                y = (y - 1 + config.getHeight()) % config.getHeight();
                break;
            case DOWN:
                y = (y + 1) % config.getHeight();
                break;
            case LEFT:
                x = (x - 1 + config.getWidth()) % config.getWidth();
                break;
            case RIGHT:
                x = (x + 1) % config.getWidth();
                break;
        }

        return new Point(x, y);
    }

    private SnakesProto.GameState.Snake buildSnakeFromCells(int playerId, List<Point> cells, SnakesProto.Direction headDirection) {
        SnakesProto.GameState.Snake.Builder builder = SnakesProto.GameState.Snake.newBuilder();
        builder.setPlayerId(playerId);
        builder.setState(SnakesProto.GameState.Snake.SnakeState.ALIVE);
        builder.setHeadDirection(headDirection);

        if (cells.isEmpty()) return builder.build();

        builder.addPoints(SnakesProto.GameState.Coord.newBuilder()
                .setX(cells.getFirst().x)
                .setY(cells.getFirst().y)
                .build());

        Point prev = cells.getFirst();
        Point current = prev;
        SnakesProto.Direction currentDir = null;
        int count = 0;

        for (int i = 1; i < cells.size(); i++) {
            current = cells.get(i);

            int dx = (current.x - prev.x + config.getWidth()) % config.getWidth();
            if (dx > config.getWidth() / 2) dx -= config.getWidth();

            int dy = (current.y - prev.y + config.getHeight()) % config.getHeight();
            if (dy > config.getHeight() / 2) dy -= config.getHeight();

            SnakesProto.Direction dir = getDirection(dx, dy);

            if (dir == currentDir || currentDir == null) {
                count++;
                currentDir = dir;
            } else {
                addSegment(builder, currentDir, count);
                currentDir = dir;
                count = 1;
            }

            prev = current;
        }

        if (count > 0 && currentDir != null) {
            addSegment(builder, currentDir, count);
        }

        return builder.build();
    }

    private SnakesProto.Direction getDirection(int dx, int dy) {
        if (dx > 0) return SnakesProto.Direction.RIGHT;
        if (dx < 0) return SnakesProto.Direction.LEFT;
        if (dy > 0) return SnakesProto.Direction.DOWN;
        return SnakesProto.Direction.UP;
    }

    private void addSegment(SnakesProto.GameState.Snake.Builder builder, SnakesProto.Direction dir, int length) {
        int dx = 0, dy = 0;

        switch (dir) {
            case RIGHT: dx = length; break;
            case LEFT: dx = -length; break;
            case DOWN: dy = length; break;
            case UP: dy = -length; break;
        }

        builder.addPoints(SnakesProto.GameState.Coord.newBuilder()
                .setX(dx)
                .setY(dy)
                .build());
    }

    private List<Point> getSnakeCells(SnakesProto.GameState.Snake snake) {
        List<Point> cells = new LinkedList<>();
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

    private Set<Point> getOccupiedCells(List<SnakesProto.GameState.Snake> snakes) {
        Set<Point> occupied = new HashSet<>();
        for (SnakesProto.GameState.Snake snake : snakes) {
            List<Point> cells = getSnakeCells(snake);
            occupied.addAll(cells);
        }
        return occupied;
    }

    private void placeFood(SnakesProto.GameState.Builder stateBuilder, Set<Point> occupied) {
        int aliveSnakes = (int) stateBuilder.getSnakesList().stream()
                .filter(s -> s.getState() == SnakesProto.GameState.Snake.SnakeState.ALIVE)
                .count();

        int requiredFood = config.getFoodStatic() + aliveSnakes;
        int currentFood = stateBuilder.getFoodsCount();
        int toAdd = requiredFood - currentFood;

        Set<Point> occupiedWithFood = new HashSet<>(occupied);
        for (SnakesProto.GameState.Coord food : stateBuilder.getFoodsList()) {
            occupiedWithFood.add(new Point(food.getX(), food.getY()));
        }

        for (int i = 0; i < toAdd; i++) {
            Point food = findEmptyCell(occupiedWithFood);
            if (food != null) {
                stateBuilder.addFoods(SnakesProto.GameState.Coord.newBuilder()
                        .setX(food.x)
                        .setY(food.y)
                        .build());
                occupiedWithFood.add(food);
            }
        }
    }


    public synchronized int addNewPlayer(String playerName, InetSocketAddress sender, SnakesProto.NodeRole requestedRole) {
        int playerId = counterPlayers.getAndIncrement();

        SnakesProto.GamePlayer newPlayer = SnakesProto.GamePlayer.newBuilder()
                .setName(playerName)
                .setId(playerId)
                .setIpAddress(sender.getAddress().getHostAddress())
                .setPort(sender.getPort())
                .setRole(requestedRole)
                .setScore(0)
                .build();

        SnakesProto.GamePlayers.Builder playersBuilder = SnakesProto.GamePlayers.newBuilder();
        for (SnakesProto.GamePlayer player : currentState.getPlayers().getPlayersList()) {
            playersBuilder.addPlayers(player);
        }
        playersBuilder.addPlayers(newPlayer);

        SnakesProto.GameState.Builder stateBuilder = currentState.toBuilder();
        stateBuilder.setPlayers(playersBuilder.build());

        if (requestedRole != SnakesProto.NodeRole.VIEWER) {
            SnakesProto.GameState.Snake newSnake = createNewSnake(playerId);

            if (newSnake == null) {
                System.err.println("Не удалось создать змейку - нет места на поле");
                return -1;
            }

            stateBuilder.addSnakes(newSnake);
            System.out.println("Змейка создана для игрока " + playerId);
        }


        stateBuilder.setStateOrder(stateOrder++);

        SnakesProto.GameState newState = stateBuilder.build();
        currentState = newState;

        System.out.println("Новое состояние: змеек=" + newState.getSnakesCount() + ", игроков=" + newState.getPlayers().getPlayersCount());


        System.out.println("Игрок " + playerName + " присоединился с ID " + playerId);

        return playerId;
    }

    private SnakesProto.GameState.Snake createNewSnake(int playerId) {
        Random random = new Random();

        Set<Point> occupied = new HashSet<>();
        for (SnakesProto.GameState.Snake snake : currentState.getSnakesList()) {
            occupied.addAll(getSnakeCells(snake));
        }

        int maxAttempts = config.getWidth() * config.getHeight();
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int x = random.nextInt(config.getWidth());
            int y = random.nextInt(config.getHeight());
            boolean canPlace = true;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int checkX = (x + dx + config.getWidth()) % config.getWidth();
                    int checkY = (y + dy + config.getHeight()) % config.getHeight();
                    if (occupied.contains(new Point(checkX, checkY))) {
                        canPlace = false;
                        break;
                    }
                }
                if (!canPlace) break;
            }

            if (canPlace) {
                int[][] offsets = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
                SnakesProto.Direction[] directions = {
                        SnakesProto.Direction.UP,
                        SnakesProto.Direction.DOWN,
                        SnakesProto.Direction.LEFT,
                        SnakesProto.Direction.RIGHT
                };

                int tailOffset = random.nextInt(4);
                int tailX = (x + offsets[tailOffset][0] + config.getWidth()) % config.getWidth();
                int tailY = (y + offsets[tailOffset][1] + config.getHeight()) % config.getHeight();

                Point headPos = new Point(x, y);
                Point tailPos = new Point(tailX, tailY);

                boolean hasFood = false;
                for (SnakesProto.GameState.Coord food : currentState.getFoodsList()) {
                    Point foodPos = new Point(food.getX(), food.getY());
                    if (foodPos.equals(headPos) || foodPos.equals(tailPos)) {
                        hasFood = true;
                        break;
                    }
                }

                if (hasFood) {
                    continue;
                }

                SnakesProto.Direction direction = directions[tailOffset];

                int dx = tailX - x;
                int dy = tailY - y;

                if (Math.abs(dx) > config.getWidth() / 2) {
                    dx = dx > 0 ? dx - config.getWidth() : dx + config.getWidth();
                }
                if (Math.abs(dy) > config.getHeight() / 2) {
                    dy = dy > 0 ? dy - config.getHeight() : dy + config.getHeight();
                }

                return SnakesProto.GameState.Snake.newBuilder()
                        .setPlayerId(playerId)
                        .addPoints(SnakesProto.GameState.Coord.newBuilder().setX(x).setY(y).build())
                        .addPoints(SnakesProto.GameState.Coord.newBuilder().setX(dx).setY(dy).build())
                        .setState(SnakesProto.GameState.Snake.SnakeState.ALIVE)
                        .setHeadDirection(direction)
                        .build();
            }
        }

        return null;
    }

    public synchronized void deletePlayer(int playerId) {
        SnakesProto.GameState.Builder stateBuilder = currentState.toBuilder();

        SnakesProto.GamePlayers.Builder playersBuilder = SnakesProto.GamePlayers.newBuilder();
        for (SnakesProto.GamePlayer player : currentState.getPlayers().getPlayersList()) {
            if (player.getId() == playerId) {
                System.err.println("Место где удалили игрока: " + playerId);
                continue;
            }

            playersBuilder.addPlayers(player);
        }

        stateBuilder.setPlayers(playersBuilder.build());

        stateBuilder.setStateOrder(stateOrder++);

        SnakesProto.GameState newState = stateBuilder.build();
        currentState = newState;

        System.out.println("Новое состояние: змеек=" + newState.getSnakesCount() + ", игроков=" + newState.getPlayers().getPlayersCount());
        System.out.println("удален игрок: " + playerId);



    }

    private Point findEmptyCell(Set<Point> occupied) {
        int totalCells = config.getWidth() * config.getHeight();
        if (occupied.size() >= totalCells) return null;

        for (int attempt = 0; attempt < 100; attempt++) {
            int x = random.nextInt(config.getWidth());
            int y = random.nextInt(config.getHeight());
            Point p = new Point(x, y);

            if (!occupied.contains(p)) {
                return p;
            }
        }

        return null;
    }

    public void setPlayerDirection(int playerId, SnakesProto.Direction direction) {
        pendingDirections.put(playerId, direction);
    }

    public synchronized void setCurrentState(SnakesProto.GameState state) {
        if (state == null) {
            System.err.println("Получено null state");
            return;
        }

        if (state.getStateOrder() > stateOrder) {
            this.currentState = state;
            this.stateOrder = state.getStateOrder();

//            System.out.println("Обновлено состояние: ход " + stateOrder + ", змеек: " + state.getSnakesCount() + ", игроков: " + state.getPlayers().getPlayersCount());
        }
    }


    public void setConfig(SnakesProto.GameConfig cfg) {
        this.config = cfg;
        System.out.println("Config установлен: " + cfg.getWidth() + "x" + cfg.getHeight());
    }

    public synchronized SnakesProto.GameState getCurrentState() {
        return currentState;
    }

    public SnakesProto.GameConfig getConfig() {
        return config;
    }

    public synchronized void clear() {
        currentState = null;
        pendingDirections.clear();
        stateOrder = 0;
        counterPlayers.set(0);
    }



    public synchronized void updatePlayerRole(int playerId, SnakesProto.NodeRole role) {
        if (currentState == null) {
            System.err.println("Ошибкав updatePlayerRole: currentState = null");
            return;
        }

//        System.err.println("Обновляем playerRole внутри GameState");

        SnakesProto.GameState.Builder stateBuilder = currentState.toBuilder();
        SnakesProto.GamePlayers.Builder playersBuilder = SnakesProto.GamePlayers.newBuilder();
        boolean found = false;

        for (SnakesProto.GamePlayer player : currentState.getPlayers().getPlayersList()) {
            if (player.getId() == playerId) {
                found = true;
                playersBuilder.addPlayers(
                        player.toBuilder().setRole(role).build()
                );
                System.err.println("Обновили роль для " + role + " для игрока " + playerId);
            } else {
                playersBuilder.addPlayers(player);
            }
        }

        if (!found) {
            System.err.println("Ошибка игрок + " + playerId + " не найден");
            return;
        }

        stateBuilder.setPlayers(playersBuilder.build());

        currentState = stateBuilder.build();

        System.err.println("Роль обновлена в GameState good " + role);
    }

    public synchronized void restorePlayerCounter() {
        if (currentState == null) {
            return;
        }

        int maxId = -1;
        for (SnakesProto.GamePlayer player : currentState.getPlayers().getPlayersList()) {
            if (player.getId() > maxId) {
                maxId = player.getId();
            }
        }

        int nextId = maxId + 1;
        counterPlayers.set(nextId);
        System.err.println("Восстановлен счётчик игроков: следующий ID будет " + nextId);
    }

}