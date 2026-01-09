import me.ippolitov.fit.snakes.SnakesProto;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class NET {
    private static final String MULTICAST_ADDRESS = "239.192.0.4";
    private static final int MULTICAST_PORT = 9192;

    private final Controller controller;
    private final StateManager stateManager;

    private DatagramSocket unicastSocket;
    private MulticastSocket multicastSocket;
    private InetAddress multicastGroup;
    private NetworkInterface networkInterface;

    private final Map<InetSocketAddress, GameInfo> availableGames = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Long> lastSeenGames = new ConcurrentHashMap<>();
    private Map<Integer, PlayerAddress> playerAddresses = new ConcurrentHashMap<>();
    private Map<Integer, Long> alivePlayers = new ConcurrentHashMap<>();

    private volatile InetSocketAddress masterAddress;
    private final AtomicLong msgSeq = new AtomicLong(0);
    private final Map<Long, PendingMessage> pendingAcks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService retransmitExecutor = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService timeOutExecutor = Executors.newScheduledThreadPool(1);
    private final ExecutorService listenerExecutor = Executors.newFixedThreadPool(2);
    private ScheduledFuture<?> timeoutTask;

    private final ScheduledExecutorService timeOutGame = Executors.newScheduledThreadPool(1);

    private volatile boolean running = true;
    private volatile boolean listeningUnicast = false;
    private String gameName;
    private SnakesProto.GameConfig gameConfig;

    private final AtomicInteger deputyId = new AtomicInteger(-1);
    private volatile long lastStateFromMaster = 0;

    private volatile boolean becomingMaster = false;

    private volatile long pendingJoinMsgSeq = -1;

    private final Object lock = new Object();

    public NET(Controller ctrl, StateManager stateMgr) {
        this.controller = ctrl;
        this.stateManager = stateMgr;

        try {
            unicastSocket = new DatagramSocket();
            unicastSocket.setBroadcast(true);

            multicastSocket = new MulticastSocket(MULTICAST_PORT);
            multicastSocket.setReuseAddress(true);
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
            timeOutGame.scheduleAtFixedRate(
                    this::updateGameList,
                    1000,
                    2000,
                    TimeUnit.MILLISECONDS
            );

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
                getCheckInterval(),
                getCheckInterval(),
                TimeUnit.MILLISECONDS);
    }

    private long getRetransmitInterval() {
        return gameConfig != null ? gameConfig.getStateDelayMs() / 10 : 100;
    }

    private long getNodeTimeoutMs() {
        return gameConfig != null ? (gameConfig.getStateDelayMs() * 3L) : 800;
    }

    private long getCheckInterval() {
        return gameConfig != null ? gameConfig.getStateDelayMs() / 10 : 100;
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

        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
            System.err.println("Остановлена проверка таймаутов");
        }

        listeningUnicast = false;
        masterAddress = null;
        deputyId.set(-1);
        lastStateFromMaster = 0;
        becomingMaster = false;
        pendingJoinMsgSeq = -1;

        pendingAcks.clear();

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

        playerAddresses.clear();
        alivePlayers.clear();
    }

    private void handleUnicastMessage(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        int senderId = msg.getSenderId();

        if (senderId > 0 && !sender.equals(masterAddress)) {
            alivePlayers.put(senderId, System.currentTimeMillis());
        }

        if (msg.hasAck()) {
            handleAck(msg, sender);
        }

        if (msg.hasSteer()) {
            int playerId = msg.getSenderId();
            SnakesProto.Direction direction = msg.getSteer().getDirection();
            stateManager.setPlayerDirection(playerId, direction);
            sendAck(msg.getMsgSeq(), playerId, sender);
        }

        if (msg.hasState()) {
            handleStateMsg(msg.getState(), sender);
            sendAck(msg.getMsgSeq(), msg.getSenderId(), sender);
        }

        if (msg.hasJoin()) {
            handleJoinRequest(msg, sender);
        }

        if (msg.hasRoleChange()) {
            handleRoleChange(msg.getRoleChange(), msg.getSenderId(), sender);
            sendAck(msg.getMsgSeq(), msg.getSenderId(), sender);
        }

        if (msg.hasError()) {
            controller.onError(msg.getError().getErrorMessage());
        }
    }

    private void handleAck(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        long ackSeq = msg.getMsgSeq();

        pendingAcks.remove(ackSeq);

        if (msg.hasReceiverId() && controller.getPlayerId() == -1) {
            if (pendingJoinMsgSeq != -1 && ackSeq == pendingJoinMsgSeq) {
                int playerId = msg.getReceiverId();
                controller.onJoinAccepted(playerId);
                System.out.println("Получен ID игрока: " + playerId);
                pendingJoinMsgSeq = -1;
            } else {
                System.err.println("Игнорируем ACK с устаревшим msg_seq: " + ackSeq +
                        " (ожидаем: " + pendingJoinMsgSeq + ")");
            }
        }
    }

    private void handleStateMsg(SnakesProto.GameMessage.StateMsg stateMsg, InetSocketAddress sender) {
        SnakesProto.GameState state = stateMsg.getState();

        if (controller.getPlayerRole() != SnakesProto.NodeRole.MASTER) {
            lastStateFromMaster = System.currentTimeMillis();
            masterAddress = sender;

            int myId = controller.getPlayerId();
            if (myId != -1) {
                boolean foundMyself = false;
                for (SnakesProto.GamePlayer player : state.getPlayers().getPlayersList()) {
                    if (player.getId() == myId) {
                        foundMyself = true;
                        break;
                    }
                }

                if (!foundMyself) {
                    System.err.println("ВНИМАНИЕ: Мой ID=" + myId + " не найден в состоянии от сервера!");
                    System.err.println("Возможно сервер удалил нас. Ожидаем новый ID...");
                    return;
                }
            }
        }

        controller.updateState(state);
        controller.updateGamePanel();

        if (controller.getPlayerRole() != SnakesProto.NodeRole.MASTER) {
            rebuildPlayerAddress(state);
        }
    }

    private void rebuildPlayerAddress(SnakesProto.GameState state) {
        playerAddresses.clear();

        for (SnakesProto.GamePlayer player : state.getPlayers().getPlayersList()) {
            if (player.getId() == controller.getPlayerId() || !player.hasIpAddress() || !player.hasPort()) {
                continue;
            }

            try {
                InetAddress address = InetAddress.getByName(player.getIpAddress());
                playerAddresses.put(player.getId(), new PlayerAddress(address, player.getPort()));
            } catch (UnknownHostException e) {
                System.err.println("Не удалось разрешить адрес для игрока " + player.getId());
            }
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
    }

    private void updateGameList() {
        cleanupOldGames();
        controller.onGamesListUpdate(new ArrayList<>(availableGames.values()));
    }

    private void cleanupOldGames() {
        long now = System.currentTimeMillis();
        long timeoutMs = 2000;
        Iterator<Map.Entry<InetSocketAddress, Long>> it = lastSeenGames.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<InetSocketAddress, Long> entry = it.next();
            if (now - entry.getValue() > timeoutMs) {
                availableGames.remove(entry.getKey());
                it.remove();
            }
        }
    }

    public void sendAnnouncements() {
        SnakesProto.NodeRole playerRole = controller.getPlayerRole();
        if (playerRole != SnakesProto.NodeRole.MASTER) return;

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
        this.gameName = game.getGameName();

        stateManager.setConfig(game.getConfig());

        System.out.println("Отправка JoinMsg на " + masterAddress);

        SnakesProto.GameMessage.JoinMsg joinMsg = SnakesProto.GameMessage.JoinMsg.newBuilder()
                .setPlayerName(playerName)
                .setGameName(game.getGameName())
                .setRequestedRole(viewerMode ? SnakesProto.NodeRole.VIEWER : SnakesProto.NodeRole.NORMAL)
                .build();

        long joinSeq = msgSeq.getAndIncrement();
        pendingJoinMsgSeq = joinSeq;

        SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(joinSeq)
                .setJoin(joinMsg)
                .build();

        sendReliable(msg, masterAddress);

        listenerExecutor.submit(this::listenUnicast);

        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }

        System.err.println("Запуск проверки таймаута для мастера JoinGame");
    }

    private void handleJoinRequest(SnakesProto.GameMessage msg, InetSocketAddress sender) {
        System.out.println("Обработка запроса на присоединение от " + sender);

        SnakesProto.GameState currentState = stateManager.getCurrentState();
        if (currentState == null) {
            System.err.println("Состояние игры не инициализировано - игра завершена");
            sendError("Игра уже завершена. Пожалуйста, выберите другую игру.", sender);
            return;
        }

        for (SnakesProto.GamePlayer player : currentState.getPlayers().getPlayersList()) {
            if (player.hasIpAddress() && player.hasPort() &&
                    player.getIpAddress().equals(sender.getAddress().getHostAddress()) &&
                    player.getPort() == sender.getPort()) {
                System.out.println("Игрок с таким адресом уже существует ID= " + player.getId());
                sendAck(msg.getMsgSeq(), player.getId(), sender);
                return;
            }
        }

        String playerName = msg.getJoin().getPlayerName();
        SnakesProto.NodeRole requestedRole = msg.getJoin().getRequestedRole();

        int newPlayerId = stateManager.addNewPlayer(playerName, sender, requestedRole);

        if (newPlayerId == -1) {
            sendError("Нет места на игровом поле", sender);
            return;
        }
        System.out.println("Присоединяется НОВЫЙ игрок: " + playerName + ", роль: " + requestedRole + ", ID: " + newPlayerId);

        sendAck(msg.getMsgSeq(), newPlayerId, sender);

        synchronized (lock) {
            playerAddresses.put(newPlayerId, new NET.PlayerAddress(sender.getAddress(), sender.getPort()));
            alivePlayers.put(newPlayerId, System.currentTimeMillis());

            if (deputyId.get() == -1 && requestedRole != SnakesProto.NodeRole.VIEWER) {
                assignDeputy(newPlayerId);
            }
        }

        System.out.println("Игрок " + playerName + " присоединился с ID " + newPlayerId);
    }

    private Integer selectNewDeputy() {
        if (playerAddresses.isEmpty()) {
            System.err.println("Нет игроков для DEPUTY");
            return null;
        }

        SnakesProto.GameState state = stateManager.getCurrentState();
        if (state == null) {
            System.err.println("Нет состояния для выбора DEPUTY");
            return null;
        }

        Integer newDeputyId = null;
        for (Map.Entry<Integer, PlayerAddress> entry : playerAddresses.entrySet()) {
            int playerId = entry.getKey();

            if (playerId == controller.getPlayerId()) {
                continue;
            }

            for (SnakesProto.GamePlayer player : state.getPlayers().getPlayersList()) {
                if (player.getId() == playerId) {
                    if (player.getRole() == SnakesProto.NodeRole.NORMAL) {
                        newDeputyId = playerId;
                        break;
                    }
                }
            }

            if (newDeputyId != null) break;
        }

        if (newDeputyId == null) {
            System.err.println("Нет подходящих игроков для DEPUTY (нет NORMAL игроков)");
            return null;
        }

        System.err.println("Выбран новый DEPUTY SelectNewDeputy: " + newDeputyId);
        return newDeputyId;
    }



    private void assignDeputy(int playerId) {
        if (deputyId.get() != -1) {
            System.out.println("DEPUTY уже назначен: " + deputyId.get() + ", игнорируем значение " + playerId);
            return;
        }

        deputyId.set(playerId);

        updatePlayerRole(playerId, SnakesProto.NodeRole.DEPUTY);

        sendRoleChange(playerId, SnakesProto.NodeRole.DEPUTY);

        System.err.println("DEPUTY назначен assignDeputy " + playerId);
    }

    private void updatePlayerRole(int playerId, SnakesProto.NodeRole newRole) {
        stateManager.updatePlayerRole(playerId, newRole);
    }
