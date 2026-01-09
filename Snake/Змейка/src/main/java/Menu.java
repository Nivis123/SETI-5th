// MenuPanel.java
import me.ippolitov.fit.snakes.SnakesProto;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public class Menu extends JPanel {
    private Controller controller;
    private DefaultListModel<String> gamesListModel;
    private JList<String> gamesList;
    private Map<Integer, GameInfo> gamesMap = new HashMap<>();
    private JTextField nameField;
    private JSpinner widthSpinner;
    private JSpinner heightSpinner;
    private JSpinner foodSpinner;
    private JSpinner delaySpinner;

    private final Color BACKGROUND_COLOR = new Color(240, 242, 245);
    private final Color PANEL_COLOR = new Color(255, 255, 255);
    private final Color BORDER_COLOR = new Color(220, 223, 230);
    private final Color TEXT_COLOR = new Color(48, 49, 51);
    private final Color SECONDARY_TEXT_COLOR = new Color(96, 98, 102);
    private final Color PRIMARY_COLOR = new Color(21, 21, 21);
    private final Color SUCCESS_COLOR = new Color(64, 158, 255);
    private final Font MAIN_FONT = new Font("Segoe UI", Font.PLAIN, 12);
    private final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 26);
    private final Font HEADER_FONT = new Font("Segoe UI", Font.BOLD, 18);
    private final Font BUTTON_FONT = new Font("Segoe UI Semibold", Font.PLAIN, 14);

    public Menu(Controller ctrl) {
        this.controller = ctrl;
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(25, 25, 25, 25));
        setBackground(BACKGROUND_COLOR);

        JLabel titleLabel = new JLabel("СЕТЕВАЯ ЗМЕЙКА", SwingConstants.CENTER);
        titleLabel.setFont(TITLE_FONT);
        titleLabel.setForeground(PRIMARY_COLOR);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 30, 0));
        add(titleLabel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout(25, 0));
        centerPanel.setOpaque(false);

        JPanel createPanel = createGameCreationPanel();
        JPanel joinPanel = createGameListPanel();

        centerPanel.add(createPanel, BorderLayout.CENTER);
        centerPanel.add(joinPanel, BorderLayout.EAST);

        add(centerPanel, BorderLayout.CENTER);

        JPanel instructionsPanel = new JPanel();
        instructionsPanel.setOpaque(false);
        JLabel instructions = new JLabel("<html><center><font color='" + colorToHex(SECONDARY_TEXT_COLOR) + "'>" +
                "Управление: W/A/S/D<br>" +
                "ESC - выход</font></center></html>");
        instructions.setFont(MAIN_FONT);
        instructions.setHorizontalAlignment(SwingConstants.CENTER);
        instructionsPanel.add(instructions);
        add(instructionsPanel, BorderLayout.SOUTH);
    }

    private String colorToHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private JPanel createGameCreationPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 20));
        mainPanel.setBackground(PANEL_COLOR);
        mainPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                BorderFactory.createEmptyBorder(25, 25, 25, 25)
        ));

        JLabel title = new JLabel("СОЗДАТЬ НОВУЮ ИГРУ");
        title.setFont(HEADER_FONT);
        title.setForeground(TEXT_COLOR);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        mainPanel.add(title, BorderLayout.NORTH);

        JPanel settingsPanel = new JPanel(new GridBagLayout());
        settingsPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(8, 10, 8, 10);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        JPanel namePanel = createLabeledField("Имя игрока:",
                nameField = new JTextField("Игрок" + new Random().nextInt(1000), 20));
        settingsPanel.add(namePanel, gbc);

        gbc.gridy = 1;
        gbc.gridwidth = 1;
        widthSpinner = new JSpinner(new SpinnerNumberModel(40, 10, 100, 5));
        settingsPanel.add(createLabeledField("Ширина:", widthSpinner), gbc);

        gbc.gridx = 1;
        heightSpinner = new JSpinner(new SpinnerNumberModel(30, 10, 100, 5));
        settingsPanel.add(createLabeledField("Высота:", heightSpinner), gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        foodSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 100, 1));
        settingsPanel.add(createLabeledField("Базовая еда:", foodSpinner), gbc);

        gbc.gridx = 1;
        delaySpinner = new JSpinner(new SpinnerNumberModel(300, 100, 3000, 50));
        settingsPanel.add(createLabeledField("Задержка (мс):", delaySpinner), gbc);

        mainPanel.add(settingsPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));

        JButton newGameBtn = new JButton("НАЧАТЬ ИГРУ");
        newGameBtn.setFont(BUTTON_FONT);
        newGameBtn.setBackground(SUCCESS_COLOR);
        newGameBtn.setForeground(Color.WHITE);
        newGameBtn.setFocusPainted(false);
        newGameBtn.setBorderPainted(false);
        newGameBtn.setPreferredSize(new Dimension(180, 50));
        newGameBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        newGameBtn.addActionListener(e -> startNewGame());
        buttonPanel.add(newGameBtn);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        return mainPanel;
    }

    private JPanel createGameListPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBackground(PANEL_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                BorderFactory.createEmptyBorder(20, 20, 20, 20)
        ));
        panel.setPreferredSize(new Dimension(300, 400));

        JLabel title = new JLabel("ДОСТУПНЫЕ ИГРЫ");
        title.setFont(HEADER_FONT);
        title.setForeground(TEXT_COLOR);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(title, BorderLayout.NORTH);

        gamesListModel = new DefaultListModel<>();
        gamesListModel.addElement("Поиск игр...");
        gamesListModel.addElement("(Если игры не появляются, создайте новую)");

        gamesList = new JList<>(gamesListModel);
        gamesList.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        gamesList.setBackground(PANEL_COLOR);
        gamesList.setForeground(TEXT_COLOR);
        gamesList.setSelectionBackground(new Color(236, 245, 255));
        gamesList.setSelectionForeground(PRIMARY_COLOR);
        gamesList.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        gamesList.setVisibleRowCount(6);

        JScrollPane scrollPane = new JScrollPane(gamesList);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        scrollPane.getViewport().setBackground(PANEL_COLOR);
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel buttonsPanel = new JPanel(new GridLayout(2, 1, 0, 10));
        buttonsPanel.setOpaque(false);
        buttonsPanel.setBorder(BorderFactory.createEmptyBorder(15, 0, 0, 0));

        JButton joinBtn = new JButton("ПРИСОЕДИНИТЬСЯ");
        joinBtn.setFont(BUTTON_FONT);
        joinBtn.setBackground(PRIMARY_COLOR);
        joinBtn.setForeground(Color.WHITE);
        joinBtn.setFocusPainted(false);
        joinBtn.setBorderPainted(false);
        joinBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        joinBtn.addActionListener(e -> joinSelectedGame(false));
        buttonsPanel.add(joinBtn);

        JButton viewBtn = new JButton("НАБЛЮДАТЬ");
        viewBtn.setFont(BUTTON_FONT);
        viewBtn.setBackground(new Color(156, 39, 176));
        viewBtn.setForeground(Color.WHITE);
        viewBtn.setFocusPainted(false);
        viewBtn.setBorderPainted(false);
        viewBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        viewBtn.addActionListener(e -> joinSelectedGame(true));
        buttonsPanel.add(viewBtn);

        panel.add(buttonsPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createLabeledField(String labelText, JComponent component) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));

        JLabel label = new JLabel(labelText);
        label.setFont(MAIN_FONT);
        label.setForeground(SECONDARY_TEXT_COLOR);
        label.setPreferredSize(new Dimension(100, 25));
        panel.add(label, BorderLayout.WEST);

        if (component instanceof JTextField) {
            ((JTextField) component).setFont(MAIN_FONT);
            ((JTextField) component).setBackground(PANEL_COLOR);
            ((JTextField) component).setForeground(TEXT_COLOR);
            ((JTextField) component).setCaretColor(PRIMARY_COLOR);
            ((JTextField) component).setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER_COLOR),
                    BorderFactory.createEmptyBorder(8, 12, 8, 12)
            ));
        } else if (component instanceof JSpinner) {
            ((JSpinner) component).setFont(MAIN_FONT);
            JComponent editor = ((JSpinner) component).getEditor();
            if (editor instanceof JSpinner.DefaultEditor) {
                JTextField textField = ((JSpinner.DefaultEditor) editor).getTextField();
                textField.setBackground(PANEL_COLOR);
                textField.setForeground(TEXT_COLOR);
                textField.setCaretColor(PRIMARY_COLOR);
                textField.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(BORDER_COLOR),
                        BorderFactory.createEmptyBorder(8, 12, 8, 12)
                ));
            }
        }

        panel.add(component, BorderLayout.CENTER);

        return panel;
    }

    private void startNewGame() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Введите имя игрока!",
                    "Ошибка",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        int width = (Integer) widthSpinner.getValue();
        int height = (Integer) heightSpinner.getValue();
        int food = (Integer) foodSpinner.getValue();
        int delay = (Integer) delaySpinner.getValue();

        SnakesProto.GameConfig config = SnakesProto.GameConfig.newBuilder()
                .setWidth(width)
                .setHeight(height)
                .setFoodStatic(food)
                .setStateDelayMs(delay)
                .build();

        controller.startNewGame(name, config);
    }

    private void joinSelectedGame(boolean viewerMode) {
        int selectedIndex = gamesList.getSelectedIndex();
        if (selectedIndex < 0) {
            JOptionPane.showMessageDialog(this,
                    "Выберите игру из списка!",
                    "Ошибка",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Введите имя игрока!",
                    "Ошибка",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        GameInfo game = gamesMap.get(selectedIndex);
        if (game != null) {
            if (!game.canJoin() && !viewerMode) {
                JOptionPane.showMessageDialog(this,
                        "Игра заполнена! Можете присоединиться как наблюдатель.",
                        "Предупреждение",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            controller.joinGame(game, name, viewerMode);
        }
    }

    public void updateGamesList(List<GameInfo> games) {
        int selectedIndex = gamesList.getSelectedIndex();
        String selectedValue = gamesList.getSelectedValue();

        gamesListModel.clear();
        gamesMap.clear();

        if (games.isEmpty()) {
            gamesListModel.addElement("Нет доступных игр");
            gamesListModel.addElement("(Если игры не появляются, создайте новую)");
        } else {
            for (int i = 0; i < games.size(); i++) {
                GameInfo game = games.get(i);
                String gameStr = game.toString();
                gamesListModel.addElement(gameStr);
                gamesMap.put(i, game);

                if (selectedValue != null && selectedValue.startsWith(game.getGameName())) {
                    gamesList.setSelectedIndex(i);
                }
            }

            if (gamesList.getSelectedIndex() < 0 && selectedIndex >= 0 && games.size() > 0) {
                gamesList.setSelectedIndex(0);
            }
        }
    }
}