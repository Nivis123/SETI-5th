import me.ippolitov.fit.snakes.SnakesProto;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class Network {
    private static final String MULTICAST_ADDRESS = "239.192.0.4";
    private static final int MULTICAST_PORT = 8080;

    private final GameController controller;
    private final GameState stateManager;

    private DatagramSocket unicastSocket;
    private MulticastSocket multicastSocket;
    private InetAddress multicastGroup;
    private NetworkInterface networkInterface;

    private final Map<InetSocketAddress, GameInfo> availableGames = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Long> lastSeenGames = new ConcurrentHashMap<>();
    private final Map<Integer, PlayerAddress> playerAddresses = new ConcurrentHashMap<>();
    private final Map<Integer, Long> alivePlayers = new ConcurrentHashMap<>();

    private InetSocketAddress masterAddress;
    private final AtomicLong msgSeq = new AtomicLong(0);
    private final Map<Long, PendingMessage> pendingAcks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService retransmitExecutor = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService timeOutExecutor = Executors.newScheduledThreadPool(1);
    private final ExecutorService listenerExecutor = Executors.newFixedThreadPool(2);
    private ScheduledFuture<?> timeoutTask;

    private volatile boolean running = true;
    private volatile boolean listeningUnicast = false;
    private String gameName;
    private SnakesProto.GameConfig gameConfig;

    public Network(GameController ctrl, GameState stateMgr) {
        this.controller = ctrl;
        this.stateManager = stateMgr;

        try {
            unicastSocket = new DatagramSocket();
            unicastSocket.setBroadcast(true);

            multicastSocket = new MulticastSocket(MULTICAST_PORT);
            multicastSocket.setReuseAddress(true);
//            multicastSocket.setLoopbackMode(false);
            multicastGroup = InetAddress.getByName(MULTICAST_ADDRESS);

            boolean joined = false;

            try {
                networkInterface = findSuitableNetworkInterface();
                if (networkInterface != null) {
                    multicastSocket.setNetworkInterface(networkInterface);
                    multicastSocket.joinGroup(new InetSocketAddress(multicastGroup, MULTICAST_PORT), networkInterface);
                    System.out.println("Присоединились к multicast группе через " + networkInterface.getName());
                    joined = true;
                }
            } catch (IOException e) {
                System.out.println("Не удалось подключиться к multicast: " + e.getMessage());
            }


            if (!joined) {
                System.err.println("Не удалось присоединиться к multicast группе.");
                System.err.println("Обнаружение игр не будет работать");
            }

            listenerExecutor.submit(this::listenMulticast);

        } catch (IOException e) {
            System.err.println("Ошибка инициализации сети: " + e.getMessage());
            System.err.println("Программа будет работать в ограниченном режиме.");
            e.printStackTrace();
        }
    }

    private NetworkInterface findSuitableNetworkInterface() throws SocketException {
        System.out.println("Поиск подходящего сетевого интерфейса");

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            String name = ni.getName().toLowerCase();
            String displayName = ni.getDisplayName().toLowerCase();

            if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) {
                System.out.println("Пропуск: " + ni.getName() + " - не подходит");
                continue;
            }

            if (displayName.contains("virtualbox") ||
                    displayName.contains("vmware") ||
                    displayName.contains("hyper-v") ||
                    displayName.contains("virtual") ||
                    displayName.contains("vbox") ||
                    displayName.contains("wfp")) {
                System.out.println("Пропуск виртуального адаптера: " + ni.getName() + " - " + displayName);
                continue;
            }

            if (name.contains("teredo") || name.contains("isatap") ||
                    name.contains("awdl") || name.contains("utun")) {
                System.out.println("Пропуск туннельного интерфейса: " + ni.getName());
                continue;
            }

            System.out.println("Найден подходящий интерфейс: " + ni.getName() + " (" + displayName + ")");

            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                    System.out.println("  IPv4 адрес: " + addr.getHostAddress());
                    return ni;
                }
            }
        }

        System.out.println("Подходящий интерфейс не найден, пробуем использовать любой доступный...");

        interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (ni.isUp() && !ni.isLoopback() && ni.supportsMulticast()) {
                System.out.println("Используем fallback: " + ni.getName());
                return ni;
            }
        }

        System.err.println("Подходящий интерфейс не найден");
        return null;
    }

    public void listenMulticast() {
        byte[] buffer = new byte[65536];
        System.out.println("Начало прослушивания multicast на порту " + MULTICAST_PORT);

        if (multicastSocket == null || multicastSocket.isClosed()) {
            System.err.println("MulticastSocket не инициализирован, прослушивание невозможно");
            return;
        }

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.receive(packet);

                SnakesProto.GameMessage msg = SnakesProto.GameMessage.parseFrom(
                        Arrays.copyOf(packet.getData(), packet.getLength())
                );

                if (msg.hasAnnouncement()) {
                    handleAnnouncement(msg.getAnnouncement(), (InetSocketAddress) packet.getSocketAddress());
                }
            } catch (SocketException e) {
                if (running) {
                    System.err.println("MulticastSocket закрыт");
                }
                break;
            } catch (Exception e) {
                if (running) {
                    System.err.println("Ошибка приема multicast: " + e.getMessage());
                }
            }
        }

        System.out.println("Прослушивание multicast завершено");
    }

    public void startAsHost(SnakesProto.GameConfig config, String playerName) {
        this.gameConfig = config;
        this.gameName = "Игра " + playerName;

        System.out.println("Запуск как хост на порту " + unicastSocket.getLocalPort());

        listenerExecutor.submit(this::listenUnicast);
        timeoutTask = timeOutExecutor.scheduleAtFixedRate(this::checkTimeouts,
                1000,
                5000,
                TimeUnit.MILLISECONDS);
    }

    private void listenUnicast() {
        byte[] buffer = new byte[65536];
        System.out.println("Начало прослушивания unicast на порту " + unicastSocket.getLocalPort());
        listeningUnicast = true;

        while (running && listeningUnicast) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                unicastSocket.receive(packet);

                SnakesProto.GameMessage msg = SnakesProto.GameMessage.parseFrom(
                        Arrays.copyOf(packet.getData(), packet.getLength())
                );

                handleUnicastMessage(msg, (InetSocketAddress) packet.getSocketAddress());
            } catch (SocketException e) {
                if (running && listeningUnicast) {
                    System.err.println("Unicast socket закрыт");
                }
                break;
            } catch (Exception e) {
                if (running && listeningUnicast) {
                    System.err.println("Ошибка приема unicast: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        listeningUnicast = false;
        System.out.println("Прослушивание unicast завершено");
    }

    public void stopListening() {
        System.out.println("Остановка прослушивания unicast");
        listeningUnicast = false;
        masterAddress = null;

        if (unicastSocket != null && !unicastSocket.isClosed()) {
            unicastSocket.close();
            System.out.println("Unicast socket закрыт для остановки прослушивания");

            try {
                unicastSocket = new DatagramSocket();
                unicastSocket.setBroadcast(true);
                System.out.println("Создан новый unicast socket на порту " + unicastSocket.getLocalPort());
            } catch (SocketException e) {
                System.err.println("Ошибка создания нового socket: " + e.getMessage());
            }
        }

        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }

        playerAddresses.clear();
        alivePlayers.clear();
    }

    private void handleUnicastMessage(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        if (!msg.hasAck() || (msg.hasAck() && msg.hasReceiverId())) {
            System.out.println("Получено сообщение от " + sender + ": " + msg.getTypeCase());
        }

        int senderId = msg.getSenderId();

        if (senderId > 0) {
            alivePlayers.put(senderId, System.currentTimeMillis());
        }

        if (msg.hasAck()) {
            pendingAcks.remove(msg.getMsgSeq());

            if (msg.hasReceiverId() && controller.getPlayerId() == -1) {
                int playerId = msg.getReceiverId();
                controller.onJoinAccepted(playerId);
                System.out.println("Получен ID игрока: " + playerId);
            }
        }

        if (msg.hasSteer()) {
            int playerId = msg.getSenderId();
            SnakesProto.Direction direction = msg.getSteer().getDirection();
            stateManager.setPlayerDirection(playerId, direction);
            sendAck(msg.getMsgSeq(), playerId, sender);
        }

        if (msg.hasState()) {
            controller.updateState(msg.getState().getState());
            controller.updateGamePanel();
            sendAck(msg.getMsgSeq(), msg.getSenderId(), sender);
        }

        if (msg.hasJoin()) {
            handleJoinRequest(msg, sender);
        }

        if (msg.hasRoleChange()) {
            handleRoleChange(msg.getRoleChange(), msg.getSenderId());
            sendAck(msg.getMsgSeq(), msg.getSenderId(), sender);

            broadcastState(stateManager.getCurrentState());
            controller.updateGamePanel();
        }

        if (msg.hasError()) {
            controller.onError(msg.getError().getErrorMessage());
        }
    }

    private void handleAnnouncement(SnakesProto.GameMessage.AnnouncementMsg announcement, InetSocketAddress sender) {
        for (SnakesProto.GameAnnouncement game : announcement.getGamesList()) {
            GameInfo info = new GameInfo(
                    game.getGameName(),
                    game.getPlayers().getPlayersCount(),
                    game.getCanJoin(),
                    sender.getAddress(),
                    sender.getPort(),
                    game.getConfig()
            );

            availableGames.put(sender, info);
            lastSeenGames.put(sender, System.currentTimeMillis());
        }

        cleanupOldGames();
        controller.onGamesListUpdate(new ArrayList<>(availableGames.values()));
    }

    private void cleanupOldGames() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<InetSocketAddress, Long>> it = lastSeenGames.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<InetSocketAddress, Long> entry = it.next();
            if (now - entry.getValue() > 3000) {
                availableGames.remove(entry.getKey());
                it.remove();
            }
        }
    }

    public void sendAnnouncements() {
        if (!controller.isMaster()) return;

        SnakesProto.GameState state = stateManager.getCurrentState();
        if (state == null) return;

        SnakesProto.GameAnnouncement announcement = SnakesProto.GameAnnouncement.newBuilder()
                .setPlayers(state.getPlayers())
                .setConfig(gameConfig)
                .setCanJoin(true)
                .setGameName(gameName)
                .build();

        SnakesProto.GameMessage.AnnouncementMsg announcementMsg = SnakesProto.GameMessage.AnnouncementMsg.newBuilder()
                .addGames(announcement)
                .build();

        SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.getAndIncrement())
                .setAnnouncement(announcementMsg)
                .build();

        try {
            byte[] data = msg.toByteArray();
            DatagramPacket packet = new DatagramPacket(data, data.length, multicastGroup, MULTICAST_PORT);

            unicastSocket.send(packet);
        } catch (IOException e) {
            System.err.println("Ошибка отправки анонса: " + e.getMessage());
        }
    }

    public void joinGame(GameInfo game, String playerName, boolean viewerMode) throws IOException {
        masterAddress = new InetSocketAddress(game.getAddress(), game.getPort());
        this.gameConfig = game.getConfig();

        stateManager.setConfig(game.getConfig());

        System.out.println("Отправка JoinMsg на " + masterAddress);

        SnakesProto.GameMessage.JoinMsg joinMsg = SnakesProto.GameMessage.JoinMsg.newBuilder()
                .setPlayerName(playerName)
                .setGameName(game.getGameName())
                .setRequestedRole(viewerMode ? SnakesProto.NodeRole.VIEWER : SnakesProto.NodeRole.NORMAL)
                .build();

        SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.getAndIncrement())
                .setJoin(joinMsg)
                .build();

        sendReliable(msg, masterAddress);

        listenerExecutor.submit(this::listenUnicast);
    }

    private void handleJoinRequest(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        System.out.println("Обработка запроса на присоединение от " + sender);

        SnakesProto.GameState currentState = stateManager.getCurrentState();
        if (currentState == null) {
            System.err.println("Состояние игры не инициализировано - игра завершена");
            sendError("Игра уже завершена. Пожалуйста, выберите другую игру.", sender);
            return;
        }

        String playerName = msg.getJoin().getPlayerName();
        SnakesProto.NodeRole requestedRole = msg.getJoin().getRequestedRole();

        int newPlayerId = stateManager.addNewPlayer(playerName, sender, requestedRole);

        if (newPlayerId == -1) {
            sendError("Нет места на игровом поле", sender);
            return;
        }
        System.out.println("Присоединяется НОВЫЙ игрок: " + playerName + ", роль: " + requestedRole + ", ID: " + newPlayerId);

        playerAddresses.put(newPlayerId, new Network.PlayerAddress(sender.getAddress(), sender.getPort()));

        alivePlayers.put(newPlayerId, System.currentTimeMillis());

        sendAck(msg.getMsgSeq(), newPlayerId, sender);
        broadcastState(stateManager.getCurrentState());

        controller.updateGamePanel();
        System.out.println("Игрок " + playerName + " присоединился с ID " + newPlayerId);
    }

    private void sendError(String errorMessage, InetSocketAddress addr) {
        SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.getAndIncrement())
                .setError(SnakesProto.GameMessage.ErrorMsg.newBuilder()
                        .setErrorMessage(errorMessage)
                        .build())
                .build();

        try {
            byte[] data = msg.toByteArray();
            DatagramPacket packet = new DatagramPacket(data, data.length, addr);
            unicastSocket.send(packet);
        } catch (IOException e) {
            System.err.println("Ошибка отправки ERROR: " + e.getMessage());
        }
    }

    public void sendSteer(SnakesProto.Direction direction) {
        if (masterAddress == null) {
            System.err.println("Ошибка отправки Steer: нет адреса мастера");
            return;
        }

        int playerId = controller.getPlayerId();
        if (playerId == -1) {
            System.err.println("Ошибка отправки Steer: ID игрока не установлен");
            return;
        }

        System.out.println("Отправка SteerMsg: direction=" + direction + ", playerId=" + playerId);

        SnakesProto.GameMessage.SteerMsg steerMsg = SnakesProto.GameMessage.SteerMsg.newBuilder()
                .setDirection(direction)
                .build();

        SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.getAndIncrement())
                .setSenderId(playerId)
                .setSteer(steerMsg)
                .build();

        sendReliable(msg, masterAddress);
    }

    public void broadcastState(SnakesProto.GameState state) {
        SnakesProto.GameMessage.StateMsg stateMsg = SnakesProto.GameMessage.StateMsg.newBuilder()
                .setState(state)
                .build();

        SnakesProto.GameMessage.Builder msgBuilder = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.getAndIncrement())
                .setSenderId(controller.getPlayerId())
                .setState(stateMsg);

        SnakesProto.GameMessage msg = msgBuilder.build();

        for (Map.Entry<Integer, PlayerAddress> entry : playerAddresses.entrySet()) {
            try {
                InetSocketAddress addr = new InetSocketAddress(
                        entry.getValue().address,
                        entry.getValue().port
                );


                sendReliable(msg, addr);
            } catch (Exception e) {
                System.err.println("Ошибка отправки состояния игроку " + entry.getKey());
            }
        }
    }

    private void sendReliable(SnakesProto.GameMessage msg, InetSocketAddress addr) {
        try {
            byte[] data = msg.toByteArray();
            DatagramPacket packet = new DatagramPacket(data, data.length, addr);
            unicastSocket.send(packet);

            if (!msg.hasAck() && !msg.hasAnnouncement()) {
                PendingMessage pending = new PendingMessage(msg, addr);
                pendingAcks.put(msg.getMsgSeq(), pending);

                scheduleRetransmit(msg.getMsgSeq(), 100);
            }
        } catch (IOException e) {
            System.err.println("Ошибка отправки сообщения: " + e.getMessage());
        }
    }

    private void scheduleRetransmit(long msgSeq, long delay) {
        retransmitExecutor.schedule(() -> {
            PendingMessage pending = pendingAcks.get(msgSeq);
            if (pending != null && pending.attempts < 3) {
                try {
                    byte[] data = pending.message.toByteArray();
                    DatagramPacket packet = new DatagramPacket(data, data.length, pending.address);
                    unicastSocket.send(packet);
                    pending.attempts++;

                    long nextDelay = Math.min(delay * 2, 500);
                    scheduleRetransmit(msgSeq, nextDelay);
                } catch (IOException e) {
                    System.err.println("Ошибка повторной отправки: " + e.getMessage());
                }
            } else if (pending != null) {
                pendingAcks.remove(msgSeq);
                System.out.println("Сообщение " + msgSeq + " не подтверждено после 3 попыток");
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void sendAck(long msgSeq, int receiverId, InetSocketAddress addr) {
        SnakesProto.GameMessage.Builder msgBuilder = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq)
                .setAck(SnakesProto.GameMessage.AckMsg.newBuilder().build());

        if (receiverId >= 0) {
            msgBuilder.setReceiverId(receiverId);
        }

        int senderId = controller.getPlayerId();
        if (senderId >= 0) {
            msgBuilder.setSenderId(senderId);
        }

        SnakesProto.GameMessage msg = msgBuilder.build();

        try {
            byte[] data = msg.toByteArray();
            DatagramPacket packet = new DatagramPacket(data, data.length, addr);
            unicastSocket.send(packet);
        } catch (IOException e) {
            System.err.println("Ошибка отправки ACK: " + e.getMessage());
        }
    }

    private void handleRoleChange(SnakesProto.GameMessage.RoleChangeMsg roleChange, int senderId) {
        if (roleChange.getSenderRole() == SnakesProto.NodeRole.VIEWER) {
            handleLeave(senderId);
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        long timeoutMs = 3000;

        Iterator<Map.Entry<Integer, Long>> it = alivePlayers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> elem = it.next();
            if (now - elem.getValue() > timeoutMs) {
                it.remove();
                playerAddresses.remove(elem.getKey());
                stateManager.deletePlayer(elem.getKey());
            }
        }

    }

    private void handleLeave(int playerId) {
        playerAddresses.remove(playerId);
        alivePlayers.remove(playerId);
        stateManager.deletePlayer(playerId);
    }

    public void leaveGame() {
        if (masterAddress != null && controller.isPlaying()) {
            SnakesProto.GameMessage.RoleChangeMsg roleChange =
                    SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                            .setSenderRole(SnakesProto.NodeRole.VIEWER)
                            .build();

            SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeq.getAndIncrement())
                    .setSenderId(controller.getPlayerId())
                    .setRoleChange(roleChange)
                    .build();

            sendReliable(msg, masterAddress);
        }
    }

    public void shutdown() {
        System.out.println("Остановка NetworkManager");
        running = false;

        retransmitExecutor.shutdownNow();
        listenerExecutor.shutdownNow();
        timeOutExecutor.shutdownNow();

        if (multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                if (networkInterface != null && multicastGroup != null) {
                    multicastSocket.leaveGroup(new InetSocketAddress(multicastGroup, MULTICAST_PORT), networkInterface);
                    System.out.println("Вышли из multicast группы");
                }
                multicastSocket.close();
            } catch (Exception e) {
                System.err.println("Ошибка при закрытии multicast socket: " + e.getMessage());
            }
        }

        if (unicastSocket != null && !unicastSocket.isClosed()) {
            unicastSocket.close();
            System.out.println("Unicast socket закрыт");
        }

        System.out.println("NetworkManager остановлен");
    }

    private static class PendingMessage {
        SnakesProto.GameMessage message;
        InetSocketAddress address;
        int attempts = 0;

        PendingMessage(SnakesProto.GameMessage msg, InetSocketAddress addr) {
            this.message = msg;
            this.address = addr;
        }
    }

    private static class PlayerAddress {
        InetAddress address;
        int port;

        PlayerAddress(InetAddress addr, int port) {
            this.address = addr;
            this.port = port;
        }
    }
}