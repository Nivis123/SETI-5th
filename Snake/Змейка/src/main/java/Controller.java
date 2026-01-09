import me.ippolitov.fit.snakes.SnakesProto;
import javax.swing.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;

public class Controller {
    private final MainGame mainFrame;
    private final NET networkManager;
    private final StateManager stateManager;
    private final ScheduledExecutorService scheduler;

    private String playerName;
    private int playerId = -1;
    private ScheduledFuture<?> gameLoopTask;
    private ScheduledFuture<?> announcementTask;
    private volatile SnakesProto.NodeRole playerRole;

    public Controller(MainGame frame) {
        this.mainFrame = frame;
        this.stateManager = new StateManager();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.networkManager = new NET(this, stateManager);
    }

    public void startNewGame(String playerName, SnakesProto.GameConfig config) {
        this.playerName = playerName;
        this.playerRole = SnakesProto.NodeRole.MASTER;

        System.out.println("Запуск новой игры: " + playerName);
        this.playerId = stateManager.initializeNewGame(config, playerName);

        networkManager.startAsHost(config, playerName);
        mainFrame.showGame();

        long delay = config.getStateDelayMs();
        gameLoopTask = scheduler.scheduleAtFixedRate(
                this::gameLoop,
                delay,
                delay,
                TimeUnit.MILLISECONDS
        );

        announcementTask = scheduler.scheduleAtFixedRate(
                () -> networkManager.sendAnnouncements(),
                0,
                1000,
                TimeUnit.MILLISECONDS
        );

        System.out.println("Игровой цикл запущен с интервалом " + delay + "мс");
    }

    public void joinGame(GameInfo gameInfo, String playerName, boolean viewerMode) {
        this.playerName = playerName;
        this.playerRole = SnakesProto.NodeRole.NORMAL;

        System.out.println("Присоединение к игре: " + gameInfo.getGameName());

        try {
            networkManager.joinGame(gameInfo, playerName, viewerMode);
            mainFrame.showGame();
        } catch (IOException e) {
            System.err.println("Ошибка подключения: " + e.getMessage());
            JOptionPane.showMessageDialog(mainFrame,
                    "Ошибка подключения: " + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void exitGame() {
        System.out.println("Выход из игры");

        if (gameLoopTask != null) {
            gameLoopTask.cancel(false);
            gameLoopTask = null;
        }

        if (announcementTask != null) {
            announcementTask.cancel(false);
            announcementTask = null;
            System.out.println("Рассылка анонсов остановлена");
        }

        networkManager.leaveGame();
        networkManager.stopListening();
        stateManager.clear();

        playerRole = null;
        playerId = -1;
        mainFrame.showMenu();
    }

    private void gameLoop() {
        if (playerRole != SnakesProto.NodeRole.MASTER) return;

        try {
            stateManager.updateGameState();
            SnakesProto.GameState state = stateManager.getCurrentState();

            if (state != null) {
                networkManager.broadcastState(state);
                mainFrame.updateGamePanel();
            }
        } catch (Exception e) {
            System.err.println("Ошибка в игровом цикле: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void handleDirection(SnakesProto.Direction direction) {
        if (playerRole == SnakesProto.NodeRole.MASTER) {
            stateManager.setPlayerDirection(playerId, direction);
        } else {
            networkManager.sendSteer(direction);
        }
    }

    public void updateState(SnakesProto.GameState state) {
        stateManager.setCurrentState(state);
    }

    public void updateGamePanel() {
        mainFrame.updateGamePanel();
    }

    public void onGamesListUpdate(List<GameInfo> games) {
        mainFrame.updateGamesList(games);
    }

    public void onJoinAccepted(int playerId) {
        System.out.println("Присоединение принято, мой ID: " + playerId);
        this.playerId = playerId;
    }

    public void onError(String message) {
        System.err.println("Ошибка: " + message);
        JOptionPane.showMessageDialog(mainFrame, message,
                "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    public void becomeMaster() {
        System.err.println("Становлюсь мастером");
        playerRole = SnakesProto.NodeRole.MASTER;

        SnakesProto.GameConfig config = stateManager.getConfig();
        if (config != null) {
            if (gameLoopTask == null) {
                long delay = config.getStateDelayMs();
                gameLoopTask = scheduler.scheduleAtFixedRate(
                        this::gameLoop,
                        0,
                        delay,
                        TimeUnit.MILLISECONDS
                );
                System.out.println("Игровой цикл запущен с интервалом " + delay + "мс");
            }

            if (announcementTask == null) {
                announcementTask = scheduler.scheduleAtFixedRate(
                        () -> networkManager.sendAnnouncements(),
                        0,
                        1000,
                        TimeUnit.MILLISECONDS
                );
                System.err.println("Рассылка анонсов запущена");
            }
        }
    }

    public StateManager getStateManager() {
        return stateManager;
    }

    public SnakesProto.NodeRole getPlayerRole() {
        return playerRole;
    }

    public int getPlayerId() {
        return playerId;
    }

    public void shutdown() {
        System.out.println("Завершение работы");

        if (gameLoopTask != null) {
            gameLoopTask.cancel(false);
        }

        if (announcementTask != null) {
            announcementTask.cancel(false);
        }

        networkManager.shutdown();
        scheduler.shutdownNow();
    }

    public void setPlayerRole(SnakesProto.NodeRole role) {
        this.playerRole = role;
    }
}