//private void checkTimeouts() {
//        SnakesProto.GameConfig config = GameModel.getInstance().getConfig();
//        if (config == null) return;
//
//        long timeout = (long) (config.getStateDelayMs() * 0.8);
//        lastReceived.entrySet().removeIf(e -> {
//            if (System.currentTimeMillis() - e.getValue() > timeout) {
//                handleDisconnect(e.getKey());
//                return true;
//            }
//            return false;
//        });
//    }
//
//    private void handleDisconnect(InetSocketAddress addr) {
//        playersAddresses.entrySet().removeIf(entry -> entry.getValue().equals(addr));
//    }
//
//    private void sendPingsIfNeeded() {
//        SnakesProto.GameConfig config = GameModel.getInstance().getConfig();
//        if (config == null) return;
//
//        SnakesProto.GameMessage ping = SnakesProto.GameMessage.newBuilder()
//                .setMsgSeq(msgSeq++)
//                .setPing(SnakesProto.GameMessage.PingMsg.newBuilder())
//                .build();
//        for (InetSocketAddress addr : playersAddresses.values()) {
//            sendWithResend(ping, addr);
//        }
//        if (masterAddress != null) {
//            sendWithResend(ping, masterAddress);
//        }
//    }
//
//    private void handleRoleChange(SnakesProto.GameMessage msg, InetSocketAddress sender) {
//        if (msg.getRoleChange().hasReceiverRole() && msg.getReceiverId() == GameModel.getInstance().getPlayerId()) {
//            GameModel.getInstance().setRole(msg.getRoleChange().getReceiverRole());
//            if (msg.getRoleChange().getReceiverRole() == SnakesProto.NodeRole.MASTER) {
//                masterAddress = null;
//            }
//        }
    private void sendRoleChange(int receiverId, SnakesProto.NodeRole role) {
        SnakesProto.GameMessage.RoleChangeMsg roleChange =
                SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                        .setReceiverRole(role)
                        .build();

        SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                .setMsgSeq(msgSeq.getAndIncrement())
                .setSenderId(controller.getPlayerId())
                .setReceiverId(receiverId)
                .setRoleChange(roleChange)
                .build();

        PlayerAddress addr = playerAddresses.get(receiverId);
        if (addr != null) {
            InetSocketAddress target = new InetSocketAddress(addr.address, addr.port);
            sendReliable(msg, target);
        }
    }

    private void notifyAllAboutNewMaster() {
        SnakesProto.GameMessage.RoleChangeMsg roleChange =
                SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                        .setSenderRole(SnakesProto.NodeRole.MASTER)
                        .build();

        for (Map.Entry<Integer, PlayerAddress> entry : playerAddresses.entrySet()) {
            int receiverId = entry.getKey();
            if (receiverId == controller.getPlayerId()) continue;

            SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeq.getAndIncrement())
                    .setSenderId(controller.getPlayerId())
                    .setReceiverId(receiverId)
                    .setRoleChange(roleChange)
                    .build();

            InetSocketAddress addr = new InetSocketAddress(
                    entry.getValue().address,
                    entry.getValue().port
            );

            sendReliable(msg, addr);
        }

        System.out.println("Уведомления о новом MASTER отправлены всем игрокам");
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
            sendMessage(data, addr);
        } catch (IOException e) {
            System.err.println("Ошибка отправки ERROR: " + e.getMessage());
        }
    }

    public void sendSteer(SnakesProto.Direction direction) {
        if (masterAddress == null) {
            System.err.println("Не могу отправить Steer - нет адреса мастера");
            return;
        }

        int playerId = controller.getPlayerId();
        if (playerId == -1) {
            System.err.println("Не могу отправить Steer - ID игрока не установлен");
            return;
        }

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

        Map<Integer, PlayerAddress> copyPlayerAddresses;
        synchronized (lock) {
            copyPlayerAddresses = new HashMap<>(playerAddresses);
        }

        for (Map.Entry<Integer, PlayerAddress> entry : copyPlayerAddresses.entrySet()) {
            int receiverId = entry.getKey();
            if (receiverId == controller.getPlayerId()) continue;

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
            sendMessage(data, addr);

            if (!msg.hasAck() && !msg.hasAnnouncement()) {
                PendingMessage pending = new PendingMessage(msg, addr);
                pendingAcks.put(msg.getMsgSeq(), pending);

                scheduleRetransmit(msg.getMsgSeq(), getRetransmitInterval());
            }
        } catch (IOException e) {
            System.err.println("Ошибка отправки сообщения: " + e.getMessage());
        }
    }

    private void sendMessage(byte[] data, InetSocketAddress address) throws IOException {
        DatagramPacket packet = new DatagramPacket(data, data.length, address);
        unicastSocket.send(packet);
    }

    private void scheduleRetransmit(long msgSeq, long delay) {
        retransmitExecutor.schedule(() -> {
            PendingMessage pending = pendingAcks.get(msgSeq);
            if (pending != null && pending.attempts < 3) {
                try {
                    InetSocketAddress targetAddr = pending.address;
                    if (masterAddress != null &&
                            controller.getPlayerRole() != SnakesProto.NodeRole.MASTER &&
                            pending.message.hasSteer()) {
                        targetAddr = masterAddress;
                        pending.address = masterAddress;
                    }

                    byte[] data = pending.message.toByteArray();
                    sendMessage(data, targetAddr);
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
            sendMessage(data, addr);
        } catch (IOException e) {
            System.err.println("Ошибка отправки ACK: " + e.getMessage());
        }
    }

    private void handleRoleChange(SnakesProto.GameMessage.RoleChangeMsg roleChange, int senderId, InetSocketAddress sender) {
        if (roleChange.hasSenderRole() && roleChange.getSenderRole() == SnakesProto.NodeRole.MASTER) {
            System.err.println("Получено уведомление: игрок " + senderId + " стал новым MASTER");
            masterAddress = sender;
            lastStateFromMaster = System.currentTimeMillis();

            if (becomingMaster && controller.getPlayerRole() != SnakesProto.NodeRole.MASTER) {
                System.err.println("Отменяем становление мастером - другой игрок уже стал мастером");
                becomingMaster = false;
            }
            return;
        }

        SnakesProto.NodeRole receiverRole = roleChange.hasReceiverRole() ? roleChange.getReceiverRole() : null;

        if (receiverRole != null) {
            if (receiverRole == SnakesProto.NodeRole.VIEWER) {
                controller.setPlayerRole(receiverRole);
                System.err.println("Меня перевели в VIEWER");
            } else if (receiverRole == SnakesProto.NodeRole.DEPUTY) {
                controller.setPlayerRole(receiverRole);
                System.err.println("Меня назначили DEPUTY");

                timeoutTask = timeOutExecutor.scheduleAtFixedRate(
                        this::checkMasterTimeout,
                        getCheckInterval(),
                        getCheckInterval(),
                        TimeUnit.MILLISECONDS
                );

            } else if (receiverRole == SnakesProto.NodeRole.MASTER) {
                controller.setPlayerRole(receiverRole);
                System.err.println("Меня назначили MASTER напрямую");
            }
        }

        if (roleChange.hasSenderRole() && roleChange.getSenderRole() == SnakesProto.NodeRole.VIEWER) {
            handleLeave(senderId);
        }
    }

    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        long timeoutMs = getNodeTimeoutMs();

        synchronized (lock) {
            List<Integer> timeOutPlayers = new ArrayList<>();
            for (Map.Entry<Integer, Long> entry : alivePlayers.entrySet()) {
                if (now - entry.getValue() > timeoutMs) {
                    timeOutPlayers.add(entry.getKey());
                }
            }

            for (int playerId : timeOutPlayers) {
                System.err.println("Удаляем игрока по таймауту: " + playerId);
                removePlayer(playerId);

                if (playerId == deputyId.get()) {
                    System.err.println("DEPUTY отключился по таймауту " + playerId);
                    deputyId.set(-1);

                    Integer newDeputyId = selectNewDeputy();
                    if (newDeputyId != null) {
                        assignDeputy(newDeputyId);
                    }
                }
            }
        }
    }

    private void checkMasterTimeout() {
        if (controller.getPlayerRole() == SnakesProto.NodeRole.MASTER) {
            return;
        }

        if (becomingMaster) {
            return;
        }

        long timeoutMs = getNodeTimeoutMs();
        long now = System.currentTimeMillis();

        if (lastStateFromMaster == 0) {
            return;
        }

        if (controller.getPlayerRole() != SnakesProto.NodeRole.DEPUTY) {
            return;
        }

        synchronized (lock) {
            if (now - lastStateFromMaster > timeoutMs) {
                synchronized (stateManager) {
                    System.err.println("Мастер отключился по таймауту");

                    SnakesProto.GameState currentState = stateManager.getCurrentState();

                    int myId = controller.getPlayerId();
                    boolean iAmInState = false;
                    if (currentState != null && myId != -1) {
                        for (SnakesProto.GamePlayer player : currentState.getPlayers().getPlayersList()) {
                            if (player.getId() == myId) {
                                iAmInState = true;
                                break;
                            }
                        }
                    }

                    if (!iAmInState) {
                        System.err.println("Я не найден в состоянии игры (ID=" + myId + "). Не могу стать мастером.");
                        lastStateFromMaster = System.currentTimeMillis();
                        return;
                    }

                    if (currentState != null) {
                        for (SnakesProto.GamePlayer player : currentState.getPlayers().getPlayersList()) {
                            if (player.getRole() == SnakesProto.NodeRole.MASTER) {
                                System.err.println("Удаляем старого Master: " + player.getId());
                                removePlayer(player.getId());
                                break;
                            }
                        }
                    }

                    if (controller.getPlayerRole() == SnakesProto.NodeRole.DEPUTY) {
                        System.err.println("Я DEPUTY буду становиться Мастером");
                        becomingMaster = true;
                        becomeMasterFromDeputy();
                    }
                }
            }
        }
    }

    private void becomeMasterFromDeputy() {
        stateManager.restorePlayerCounter();

        updatePlayerRole(controller.getPlayerId(), SnakesProto.NodeRole.MASTER);

        deputyId.set(-1);

        notifyAllAboutNewMaster();

        Integer newDeputyId = selectNewDeputy();
        if (newDeputyId != null) {
            assignDeputy(newDeputyId);
        }

        controller.becomeMaster();

        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }

        timeoutTask = timeOutExecutor.scheduleAtFixedRate(
                this::checkTimeouts,
                getCheckInterval(),
                getCheckInterval(),
                TimeUnit.MILLISECONDS);

        becomingMaster = false;
        System.err.println("DEPUTY стал Master " + controller.getPlayerRole() + " " + controller.getPlayerId());
    }

    private void removePlayer(int playerId) {
        playerAddresses.remove(playerId);
        alivePlayers.remove(playerId);
        stateManager.deletePlayer(playerId);

        System.out.println("Игрок " + playerId + " удалён из всех структур");
    }

    private void handleLeave(int playerId) {
        synchronized (lock) {
            removePlayer(playerId);

            if (controller.getPlayerRole() == SnakesProto.NodeRole.MASTER && playerId == deputyId.get()) {
                System.err.println("DEPUTY вышел из игры сам");
                deputyId.set(-1);
                Integer newDeputy = selectNewDeputy();
                if (newDeputy != null) {
                    assignDeputy(newDeputy);
                }
            }
        }
    }

    public void leaveGame() {
        System.err.println("зашли в leaveGame");

        if (masterAddress != null && controller.getPlayerRole() != SnakesProto.NodeRole.MASTER) {
            System.err.println("Я отправляю сообщение ROLECHANGE мой ID" + controller.getPlayerId());
            SnakesProto.GameMessage.RoleChangeMsg roleChange =
                    SnakesProto.GameMessage.RoleChangeMsg.newBuilder()
                            .setSenderRole(SnakesProto.NodeRole.VIEWER)
                            .build();

            SnakesProto.GameMessage msg = SnakesProto.GameMessage.newBuilder()
                    .setMsgSeq(msgSeq.getAndIncrement())
                    .setSenderId(controller.getPlayerId())
                    .setRoleChange(roleChange)
                    .build();

            try {
                byte[] data = msg.toByteArray();
                sendMessage(data, masterAddress);
            } catch (IOException e) {
                System.err.println("Ошибка отправки RoleChange: " + e.getMessage());
            }

            System.err.println("отправили сообщение master о выходе");
        }
    }

    public void shutdown() {
        System.out.println("Остановка NetworkManager");
        running = false;

        retransmitExecutor.shutdownNow();
        listenerExecutor.shutdownNow();
        timeOutExecutor.shutdownNow();
        timeOutGame.shutdownNow();

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