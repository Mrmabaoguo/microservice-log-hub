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
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // 匹配任意 (Xxx.java:52) 或 Xxx.java:52 以及前置包名
    private static final Pattern FILE_LINE_LINK_PATTERN = Pattern.compile(
            "(?:([a-zA-Z0-9_$.]+)\\.[a-zA-Z0-9_$<>]+\\()?" +
            "(([a-zA-Z0-9_$]+)\\.(java|kt|groovy|scala)):(\\d+)"
    );

    // 匹配常见 ERROR / Exception / 堆栈行
    private static final Pattern ERROR_START_PATTERN = Pattern.compile(
            "(?i)(\\bERROR\\b|\\bFATAL\\b|\\bException\\b|Exception:|Caused by:|Suppressed:|\\[ERROR\\]|[a-zA-Z0-9_$.]+Exception|[a-zA-Z0-9_$.]+Error)"
    );
    private static final Pattern STACK_LINE_PATTERN = Pattern.compile(
            "^(\\s+at\\s+[a-zA-Z0-9_$.]+\\(|\\s*\\.\\.\\.\\s*\\d+\\s+more|\\s*Caused by:|\\s*Suppressed:)"
    );
    private static final Pattern NORMAL_LOG_HEAD_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}|\\d{2}:\\d{2}:\\d{2}).*\\b(INFO|DEBUG|TRACE|WARN)\\b.*"
    );
    private static final Pattern WARN_PATTERN = Pattern.compile("(?i)\\bWARN(ING)?\\b");

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

    private volatile String selectedService = "全部服务 (All)";
    private volatile int selectedLevelIndex = 0; // 0=全部级别, 1=WARN+ERROR, 2=仅ERROR
    private volatile String keywordFilter = "";
    private volatile boolean paused = false;
    private volatile String lastErrorBannerService = "";
    private volatile long lastErrorBannerTime = 0L;

    private static class LogRecord {
        final String time;
        final String serviceName;
        final String rawLine;
        final Level level;

        LogRecord(String time, String serviceName, String rawLine, Level level) {
            this.time = time;
            this.serviceName = serviceName;
            this.rawLine = rawLine;
            this.level = level;
        }
    }

    private enum Level {
        INFO, WARN, ERROR
    }

    private static class ServiceStreamState {
        final String serviceName;
        final StringBuilder lineBuffer = new StringBuilder();
        int remainingErrorLines = 0;

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
                    if (handler != null && !handler.isProcessTerminated()) {
                        String name = cleanServiceName(desc.getDisplayName());
                        attachProcessHandler(name, handler);
                    }
                }
                updateStatusLabel();
            } catch (Throwable ignored) {
            }
        });
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
        if (handler == null || !attachedHandlers.add(handler)) {
            return;
        }

        ServiceStreamState state = new ServiceStreamState(serviceName);
        streamStates.put(handler, state);

        ApplicationManager.getApplication().invokeLater(() -> {
            if (knownServices.add(serviceName)) {
                refreshServiceDropdown();
            }
            updateStatusLabel();
            allLogsConsole.print(String.format("[%s] [系统挂载] 已成功接入服务日志流: %s\n",
                    LocalTime.now().format(TIME_FMT), serviceName), ConsoleViewContentType.SYSTEM_OUTPUT);
        });

        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (paused) return;
                String text = event.getText();
                if (text == null || text.isEmpty()) return;

                boolean isStdErr = ProcessOutputTypes.STDERR.equals(outputType);
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
                        processSingleLine(state, line, isStdErr);
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
                    updateStatusLabel();
                });
            }
        }, this);
    }

    private void processSingleLine(ServiceStreamState state, String line, boolean isStdErr) {
        if (line.trim().isEmpty()) {
            return;
        }

        String serviceName = state.serviceName;
        Level level = classifyLine(state, line, isStdErr);
        String now = LocalTime.now().format(TIME_FMT);

        LogRecord record = new LogRecord(now, serviceName, line, level);
        logBuffer.addLast(record);
        while (logBuffer.size() > MAX_BUFFER_LINES) {
            logBuffer.pollFirst();
        }

        if (level == Level.ERROR) {
            if (ERROR_START_PATTERN.matcher(line).find() && !STACK_LINE_PATTERN.matcher(line).find()) {
                serviceErrorCounts.merge(serviceName, 1, Integer::sum);
                totalErrorBlocks.incrementAndGet();
            }
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            if (matchesFilter(record)) {
                printRecordToAllConsole(record);
            }
            if (record.level == Level.ERROR && matchesServiceAndKeyword(record)) {
                printRecordToErrorConsole(record);
                updateStatusLabel();
            }
        });
    }

    private Level classifyLine(ServiceStreamState state, String line, boolean isStdErr) {
        if (NORMAL_LOG_HEAD_PATTERN.matcher(line).matches()) {
            state.remainingErrorLines = 0;
            if (WARN_PATTERN.matcher(line).find()) {
                return Level.WARN;
            }
            return Level.INFO;
        }

        if (isStdErr || ERROR_START_PATTERN.matcher(line).find()) {
            state.remainingErrorLines = 60;
            return Level.ERROR;
        }

        if (state.remainingErrorLines > 0) {
            state.remainingErrorLines--;
            return Level.ERROR;
        }

        if (WARN_PATTERN.matcher(line).find()) {
            return Level.WARN;
        }

        return Level.INFO;
    }

    private boolean matchesServiceAndKeyword(LogRecord rec) {
        if (!"全部服务 (All)".equals(selectedService) && !selectedService.equals(rec.serviceName)) {
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
        long nowMs = System.currentTimeMillis();
        if (!rec.serviceName.equals(lastErrorBannerService) || (nowMs - lastErrorBannerTime) > 2500L) {
            lastErrorBannerService = rec.serviceName;
            lastErrorBannerTime = nowMs;
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

    private void rebuildConsolesFromBuffer() {
        allLogsConsole.clear();
        errorLogsConsole.clear();
        lastErrorBannerService = "";
        lastErrorBannerTime = 0L;

        for (LogRecord rec : logBuffer) {
            if (matchesFilter(rec)) {
                printRecordToAllConsole(rec);
            }
            if (rec.level == Level.ERROR && matchesServiceAndKeyword(rec)) {
                printRecordToErrorConsole(rec);
            }
        }
    }

    private void clearAllLogs() {
        logBuffer.clear();
        serviceErrorCounts.clear();
        totalErrorBlocks.set(0);
        lastErrorBannerService = "";
        lastErrorBannerTime = 0L;
        allLogsConsole.clear();
        errorLogsConsole.clear();
        refreshServiceDropdown();
        updateStatusLabel();
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
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<String> killedList = new ArrayList<>();
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
                if (killedList.isEmpty()) {
                    allLogsConsole.print("[系统清理] 当前后台没有发现残留的后台 Java 服务或编译进程。\n", ConsoleViewContentType.SYSTEM_OUTPUT);
                } else {
                    allLogsConsole.print("[系统清理] 已成功强杀 " + killedList.size() + " 个后台进程:\n", ConsoleViewContentType.SYSTEM_OUTPUT);
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
                    final String fqClassName = matcher.group(1); // e.g. reactor.core.publisher.MonoDefer
                    final String fileName = matcher.group(2);    // e.g. MonoDefer.java
                    final String simpleName = matcher.group(3);  // e.g. MonoDefer
                    final String lineStr = matcher.group(5);     // e.g. 52

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

        // 1. 先按源码文件名查找 (如 MonoDefer.java 或 UserController.java)
        Collection<VirtualFile> candidates = findFilesByNameCompat(project, fileName, allScope);

        // 2. 如果第三方依赖没有下载 -sources.jar，退化查找编译后的 .class 文件 (如 MonoDefer.class)，由 IDEA 自动反编译并跳转对应行
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

        // 优先匹配包路径一致且在项目源码中的文件
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
