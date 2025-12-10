import me.ippolitov.fit.snakes.SnakesProto;

import java.net.InetAddress;

public class GameInfo {
    private String gameName;
    private int playersCount;
    private boolean canJoin;
    private InetAddress address;
    private int port;
    private SnakesProto.GameConfig config;

    public GameInfo(String name, int players, boolean canJoin,
                    InetAddress addr, int port, SnakesProto.GameConfig cfg) {
        this.gameName = name;
        this.playersCount = players;
        this.canJoin = canJoin;
        this.address = addr;
        this.port = port;
        this.config = cfg;
    }

    public String getGameName() {
        return gameName;
    }

    public int getPlayersCount() {
        return playersCount;
    }

    public boolean canJoin() {
        return canJoin;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public SnakesProto.GameConfig getConfig() {
        return config;
    }

    @Override
    public String toString() {
        return String.format("%s (%d игроков) - %dx%d%s",
                gameName, playersCount,
                config.getWidth(), config.getHeight(),
                canJoin ? "" : " [ЗАПОЛНЕНА]");
    }
}