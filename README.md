# Microservice Log & Error Hub (IDEA 微服务全量日志与报错总控台)

一款专为多微服务（Spring Boot / Java 等）本地联调打造的轻量级 IntelliJ IDEA 插件。解决本地启动十几个微服务时，需要挨个点开控制台找报错、看日志的痛点。

## ✨ 核心功能

1. **📋 全量实时合并日志（左屏 / Tab 1）**
   - 自动监听并挂载 IDEA 中所有通过 Run / Debug 启动的服务进程。
   - 所有服务的 `INFO` / `DEBUG` / `SQL` / `WARN` / `ERROR` 实时按时间流式汇总在同一窗口，每行自动前置醒目的 `[服务名称]`。
2. **🔥 纯报错堆栈提取台（右屏 / Tab 2）**
   - 一旦任意微服务抛出 `Exception` 或 `ERROR`，自动拦截完整异常堆栈并标注 `==== 🔥 [时间] 报错服务: 【XXX】 ====`。
   - 不用再挨个翻十几个控制台找是哪个服务报错。
3. **🔗 源码蓝字超链接一键跳转**
   - 直接复用 IDEA 原生 `ConsoleView` 渲染引擎，点击报错堆栈中的 `(XxxController.java:85)` 直接跳转到对应源码行。
4. **🔍 多维实时过滤**
   - 支持按 **单个服务筛选**（自动统计各服务报错次数 `🔥N`）、按 **日志级别（ALL / WARN+ERROR / ERROR）** 筛选、按 **关键字实时检索**。
   - 支持 **左右分屏同看** 与 **标签页切换** 两种布局。
5. **🧹 一键强杀残留孤儿 Java 服务**
   - 内置 **【强杀残留Java服务】** 按钮，一键扫描并清理关闭 IDEA 后残留在后台占用端口的孤儿 Java 服务与 `ExternalJavacProcess` 编译进程（自动保护 IDEA 本体与 FinalShell）。

## 📦 安装方式

1. 下载 Release 或 `dist/microservice-log-hub-1.1.0.jar`。
2. 打开 IntelliJ IDEA -> `Settings (Ctrl+Alt+S)` -> `Plugins` -> 点击右上角齿轮 `⚙️` -> `Install Plugin from Disk...`。
3. 选择 `microservice-log-hub-1.1.0.jar` 完成安装。
4. 在底部工具栏或顶部菜单 `View -> Tool Windows -> 微服务总控台` 打开面板。

## 🛠️ 本地一键编译打包

在 PowerShell 7 中执行：

```powershell
pwsh ./build.ps1
```
