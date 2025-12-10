package ru.nsu.fit.snakes.net;

import me.ippolitov.fit.snakes.SnakesProto;
import ru.nsu.fit.snakes.model.GameModel;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.Timer;
import java.util.function.Consumer;

public class GameNode {
    private DatagramSocket unicastSocket;
    private MulticastSocket multicastSocket;
    private InetAddress multicastGroup;
    private final int MULTICAST_PORT = 1080;
    private final String MULTICAST_ADDR = "239.192.0.4";
    private long msgSeq = 0;
    private final Map<Long, Timer> resendTimers = new HashMap<>();
    private final Map<InetSocketAddress, Long> lastReceived = new HashMap<>();
    private InetSocketAddress masterAddress;
    private Timer gameTimer;
    private final Map<Integer, SnakesProto.Direction> pendingSteers = new HashMap<>();
    private final Map<Integer, InetSocketAddress> playersAddresses = new HashMap<>();
    private boolean timersStarted = false;
    //Поиск
    public void startMulticastReceiver(Consumer<Map<String, SnakesProto.GameAnnouncement>> callback) {
        new Thread(() -> {
            try {
                multicastSocket = new MulticastSocket(MULTICAST_PORT);
                multicastGroup = InetAddress.getByName(MULTICAST_ADDR);
                multicastSocket.joinGroup(multicastGroup);

                byte[] buf = new byte[4096];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    multicastSocket.receive(packet);
                    SnakesProto.GameMessage msg = SnakesProto.GameMessage.parseFrom(Arrays.copyOf(buf, packet.getLength()));
                    if (msg.hasAnnouncement()) {
                        for (SnakesProto.GameAnnouncement ann : msg.getAnnouncement().getGamesList()) {
                            GameModel.getInstance().addAnnouncement(ann.getGameName(), ann, packet.getAddress(), packet.getPort());
                        }
                        callback.accept(GameModel.getInstance().getAnnouncements());
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
    //основной обменн
    public void startUnicastSocket() {
        try {
            unicastSocket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
        }

        new Thread(() -> {
            byte[] buf = new byte[4096];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    unicastSocket.receive(packet);
                    InetSocketAddress sender = (InetSocketAddress) packet.getSocketAddress();
                    lastReceived.put(sender, System.currentTimeMillis());

                    SnakesProto.GameMessage msg = SnakesProto.GameMessage.parseFrom(Arrays.copyOf(buf, packet.getLength()));
                    processMessage(msg, sender);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void startTimers() {
        if (timersStarted) return;

        SnakesProto.GameConfig config = GameModel.getInstance().getConfig();
        if (config == null) return;

        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkTimeouts();
                sendPingsIfNeeded();
            }
        }, 0, Math.max(100, config.getStateDelayMs() / 10));

        timersStarted = true;
    }

    private void processMessage(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        if (msg.hasAck()) {
            Timer timer = resendTimers.remove(msg.getMsgSeq());
            if (timer != null) timer.cancel();
            return;
        }

        if (!msg.hasAnnouncement() && !msg.hasDiscover() && !msg.hasAck()) {
            SnakesProto.GameMessage ack = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msg.getMsgSeq())
                    .setAck(SnakesProto.GameMessage.AckMsg.newBuilder())
                    .setSenderId(GameModel.getInstance().getPlayerId())
                    .setReceiverId(msg.getSenderId())
                    .build();
            send(ack, sender);
        }

        switch (msg.getTypeCase()) {
            case STATE -> {
                GameModel.getInstance().updateState(msg.getState().getState());
                if (!timersStarted) {
                    startTimers();
                }
            }
            case JOIN -> {
                if (GameModel.getInstance().getRole() == SnakesProto.NodeRole.MASTER) {
                    handleJoin(msg, sender);
                }
            }
            case STEER -> {
                if (GameModel.getInstance().getRole() == SnakesProto.NodeRole.MASTER) {
                    pendingSteers.put(msg.getSenderId(), msg.getSteer().getDirection());
                }
            }
            case ROLE_CHANGE -> handleRoleChange(msg, sender);
            case ERROR -> JOptionPane.showMessageDialog(null, msg.getError().getErrorMessage());
            case DISCOVER -> sendAnnouncement(sender);
        }
    }

    public void startNewGame(String gameName, String playerName, SnakesProto.GameConfig config) {
        GameModel.getInstance().setConfig(config);
        GameModel.getInstance().setPlayerName(playerName);
        GameModel.getInstance().setRole(SnakesProto.NodeRole.MASTER);
        GameModel.getInstance().setPlayerId(0);

        startTimers();

        SnakesProto.GameState initialState = initializeInitialState(gameName, playerName, config);
        GameModel.getInstance().updateState(initialState);

        gameTimer = new Timer();
        gameTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (GameModel.getInstance().getRole() == SnakesProto.NodeRole.MASTER) {
                    SnakesProto.GameState newState = GameModel.getInstance().advanceState(pendingSteers);
                    pendingSteers.clear();
                    if (newState != null) {
                        broadcastState(newState);
                    }
                }
            }
        }, config.getStateDelayMs(), config.getStateDelayMs());

        new Timer().scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (GameModel.getInstance().getRole() == SnakesProto.NodeRole.MASTER) {
                    sendMulticastAnnouncement(gameName);
                }
            }
        }, 0, 1000);
    }

    private SnakesProto.GameState initializeInitialState(String gameName, String playerName, SnakesProto.GameConfig config) {
        SnakesProto.GameState.Snake snake = createNewSnake(0, config);
        SnakesProto.GamePlayer player = SnakesProto.GamePlayer.newBuilder()
                .setName(playerName)
                .setId(0)
                .setRole(SnakesProto.NodeRole.MASTER)
                .setScore(0)
                .build();
        SnakesProto.GamePlayers players = SnakesProto.GamePlayers.newBuilder().addPlayers(player).build();

        List<SnakesProto.GameState.Coord> foods = generateFoods(config, new ArrayList<>(), config.getFoodStatic() + 1);

        return SnakesProto.GameState.newBuilder()
                .setStateOrder(0)
                .addSnakes(snake)
                .addAllFoods(foods)
                .setPlayers(players)
                .build();
    }

    private SnakesProto.GameState.Snake createNewSnake(int playerId, SnakesProto.GameConfig config) {
        Random r = new Random();
        int width = config.getWidth();
        int height = config.getHeight();
        int hx = r.nextInt(width);
        int hy = r.nextInt(height);

        SnakesProto.Direction[] directions = new SnakesProto.Direction[] {
                SnakesProto.Direction.UP, SnakesProto.Direction.DOWN,
                SnakesProto.Direction.LEFT, SnakesProto.Direction.RIGHT
        };
        SnakesProto.Direction tailDir = directions[r.nextInt(4)];
        SnakesProto.Direction headDir = reverseDirection(tailDir);

        SnakesProto.GameState.Coord head = SnakesProto.GameState.Coord.newBuilder().setX(hx).setY(hy).build();
        SnakesProto.GameState.Coord tailDelta = getDelta(tailDir);

        return SnakesProto.GameState.Snake.newBuilder()
                .setPlayerId(playerId)
                .addPoints(head)
                .addPoints(tailDelta)
                .setState(SnakesProto.GameState.Snake.SnakeState.ALIVE)
                .setHeadDirection(headDir)
                .build();
    }

    private SnakesProto.Direction reverseDirection(SnakesProto.Direction d) {
        return switch (d) {
            case UP -> SnakesProto.Direction.DOWN;
            case DOWN -> SnakesProto.Direction.UP;
            case LEFT -> SnakesProto.Direction.RIGHT;
            case RIGHT -> SnakesProto.Direction.LEFT;
            default -> d;
        };
    }

    private SnakesProto.GameState.Coord getDelta(SnakesProto.Direction d) {
        return switch (d) {
            case UP -> SnakesProto.GameState.Coord.newBuilder().setX(0).setY(-1).build();
            case DOWN -> SnakesProto.GameState.Coord.newBuilder().setX(0).setY(1).build();
            case LEFT -> SnakesProto.GameState.Coord.newBuilder().setX(-1).setY(0).build();
            case RIGHT -> SnakesProto.GameState.Coord.newBuilder().setX(1).setY(0).build();
            default -> SnakesProto.GameState.Coord.newBuilder().setX(0).setY(0).build();
        };
    }

    private List<SnakesProto.GameState.Coord> generateFoods(SnakesProto.GameConfig config, List<Point> occupied, int count) {
        List<SnakesProto.GameState.Coord> foods = new ArrayList<>();
        int width = config.getWidth();
        int height = config.getHeight();
        Set<Point> occ = new HashSet<>(occupied);
        Random r = new Random();

        for (int i = 0; i < count; i++) {
            Point p;
            int attempts = 0;
            do {
                p = new Point(r.nextInt(width), r.nextInt(height));
                attempts++;
                if (attempts > width * height) {
                    break;
                }
            } while (occ.contains(p));
            foods.add(SnakesProto.GameState.Coord.newBuilder().setX(p.x).setY(p.y).build());
            occ.add(p);
        }
        return foods;
    }

    public void joinGame(String gameName, String playerName, InetAddress masterAddr, int masterPort, boolean viewer) {
        GameModel.getInstance().setPlayerName(playerName);
        masterAddress = new InetSocketAddress(masterAddr, masterPort);

        SnakesProto.NodeRole requestedRole = viewer ? SnakesProto.NodeRole.VIEWER : SnakesProto.NodeRole.NORMAL;
        SnakesProto.GameMessage join = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq++)
                .setJoin(SnakesProto.GameMessage.JoinMsg.newBuilder()
                        .setPlayerName(playerName)
                        .setGameName(gameName)
                        .setRequestedRole(requestedRole))
                .build();
        sendWithResend(join, masterAddress);
    }

    private void handleJoin(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        if (GameModel.getInstance().getRole() != SnakesProto.NodeRole.MASTER) return;

        int newId = getNewPlayerId();
        SnakesProto.GameState.Snake newSnake = createNewSnake(newId, GameModel.getInstance().getConfig());

        SnakesProto.GamePlayer newPlayer = SnakesProto.GamePlayer.newBuilder()
                .setName(msg.getJoin().getPlayerName())
                .setId(newId)
                .setIpAddress(sender.getAddress().getHostAddress())
                .setPort(sender.getPort())
                .setRole(SnakesProto.NodeRole.NORMAL)
                .setScore(0)
                .build();

        playersAddresses.put(newId, sender);

        SnakesProto.GameState.Builder stateBuilder = GameModel.getInstance().getCurrentState().toBuilder();
        stateBuilder.addSnakes(newSnake);
        stateBuilder.getPlayersBuilder().addPlayers(newPlayer);
        GameModel.getInstance().updateState(stateBuilder.build());

        SnakesProto.GameMessage ack = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msg.getMsgSeq())
                .setAck(SnakesProto.GameMessage.AckMsg.newBuilder())
                .setReceiverId(newId)
                .build();
        send(ack, sender);
    }

    private int getNewPlayerId() {
        int maxId = GameModel.getInstance().getCurrentState().getPlayers().getPlayersList().stream()
                .mapToInt(SnakesProto.GamePlayer::getId)
                .max().orElse(-1);
        return maxId + 1;
    }

    private void broadcastState(SnakesProto.GameState state) {
        SnakesProto.GameMessage stateMsg = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq++)
                .setState(SnakesProto.GameMessage.StateMsg.newBuilder().setState(state))
                .build();
        for (InetSocketAddress addr : playersAddresses.values()) {
            sendWithResend(stateMsg, addr);
        }
    }

    private void sendMulticastAnnouncement(String gameName) {
        SnakesProto.GameState currentState = GameModel.getInstance().getCurrentState();
        if (currentState == null) return;

        SnakesProto.GameAnnouncement ann = SnakesProto.GameAnnouncement.newBuilder()
                .setPlayers(currentState.getPlayers())
                .setConfig(GameModel.getInstance().getConfig())
                .setCanJoin(true)
                .setGameName(gameName)
                .build();
        SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                .setAnnouncement(SnakesProto.GameMessage.AnnouncementMsg.newBuilder().addGames(ann))
                .setMsgSeq(msgSeq++)
                .build();
        byte[] bytes = msg.toByteArray();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, multicastGroup, MULTICAST_PORT);
        try {
            unicastSocket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendAnnouncement(InetSocketAddress recipient) {
    }

    private void send(SnakesProto.GameMessage msg, InetSocketAddress addr) {
        byte[] bytes = msg.toByteArray();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, addr);
        try {
            unicastSocket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendWithResend(SnakesProto.GameMessage msg, InetSocketAddress addr) {
        send(msg, addr);
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                send(msg, addr);
            }
        }, getResendDelay(), getResendDelay());
        resendTimers.put(msg.getMsgSeq(), timer);
    }

    private int getResendDelay() {
        SnakesProto.GameConfig config = GameModel.getInstance().getConfig();
        if (config != null) {
            return Math.max(100, config.getStateDelayMs() / 10);
        }
        return 100;
    }

    public void sendSteer(SnakesProto.Direction dir) {
        if (GameModel.getInstance().getRole() == SnakesProto.NodeRole.MASTER) {
            pendingSteers.put(GameModel.getInstance().getPlayerId(), dir);
        } else if (masterAddress != null) {
            SnakesProto.GameMessage steer = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeq++)
                    .setSteer(SnakesProto.GameMessage.SteerMsg.newBuilder().setDirection(dir))
                    .setSenderId(GameModel.getInstance().getPlayerId())
                    .build();
            sendWithResend(steer, masterAddress);
        }
    }

    private void checkTimeouts() {
        SnakesProto.GameConfig config = GameModel.getInstance().getConfig();
        if (config == null) return;

        long timeout = (long) (config.getStateDelayMs() * 0.8);
        lastReceived.entrySet().removeIf(e -> {
            if (System.currentTimeMillis() - e.getValue() > timeout) {
                handleDisconnect(e.getKey());
                return true;
            }
            return false;
        });
    }

    private void handleDisconnect(InetSocketAddress addr) {
        playersAddresses.entrySet().removeIf(entry -> entry.getValue().equals(addr));
    }

    private void sendPingsIfNeeded() {
        SnakesProto.GameConfig config = GameModel.getInstance().getConfig();
        if (config == null) return;

        SnakesProto.GameMessage ping = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq++)
                .setPing(SnakesProto.GameMessage.PingMsg.newBuilder())
                .build();
        for (InetSocketAddress addr : playersAddresses.values()) {
            sendWithResend(ping, addr);
        }
        if (masterAddress != null) {
            sendWithResend(ping, masterAddress);
        }
    }

    private void handleRoleChange(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        if (msg.getRoleChange().hasReceiverRole() && msg.getReceiverId() == GameModel.getInstance().getPlayerId()) {
            GameModel.getInstance().setRole(msg.getRoleChange().getReceiverRole());
            if (msg.getRoleChange().getReceiverRole() == SnakesProto.NodeRole.MASTER) {
                masterAddress = null;
            }
        }
    }
}