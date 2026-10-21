package com.devtool.plugin.loghub;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MicroserviceLogPanel implements Disposable {

    private static final int MAX_BUFFER_LINES = 10000;
    private static final int MAX_STACK_CONTINUATION_LINES = 80;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // 剥离控制台可能附带的 ANSI 颜色控制字符
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");

    // 匹配任意 (Xxx.java:52) 或 Xxx.java:52 以及前置包名
    private static final Pattern FILE_LINE_LINK_PATTERN = Pattern.compile(
            "(?:([a-zA-Z0-9_$.]+)\\.[a-zA-Z0-9_$<>]+\\()?" +
            "(([a-zA-Z0-9_$]+)\\.(java|kt|groovy|scala)):(\\d+)"
    );

    // 匹配以日期或时间开头的标准日志行（支持 [2026-10-21 09:47:06.595] / 2026-10-21 09:47:06.595 / 09:47:06.595 等）
    private static final Pattern TIMESTAMP_LINE_START_PATTERN = Pattern.compile(
            "^\\s*\\[?(?:\\d{4}[-/]\\d{2}[-/]\\d{2}[T\\s]+)?\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,6})?"
    );

    // 严格区分大小写：仅在日志头区域内提取标准日志级别
    private static final Pattern HEADER_LEVEL_TOKEN_PATTERN = Pattern.compile(
            "\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\b"
    );

    // 无日志头时的独立异常行匹配（严格区分大小写，且必须以 Exception/Error/Throwable 结尾，避免误伤 GlobalExceptionHandler 或 SQL 字段）
    private static final Pattern STANDALONE_EXCEPTION_HEAD_PATTERN = Pattern.compile(
            "^\\s*(?:Caused by:|Suppressed:|Exception in thread\\s+\"[^\"]+\"\\s+|(?:[a-zA-Z_$][a-zA-Z0-9_$]*\\.)+[A-Z][a-zA-Z0-9_$]*(?:Exception|Error|Throwable)(?=:|$|\\s))"
    );

    // 标准堆栈帧行匹配
    private static final Pattern STACK_LINE_PATTERN = Pattern.compile(
            "^(\\s+at\\s+[a-zA-Z0-9_$/<>]+\\(|\\s*\\.\\.\\.\\s*\\d+\\s+more\\b|\\s*Caused by:|\\s*Suppressed:)"
    );

    private final Project project;
    private final JPanel rootPanel;
    private final ConsoleView allLogsConsole;
    private final ConsoleView errorLogsConsole;
    private final JTabbedPane tabbedPane;
    private final JSplitPane splitPane;
    private final JPanel centerContainer;

    private final JComboBox<String> serviceSelector;
    private final JComboBox<String> levelSelector;
    private final JTextField keywordField;
    private final JCheckBox splitViewCheck;
    private final JCheckBox pauseCheck;
    private final JLabel statusLabel;

    private final Set<ProcessHandler> attachedHandlers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> knownServices = new LinkedHashSet<>();
    private final Map<String, Integer> serviceErrorCounts = new ConcurrentHashMap<>();
    private final Map<ProcessHandler, ServiceStreamState> streamStates = new ConcurrentHashMap<>();
    private final Deque<LogRecord> logBuffer = new ConcurrentLinkedDeque<>();
    private final AtomicInteger totalErrorBlocks = new AtomicInteger(0);
    private final AtomicInteger nextErrorBlockId = new AtomicInteger(1);

    private volatile String selectedService = "全部服务 (All)";
    private volatile int selectedLevelIndex = 0; // 0=全部级别, 1=WARN+ERROR, 2=仅ERROR
    private volatile String keywordFilter = "";
    private volatile boolean paused = false;
    private volatile int lastPrintedErrorBlockId = -1;

    private static class LogRecord {
        final String time;
        final String serviceName;
        final String rawLine;
        final Level level;
        final int errorBlockId;

        LogRecord(String time, String serviceName, String rawLine, Level level, int errorBlockId) {
            this.time = time;
            this.serviceName = serviceName;
            this.rawLine = rawLine;
            this.level = level;
            this.errorBlockId = errorBlockId;
        }
    }

    private enum Level {
        INFO, WARN, ERROR
    }

    private static class ServiceStreamState {
        final String serviceName;
        final StringBuilder lineBuffer = new StringBuilder();
        int remainingErrorLines = 0;
        int currentErrorBlockId = 0;

        ServiceStreamState(String serviceName) {
            this.serviceName = serviceName;
        }
    }

    public MicroserviceLogPanel(@NotNull Project project, @NotNull Disposable parentDisposable) {
        this.project = project;
        Disposer.register(parentDisposable, this);

        this.allLogsConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        this.errorLogsConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        Filter fileLinkFilter = createStackTraceFileFilter(project);
        this.allLogsConsole.addMessageFilter(fileLinkFilter);
        this.errorLogsConsole.addMessageFilter(fileLinkFilter);
        Disposer.register(this, this.allLogsConsole);
        Disposer.register(this, this.errorLogsConsole);

        this.rootPanel = new JPanel(new BorderLayout());

        // 1. 顶部工具栏
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));

        topBar.add(new JLabel("服务筛选:"));
        serviceSelector = new JComboBox<>(new String[]{"全部服务 (All)"});
        serviceSelector.setPreferredSize(new Dimension(170, 28));
        serviceSelector.addActionListener(e -> {
            Object item = serviceSelector.getSelectedItem();
            if (item != null) {
                selectedService = extractRawServiceName(item.toString());
                rebuildConsolesFromBuffer();
            }
        });
        topBar.add(serviceSelector);

        topBar.add(new JLabel("级别:"));
        levelSelector = new JComboBox<>(new String[]{
                "全部日志 (INFO/DEBUG/ERROR)",
                "仅警告与报错 (WARN + ERROR)",
                "仅看报错 (ERROR Only)"
        });
        levelSelector.addActionListener(e -> {
            selectedLevelIndex = levelSelector.getSelectedIndex();
            rebuildConsolesFromBuffer();
        });
        topBar.add(levelSelector);

        topBar.add(new JLabel("搜索过滤:"));
        keywordField = new JTextField(16);
        keywordField.setToolTipText("输入接口路径、异常名、关键字实时过滤日志");
        keywordField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { onKeywordChanged(); }
            @Override
            public void removeUpdate(DocumentEvent e) { onKeywordChanged(); }
            @Override
            public void changedUpdate(DocumentEvent e) { onKeywordChanged(); }
        });
        topBar.add(keywordField);

        splitViewCheck = new JCheckBox("左右分屏同看(全量+报错)", true);
        splitViewCheck.addActionListener(e -> updateLayoutMode());
        topBar.add(splitViewCheck);

        pauseCheck = new JCheckBox("暂停滚动", false);
        pauseCheck.addActionListener(e -> paused = pauseCheck.isSelected());
        topBar.add(pauseCheck);

        JButton scanBtn = new JButton("刷新挂载服务");
        scanBtn.setToolTipText("主动扫描当前已启动的所有微服务控制台");
        scanBtn.addActionListener(e -> attachAllRunningServices());
        topBar.add(scanBtn);

        JButton clearBtn = new JButton("清空日志");
        clearBtn.addActionListener(e -> clearAllLogs());
        topBar.add(clearBtn);

        JButton killOrphansBtn = new JButton("强杀残留Java服务");
        killOrphansBtn.setForeground(new Color(210, 60, 60));
        killOrphansBtn.setToolTipText("一键扫描并终止后台残留的 Java 服务与编译进程，释放端口");
        killOrphansBtn.addActionListener(e -> killBackendJavaProcesses());
        topBar.add(killOrphansBtn);

        statusLabel = new JLabel("已挂载服务: 0 | 累计异常: 0");
        topBar.add(statusLabel);

        rootPanel.add(topBar, BorderLayout.NORTH);

        // 2. 中部核心日志区（支持左右分屏或标签页切换）
        centerContainer = new JPanel(new BorderLayout());
        tabbedPane = new JTabbedPane();
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.58);

        updateLayoutMode();
        rootPanel.add(centerContainer, BorderLayout.CENTER);

        // 3. 印出欢迎横幅
        printWelcomeBanner();

        // 4. 订阅后续新启动的微服务 + 立即扫描当前已在运行的微服务
        subscribeExecutionTopic();
        attachAllRunningServices();
    }

    private void updateLayoutMode() {
        centerContainer.removeAll();
        JComponent allComp = allLogsConsole.getComponent();
        JComponent errComp = errorLogsConsole.getComponent();

        if (splitViewCheck.isSelected()) {
            JPanel leftWrapper = new JPanel(new BorderLayout());
            JLabel leftTitle = new JLabel("  📋 全量实时合并日志 (INFO / DEBUG / SQL / ERROR) - 每行带 [服务名]");
            leftTitle.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            leftWrapper.add(leftTitle, BorderLayout.NORTH);
            leftWrapper.add(allComp, BorderLayout.CENTER);

            JPanel rightWrapper = new JPanel(new BorderLayout());
            JLabel rightTitle = new JLabel("  🔥 全服务报错提取台 (自动拦截所有微服务 Exception 堆栈，点击蓝字直达源码)");
            rightTitle.setForeground(new Color(235, 80, 80));
            rightTitle.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            rightWrapper.add(rightTitle, BorderLayout.NORTH);
            rightWrapper.add(errComp, BorderLayout.CENTER);

            splitPane.setLeftComponent(leftWrapper);
            splitPane.setRightComponent(rightWrapper);
            centerContainer.add(splitPane, BorderLayout.CENTER);
        } else {
            tabbedPane.removeAll();
            tabbedPane.addTab("📋 全量实时合并日志 (All Logs)", allComp);
            tabbedPane.addTab("🔥 纯报错汇总 (Errors: " + totalErrorBlocks.get() + ")", errComp);
            centerContainer.add(tabbedPane, BorderLayout.CENTER);
        }
        centerContainer.revalidate();
        centerContainer.repaint();
    }

    private void printWelcomeBanner() {
        allLogsConsole.print("================================================================================\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        allLogsConsole.print(" 🚀 微服务全量日志 & 报错总控台已就绪！\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        allLogsConsole.print(" • 左侧/Tab1 实时汇聚所有微服务的全部日志（INFO/DEBUG/SQL/ERROR），自动前置 [服务名]\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        allLogsConsole.print(" • 右侧/Tab2 自动过滤提取所有微服务的异常与报错堆栈，一眼看清是哪个服务报错\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        allLogsConsole.print(" • 点击堆栈中的 (Xxx.java:行号) 蓝色链接可直接跳转到对应代码行\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        allLogsConsole.print("================================================================================\n\n", ConsoleViewContentType.SYSTEM_OUTPUT);

        errorLogsConsole.print("================================================================================\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        errorLogsConsole.print(" 🔥 纯报错监控区：当前暂无微服务报错。一旦任意微服务抛出异常，将在此处自动聚合！\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        errorLogsConsole.print("================================================================================\n\n", ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    private void subscribeExecutionTopic() {
        project.getMessageBus().connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
            @Override
            public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
                String serviceName = cleanServiceName(env.getRunProfile().getName());
                attachProcessHandler(serviceName, handler);
            }
        });
    }

    public void attachAllRunningServices() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                List<RunContentDescriptor> descriptors = RunContentManager.getInstance(project).getAllDescriptors();
                for (RunContentDescriptor desc : descriptors) {
                    ProcessHandler handler = desc.getProcessHandler();
                    if (handler != null && !handler.isProcessTerminated() && !handler.isProcessTerminating()) {
                        String name = cleanServiceName(desc.getDisplayName());
                        attachProcessHandler(name, handler);
                    }
                }
                pruneDeadHandlersAndSyncServices();
            } catch (Throwable ignored) {
            }
        });
    }

    private void pruneDeadHandlersAndSyncServices() {
        Iterator<ProcessHandler> it = attachedHandlers.iterator();
        while (it.hasNext()) {
            ProcessHandler h = it.next();
            if (h == null || h.isProcessTerminated() || h.isProcessTerminating()) {
                it.remove();
                streamStates.remove(h);
            }
        }
        knownServices.clear();
        for (ServiceStreamState s : streamStates.values()) {
            knownServices.add(s.serviceName);
        }
        if (!"全部服务 (All)".equals(selectedService) && !knownServices.contains(selectedService)) {
            selectedService = "全部服务 (All)";
        }
        refreshServiceDropdown();
        updateStatusLabel();
    }

    private String cleanServiceName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return "UnknownService";
        }
        String s = rawName.trim();
        int bracket = s.indexOf('[');
        if (bracket > 0) {
            s = s.substring(0, bracket).trim();
        }
        return s;
    }

    private void attachProcessHandler(String serviceName, ProcessHandler handler) {
        if (handler == null || handler.isProcessTerminated() || handler.isProcessTerminating() || !attachedHandlers.add(handler)) {
            return;
        }

        ServiceStreamState state = new ServiceStreamState(serviceName);
        streamStates.put(handler, state);

        ApplicationManager.getApplication().invokeLater(() -> {
            pruneDeadHandlersAndSyncServices();
            allLogsConsole.print(String.format("[%s] [系统挂载] 已成功接入服务日志流: %s\n",
                    LocalTime.now().format(TIME_FMT), serviceName), ConsoleViewContentType.SYSTEM_OUTPUT);
        });

        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (paused) return;
                String text = event.getText();
                if (text == null || text.isEmpty()) return;

                boolean isSystem = ProcessOutputTypes.SYSTEM.equals(outputType);
                if (isSystem) {
                    return; // 忽略冗长启动命令行参数本身
                }

                synchronized (state.lineBuffer) {
                    state.lineBuffer.append(text);
                    int newlineIdx;
                    while ((newlineIdx = state.lineBuffer.indexOf("\n")) >= 0) {
                        String line = state.lineBuffer.substring(0, newlineIdx);
                        if (line.endsWith("\r")) {
                            line = line.substring(0, line.length() - 1);
                        }
                        state.lineBuffer.delete(0, newlineIdx + 1);
                        processSingleLine(state, line);
                    }
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                attachedHandlers.remove(handler);
                streamStates.remove(handler);
                ApplicationManager.getApplication().invokeLater(() -> {
                    allLogsConsole.print(String.format("[%s] [%s] ⏹ 服务已停止 (ExitCode=%d)\n",
                            LocalTime.now().format(TIME_FMT), serviceName, event.getExitCode()), ConsoleViewContentType.SYSTEM_OUTPUT);
                    pruneDeadHandlersAndSyncServices();
                });
            }
        }, this);
    }

    private void processSingleLine(ServiceStreamState state, String line) {
        if (line.trim().isEmpty()) {
            return;
        }

        String serviceName = state.serviceName;
        int prevErrorBlockId = state.currentErrorBlockId;
        Level level = classifyLine(state, line);
        int blockId = (level == Level.ERROR) ? state.currentErrorBlockId : 0;
        boolean isNewErrorBlock = (level == Level.ERROR && blockId != prevErrorBlockId);

        if (isNewErrorBlock) {
            serviceErrorCounts.merge(serviceName, 1, Integer::sum);
            totalErrorBlocks.incrementAndGet();
        }

        String now = LocalTime.now().format(TIME_FMT);
        LogRecord record = new LogRecord(now, serviceName, line, level, blockId);
        logBuffer.addLast(record);
        while (logBuffer.size() > MAX_BUFFER_LINES) {
            logBuffer.pollFirst();
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            if (isNewErrorBlock) {
                refreshServiceDropdown();
                updateStatusLabel();
            }
            if (matchesFilter(record)) {
                printRecordToAllConsole(record);
            }
            if (record.level == Level.ERROR) {
                if (keywordFilter.isEmpty()) {
                    if (matchesService(record)) {
                        printRecordToErrorConsole(record);
                    }
                } else {
                    // 当存在搜索关键字时，若当前行或同属该报错块的其他行匹配，重绘以保证整块堆栈完整展示
                    if (matchesServiceAndKeyword(record) || isErrorBlockMatchingKeyword(record.errorBlockId)) {
                        rebuildErrorConsoleOnly();
                    }
                }
            }
        });
    }

    private Level classifyLine(ServiceStreamState state, String rawLine) {
        String cleanLine = stripAnsi(rawLine);

        // 1. 优先识别以日期/时间开头的标准日志头，严格只从日志头区域提取级别，绝不扫描业务正文或 SQL
        if (TIMESTAMP_LINE_START_PATTERN.matcher(cleanLine).find()) {
            String headerRegion = extractLogHeaderRegion(cleanLine);
            Matcher levelMatcher = HEADER_LEVEL_TOKEN_PATTERN.matcher(headerRegion);
            if (levelMatcher.find()) {
                String lvl = levelMatcher.group(1);
                if ("ERROR".equals(lvl) || "FATAL".equals(lvl)) {
                    startOrContinueErrorBlock(state);
                    return Level.ERROR;
                } else if ("WARN".equals(lvl) || "WARNING".equals(lvl)) {
                    endErrorBlock(state);
                    return Level.WARN;
                } else {
                    // INFO / DEBUG / TRACE 强制清空报错状态，绝不被前序报错连坐
                    endErrorBlock(state);
                    return Level.INFO;
                }
            }
            // 带时间戳但未匹配到 ERROR/WARN 的普通日志行，同样结束前序报错块
            endErrorBlock(state);
            return Level.INFO;
        }

        // 2. 无时间戳行：检查是否为标准 Java 异常头（如 java.lang.NullPointerException: ... / Caused by: ...）
        if (STANDALONE_EXCEPTION_HEAD_PATTERN.matcher(cleanLine).find()) {
            startOrContinueErrorBlock(state);
            return Level.ERROR;
        }

        // 3. 无时间戳行：检查是否为异常堆栈帧（\tat pkg.Class.method(File.java:52) / ... N more）
        if (STACK_LINE_PATTERN.matcher(cleanLine).find()) {
            if (state.currentErrorBlockId == 0) {
                startOrContinueErrorBlock(state);
            } else {
                state.remainingErrorLines = MAX_STACK_CONTINUATION_LINES;
            }
            return Level.ERROR;
        }

        // 4. 如果当前正处于报错块中，允许紧接在 ERROR 日志头后面的多行异常描述延续（但排除 MyBatis ==> / <== 等调试行）
        if (state.remainingErrorLines > 0) {
            String trimmed = cleanLine.trim();
            if (trimmed.startsWith("==>") || trimmed.startsWith("<==")) {
                endErrorBlock(state);
                return Level.INFO;
            }
            state.remainingErrorLines--;
            if (state.remainingErrorLines == 0) {
                state.currentErrorBlockId = 0;
            }
            return Level.ERROR;
        }

        return Level.INFO;
    }

    private void startOrContinueErrorBlock(ServiceStreamState state) {
        if (state.currentErrorBlockId == 0) {
            state.currentErrorBlockId = nextErrorBlockId.getAndIncrement();
        }
        state.remainingErrorLines = MAX_STACK_CONTINUATION_LINES;
    }

    private void endErrorBlock(ServiceStreamState state) {
        state.remainingErrorLines = 0;
        state.currentErrorBlockId = 0;
    }

    private String stripAnsi(String input) {
        if (input.indexOf('\u001B') < 0) {
            return input;
        }
        return ANSI_ESCAPE_PATTERN.matcher(input).replaceAll("");
    }

    private String extractLogHeaderRegion(String cleanLine) {
        int dashIdx = cleanLine.indexOf(" - ");
        int limit = Math.min(cleanLine.length(), 110);
        if (dashIdx > 0 && dashIdx < limit) {
            limit = dashIdx;
        }
        return cleanLine.substring(0, limit);
    }

    private boolean matchesService(LogRecord rec) {
        return "全部服务 (All)".equals(selectedService) || selectedService.equals(rec.serviceName);
    }

    private boolean matchesServiceAndKeyword(LogRecord rec) {
        if (!matchesService(rec)) {
            return false;
        }
        String kw = keywordFilter;
        if (!kw.isEmpty()) {
            String lowerKw = kw.toLowerCase(Locale.ROOT);
            return rec.rawLine.toLowerCase(Locale.ROOT).contains(lowerKw)
                    || rec.serviceName.toLowerCase(Locale.ROOT).contains(lowerKw);
        }
        return true;
    }

    private boolean isErrorBlockMatchingKeyword(int blockId) {
        if (blockId <= 0 || keywordFilter.isEmpty()) {
            return false;
        }
        String lowerKw = keywordFilter.toLowerCase(Locale.ROOT);
        for (LogRecord rec : logBuffer) {
            if (rec.errorBlockId == blockId && matchesService(rec)) {
                if (rec.rawLine.toLowerCase(Locale.ROOT).contains(lowerKw)
                        || rec.serviceName.toLowerCase(Locale.ROOT).contains(lowerKw)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesFilter(LogRecord rec) {
        if (!matchesServiceAndKeyword(rec)) {
            return false;
        }
        if (selectedLevelIndex == 1) {
            return rec.level == Level.WARN || rec.level == Level.ERROR;
        } else if (selectedLevelIndex == 2) {
            return rec.level == Level.ERROR;
        }
        return true;
    }

    private void printRecordToAllConsole(LogRecord rec) {
        String prefix = String.format("[%s] ", rec.serviceName);
        allLogsConsole.print(prefix, ConsoleViewContentType.SYSTEM_OUTPUT);

        ConsoleViewContentType contentType;
        if (rec.level == Level.ERROR) {
            contentType = ConsoleViewContentType.ERROR_OUTPUT;
        } else if (rec.level == Level.WARN) {
            contentType = ConsoleViewContentType.LOG_WARNING_OUTPUT;
        } else {
            contentType = ConsoleViewContentType.NORMAL_OUTPUT;
        }
        allLogsConsole.print(rec.rawLine + "\n", contentType);
    }

    private void printRecordToErrorConsole(LogRecord rec) {
        if (rec.errorBlockId != lastPrintedErrorBlockId) {
            lastPrintedErrorBlockId = rec.errorBlockId;
            errorLogsConsole.print(String.format(
                    "\n==================== 🔥 [%s] 报错服务: 【%s】 ====================\n",
                    rec.time, rec.serviceName), ConsoleViewContentType.SYSTEM_OUTPUT);
        }
        errorLogsConsole.print("[" + rec.serviceName + "] " + rec.rawLine + "\n", ConsoleViewContentType.ERROR_OUTPUT);
    }

    private void onKeywordChanged() {
        keywordFilter = keywordField.getText().trim();
        rebuildConsolesFromBuffer();
    }

    private void rebuildErrorConsoleOnly() {
        errorLogsConsole.clear();
        lastPrintedErrorBlockId = -1;

        Set<Integer> matchedBlocks = null;
        if (!keywordFilter.isEmpty()) {
            matchedBlocks = new HashSet<>();
            String lowerKw = keywordFilter.toLowerCase(Locale.ROOT);
            for (LogRecord rec : logBuffer) {
                if (rec.level == Level.ERROR && matchesService(rec)) {
                    if (rec.rawLine.toLowerCase(Locale.ROOT).contains(lowerKw)
                            || rec.serviceName.toLowerCase(Locale.ROOT).contains(lowerKw)) {
                        matchedBlocks.add(rec.errorBlockId);
                    }
                }
            }
        }

        for (LogRecord rec : logBuffer) {
            if (rec.level == Level.ERROR && matchesService(rec)) {
                if (matchedBlocks == null || matchedBlocks.contains(rec.errorBlockId)) {
                    printRecordToErrorConsole(rec);
                }
            }
        }
    }

    private void rebuildConsolesFromBuffer() {
        allLogsConsole.clear();
        for (LogRecord rec : logBuffer) {
            if (matchesFilter(rec)) {
                printRecordToAllConsole(rec);
            }
        }
        rebuildErrorConsoleOnly();
    }

    private void clearAllLogs() {
        logBuffer.clear();
        serviceErrorCounts.clear();
        totalErrorBlocks.set(0);
        lastPrintedErrorBlockId = -1;
        for (ServiceStreamState st : streamStates.values()) {
            endErrorBlock(st);
        }
        allLogsConsole.clear();
        errorLogsConsole.clear();
        pruneDeadHandlersAndSyncServices();
    }

    private void refreshServiceDropdown() {
        String current = selectedService;
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("全部服务 (All)");
        for (String s : knownServices) {
            int errCnt = serviceErrorCounts.getOrDefault(s, 0);
            if (errCnt > 0) {
                model.addElement(s + " (🔥" + errCnt + ")");
            } else {
                model.addElement(s);
            }
        }
        serviceSelector.setModel(model);
        for (int i = 0; i < model.getSize(); i++) {
            if (extractRawServiceName(model.getElementAt(i)).equals(current)) {
                serviceSelector.setSelectedIndex(i);
                break;
            }
        }
    }

    private String extractRawServiceName(String item) {
        int idx = item.indexOf(" (🔥");
        if (idx > 0) {
            return item.substring(0, idx);
        }
        return item;
    }

    private void updateStatusLabel() {
        StringBuilder sb = new StringBuilder();
        sb.append("已挂载服务: ").append(attachedHandlers.size()).append(" 个");
        sb.append(" | 累计异常: ").append(totalErrorBlocks.get());
        if (!serviceErrorCounts.isEmpty()) {
            sb.append(" (");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : serviceErrorCounts.entrySet()) {
                if (e.getValue() > 0) {
                    parts.add(e.getKey() + ":" + e.getValue());
                }
            }
            sb.append(String.join(", ", parts)).append(")");
        }
        statusLabel.setText(sb.toString());
        if (!splitViewCheck.isSelected() && tabbedPane.getTabCount() >= 2) {
            tabbedPane.setTitleAt(1, "🔥 纯报错汇总 (Errors: " + totalErrorBlocks.get() + ")");
        }
    }

    private boolean isProtectedIdeOrToolProcess(String mainClass) {
        if (mainClass == null || mainClass.isEmpty()) return true;
        String lower = mainClass.toLowerCase(Locale.ROOT);
        return lower.contains("com.intellij.idea.main")
                || lower.contains("sun.tools.jps")
                || lower.contains("jdk.jcmd")
                || lower.contains("finalshell")
                || lower.contains(" kotlin")
                || lower.startsWith("--");
    }

    private void killBackendJavaProcesses() {
        // 1. 先同步终止 IDEA 当前挂载的所有存活会话句柄
        List<String> killedList = Collections.synchronizedList(new ArrayList<>());
        for (ProcessHandler h : new ArrayList<>(attachedHandlers)) {
            ServiceStreamState st = streamStates.remove(h);
            attachedHandlers.remove(h);
            if (h != null && !h.isProcessTerminated()) {
                try {
                    h.destroyProcess();
                    if (st != null) {
                        killedList.add(st.serviceName + " (IDEA会话)");
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        knownServices.clear();
        selectedService = "全部服务 (All)";
        refreshServiceDropdown();
        updateStatusLabel();

        // 2. 后台扫描系统级 jps 清理残留孤儿进程
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Process jpsProc = new ProcessBuilder("jps", "-l").redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(jpsProc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+", 2);
                        if (parts.length == 2) {
                            String pid = parts[0];
                            String mainClass = parts[1];
                            if (!isProtectedIdeOrToolProcess(mainClass)) {
                                new ProcessBuilder("taskkill", "/F", "/PID", pid).start().waitFor();
                                killedList.add(mainClass + " (PID:" + pid + ")");
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                ApplicationManager.getApplication().invokeLater(() ->
                        allLogsConsole.print("[系统] 清理进程时异常: " + ex.getMessage() + "\n", ConsoleViewContentType.ERROR_OUTPUT)
                );
                return;
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                pruneDeadHandlersAndSyncServices();
                if (killedList.isEmpty()) {
                    allLogsConsole.print("[系统清理] 当前后台没有发现残留的后台 Java 服务或编译进程，下拉列表已同步清空。\n", ConsoleViewContentType.SYSTEM_OUTPUT);
                } else {
                    allLogsConsole.print("[系统清理] 已成功强杀 " + killedList.size() + " 个后台进程，并已同步清空下拉列表:\n", ConsoleViewContentType.SYSTEM_OUTPUT);
                    for (String item : killedList) {
                        allLogsConsole.print("  ✓ " + item + "\n", ConsoleViewContentType.SYSTEM_OUTPUT);
                    }
                }
            });
        });
    }

    private Filter createStackTraceFileFilter(Project project) {
        return new Filter() {
            @Override
            public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
                Matcher matcher = FILE_LINE_LINK_PATTERN.matcher(line);
                List<ResultItem> items = null;
                int lineStartOffset = entireLength - line.length();

                while (matcher.find()) {
                    final String fqClassName = matcher.group(1);
                    final String fileName = matcher.group(2);
                    final String simpleName = matcher.group(3);
                    final String lineStr = matcher.group(5);

                    int parsedLine = 1;
                    try {
                        parsedLine = Integer.parseInt(lineStr);
                    } catch (NumberFormatException ignored) {
                    }
                    final int targetLine = Math.max(1, parsedLine);

                    int linkStart = lineStartOffset + matcher.start(2);
                    int linkEnd = lineStartOffset + matcher.end(5);

                    HyperlinkInfo hyperlinkInfo = new HyperlinkInfo() {
                        @Override
                        public void navigate(@NotNull Project p) {
                            navigateToFileAndLine(p, fqClassName, fileName, simpleName, targetLine);
                        }
                    };

                    if (items == null) {
                        items = new ArrayList<>(2);
                    }
                    items.add(new ResultItem(linkStart, linkEnd, hyperlinkInfo));
                }

                if (items == null || items.isEmpty()) {
                    return null;
                }
                return new Result(items);
            }
        };
    }

    private void navigateToFileAndLine(Project project, @Nullable String fqClassName, String fileName, String simpleName, int oneBasedLine) {
        if (project.isDisposed()) return;
        GlobalSearchScope allScope = GlobalSearchScope.allScope(project);

        Collection<VirtualFile> candidates = findFilesByNameCompat(project, fileName, allScope);
        if (candidates.isEmpty()) {
            candidates = findFilesByNameCompat(project, simpleName + ".class", allScope);
        }

        if (candidates.isEmpty()) {
            return;
        }

        VirtualFile bestMatch = selectBestMatchingFile(candidates, fqClassName);
        if (bestMatch != null) {
            new OpenFileDescriptor(project, bestMatch, Math.max(0, oneBasedLine - 1), 0).navigate(true);
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<VirtualFile> findFilesByNameCompat(Project project, String name, GlobalSearchScope scope) {
        try {
            return FilenameIndex.getVirtualFilesByName(name, scope);
        } catch (Throwable ignored) {
            try {
                java.lang.reflect.Method m = FilenameIndex.class.getMethod(
                        "getVirtualFilesByName", Project.class, String.class, GlobalSearchScope.class);
                return (Collection<VirtualFile>) m.invoke(null, project, name, scope);
            } catch (Throwable ex) {
                return Collections.emptyList();
            }
        }
    }

    private @Nullable VirtualFile selectBestMatchingFile(Collection<VirtualFile> files, @Nullable String fqClassName) {
        if (files.isEmpty()) return null;
        if (files.size() == 1 || fqClassName == null || fqClassName.isEmpty()) {
            return files.iterator().next();
        }

        int lastDot = fqClassName.lastIndexOf('.');
        String pkgPath = lastDot > 0 ? fqClassName.substring(0, lastDot).replace('.', '/') : "";

        for (VirtualFile vf : files) {
            String path = vf.getPath().replace('\\', '/');
            if (!pkgPath.isEmpty() && path.contains(pkgPath)) {
                return vf;
            }
        }
        return files.iterator().next();
    }

    public JComponent getRootComponent() {
        return rootPanel;
    }

    @Override
    public void dispose() {
        attachedHandlers.clear();
        streamStates.clear();
        logBuffer.clear();
    }
}
