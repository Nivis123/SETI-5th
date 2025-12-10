import me.ippolitov.fit.snakes.SnakesProto;

import javax.swing.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;

public class GameController {
    private final SnakeGame mainFrame;
    private final Network networkManager;
    private final GameState stateManager;
    private final ScheduledExecutorService scheduler;

    private volatile boolean isPlaying = false;
    private volatile boolean isMaster = false;
    private String playerName;
    private int playerId = -1;
    private ScheduledFuture<?> gameLoopTask;
    private ScheduledFuture<?> announcementTask;

    public GameController(SnakeGame frame) {
        this.mainFrame = frame;
        this.stateManager = new GameState();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.networkManager = new Network(this, stateManager);
    }

    public void startNewGame(String playerName, SnakesProto.GameConfig config) {
        this.playerName = playerName;
        this.isMaster = true;

        System.out.println("Запуск новой игры: " + playerName);

        this.playerId = stateManager.initializeNewGame(config, playerName);
        networkManager.startAsHost(config, playerName);

        isPlaying = true;
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
        this.isMaster = false;

        System.out.println("Присоединение к игре: " + gameInfo.getGameName());

        try {
            networkManager.joinGame(gameInfo, playerName, viewerMode);
            isPlaying = true;
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

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        networkManager.stopListening();
        stateManager.clear();

        isMaster = false;
        isPlaying = false;
        playerId = -1;


        mainFrame.showMenu();
    }

    private void gameLoop() {
        if (!isPlaying || !isMaster) return;

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
        if (!isPlaying) return;

        System.out.println("Направление: " + direction + " (isMaster=" + isMaster + ", playerId=" + playerId + ")");

        if (isMaster) {
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
        System.out.println("Становлюсь мастером");
        this.isMaster = true;

        SnakesProto.GameConfig config = stateManager.getConfig();
        if (config != null && gameLoopTask == null) {
            long delay = config.getStateDelayMs();
            gameLoopTask = scheduler.scheduleAtFixedRate(
                    this::gameLoop,
                    delay,
                    delay,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    public GameState getStateManager() {
        return stateManager;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isMaster() {
        return isMaster;
    }

    public int getPlayerId() {
        return playerId;
    }

    public void shutdown() {
        System.out.println("Завершение работы");
        isPlaying = false;

        if (gameLoopTask != null) {
            gameLoopTask.cancel(false);
        }

        if (announcementTask != null) {
            announcementTask.cancel(false);
        }

        networkManager.shutdown();
        scheduler.shutdownNow();
    }
}